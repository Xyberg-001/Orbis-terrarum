package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.mixin.client.CreateWorldScreenInvoker;
import com.berg.orbis.osm.OsmRegionManager;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldHeight;
import com.berg.orbis.worldgen.WorldModel;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Holds "Create New World" on a progress screen until the world can actually be created, then resumes the
 * vanilla creation step. Two things are waited for:
 * <ul>
 *   <li>the four map regions around the spawn (without this the server thread blocked inside the spawn
 *   search waiting for Overpass, which can take minutes, and the game showed "not responding");</li>
 *   <li>the world's height: the settings' value (or a fit to the terrain around the origin) is resolved,
 *   written as a data pack into the folder the new world takes its data packs from, and the creation context
 *   is reloaded so the overworld's dimension type carries that height (see {@link WorldHeight}).</li>
 * </ul>
 */
public final class SpawnGate {
    private static CreateWorldScreen pending;
    private static Screen shown;
    private static int ticks;
    private static long startedAt;
    private static CompletableFuture<Integer> fit;
    /** Non-zero while the creation context is reloading with the height pack; the value it must reach. */
    private static int awaitingHeight;
    private static long awaitingSince;

    private SpawnGate() {}

    private static RealWorldChunkGenerator generatorForScreen(CreateWorldScreen screen) {
        try {
            ChunkGenerator gen = screen.getUiState().getSettings().selectedDimensions().overworld();
            if (gen instanceof RealWorldChunkGenerator rw) return rw;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static WorldModel modelForScreen(CreateWorldScreen screen) {
        // The world's own settings decide which model (and which spawn) we are waiting for.
        RealWorldChunkGenerator rw = generatorForScreen(screen);
        return rw != null ? rw.model() : OrbisMod.model();
    }

    /** The overworld height the creation context currently carries. */
    private static int contextHeight(CreateWorldScreen screen) {
        try {
            LevelStem stem = screen.getUiState().getSettings().selectedDimensions().dimensions().get(LevelStem.OVERWORLD);
            return stem == null ? OrbisConfig.DIMENSION_HEIGHT : stem.type().value().height();
        } catch (RuntimeException e) {
            return OrbisConfig.DIMENSION_HEIGHT;
        }
    }

    /** True when the world's height still has to be resolved or applied to the creation context. */
    private static boolean heightPending(CreateWorldScreen screen) {
        RealWorldChunkGenerator rw = generatorForScreen(screen);
        if (rw == null) return false;
        WorldSettings s = rw.settings().orElse(null);
        // Not customised: the installation defaults, whose height still has to be worked out and applied (the
        // world type's own dimension type is vanilla's; an Orbis world's height always comes from its pack).
        if (s == null) return true;
        if (s.worldHeight <= 0) return true;
        return WorldHeight.snap(s.worldHeight) != contextHeight(screen);
    }

    public static boolean shouldWait() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof CreateWorldScreen screen)) return false;
        if (generatorForScreen(screen) == null) return false; // not an Orbis world: nothing to wait for
        WorldModel m = modelForScreen(screen);
        OsmRegionManager regions = m == null ? null : m.regions();
        boolean spawn = regions != null && m.cfg().waitForOsm && !regions.spawnCoreReady();
        return spawn || heightPending(screen);
    }

    public static void begin(CreateWorldScreen screen) {
        WorldModel m = modelForScreen(screen);
        if (m != null && m.regions() != null) m.regions().queueSpawnArea();
        pending = screen;
        ticks = 0;
        startedAt = System.currentTimeMillis();
        awaitingHeight = 0;
        if (heightPending(screen) && fit == null) {
            RealWorldChunkGenerator rw = generatorForScreen(screen);
            int requested = rw != null && rw.settings().isPresent() ? rw.settings().get().worldHeight : OrbisMod.config().worldHeight;
            WorldModel model = m;
            fit = CompletableFuture.supplyAsync(() -> WorldHeight.resolve(requested, model));
        }
        show();
    }

    private static void show() {
        Minecraft mc = Minecraft.getInstance();
        WorldModel m = modelForScreen(pending);
        OsmRegionManager regions = m == null ? null : m.regions();
        int ready = regions == null ? 0 : regions.spawnRegionsReady();
        int total = regions == null ? 0 : regions.spawnRegionsTotal();
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        String place = m == null ? "" : String.format(java.util.Locale.ROOT, "%.4f, %.4f", m.cfg().originLat, m.cfg().originLon);
        Component text = Component.translatable("orbisterrarum.spawnwait.text", place, ready, total, seconds)
                .append("\n\n")
                .append(Component.translatable(seconds > 90 ? "orbisterrarum.spawnwait.slow" : "orbisterrarum.spawnwait.hint"));
        CreateWorldScreen back = pending;
        shown = new AlertScreen(() -> {
            pending = null;
            shown = null;
            fit = null;
            mc.gui.setScreen(back);
        }, Component.translatable("orbisterrarum.spawnwait.title"), text, Component.translatable("gui.back"), true);
        mc.gui.setScreen(shown);
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        if (pending == null) return;
        CreateWorldScreen screen = pending;
        if (awaitingHeight != 0) {
            // The creation context is reloading with the height pack. Once the create screen is back and the
            // context carries the height, put the world's settings back on the generator and create.
            boolean back = mc.gui.screen() == screen;
            if (back && contextHeight(screen) == awaitingHeight) {
                int height = awaitingHeight;
                awaitingHeight = 0;
                pending = null;
                fit = null;
                applySettings(screen, height);
                ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
            } else if (System.currentTimeMillis() - awaitingSince > 120_000) {
                System.err.println("[orbis] World height: the creation context did not pick up the height pack; creating with the full height");
                awaitingHeight = 0;
                pending = null;
                fit = null;
                if (back) ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
            }
            return;
        }
        if (shown != null && mc.gui.screen() != shown) {
            // The player left our screen some other way: stop waiting.
            pending = null;
            shown = null;
            fit = null;
            return;
        }
        WorldModel m = modelForScreen(screen);
        OsmRegionManager regions = m == null ? null : m.regions();
        boolean spawnReady = regions == null || regions.spawnCoreReady();
        boolean fitReady = !heightPending(screen) || (fit != null && fit.isDone());
        if (spawnReady && fitReady) {
            shown = null;
            mc.gui.setScreen(screen);
            if (heightPending(screen)) {
                int height;
                try {
                    height = fit.join();
                } catch (RuntimeException e) {
                    System.err.println("[orbis] World height fit failed: " + e);
                    height = OrbisConfig.DIMENSION_HEIGHT;
                }
                applyHeight(screen, height);
                return;
            }
            pending = null;
            fit = null;
            ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
            return;
        }
        if (++ticks % 20 == 0) show();
    }

    /** Stores the resolved height in the world's settings on the generator (they are saved with the world). */
    private static void applySettings(CreateWorldScreen screen, int height) {
        RealWorldChunkGenerator rw = generatorForScreen(screen);
        WorldSettings s = rw != null && rw.settings().isPresent() ? rw.settings().get().copy() : OrbisMod.defaultWorldSettings();
        s.worldHeight = height;
        screen.getUiState().updateDimensions((registries, dimensions) -> dimensions.replaceOverworldGenerator(registries,
                new RealWorldChunkGenerator(dimensions.overworld().getBiomeSource(), Optional.of(s))));
    }

    /** Gives the world its height: the data pack into the new world's pack folder, then a context reload. */
    private static void applyHeight(CreateWorldScreen screen, int height) {
        CreateWorldScreenInvoker inv = (CreateWorldScreenInvoker) screen;
        if (height == contextHeight(screen)) {
            // Nothing to override (full height, or the pack is already in place): only the settings change.
            pending = null;
            fit = null;
            applySettings(screen, height);
            inv.orbis$invokeOnCreate();
            return;
        }
        try {
            Path temp = inv.orbis$tempDataPackDir();
            WorldHeight.writePack(temp, height);
            Pair<Path, PackRepository> selection = inv.orbis$dataPackSelection(screen.getUiState().getSettings().dataConfiguration());
            PackRepository repository = selection.getSecond();
            repository.reload();
            List<String> ids = new ArrayList<>(repository.getSelectedIds());
            String id = "file/" + WorldHeight.PACK_NAME;
            if (!ids.contains(id)) ids.add(id);
            repository.setSelected(ids);
            awaitingHeight = height;
            awaitingSince = System.currentTimeMillis();
            inv.orbis$applyDataPacks(repository, false, configuration -> {});
        } catch (Exception e) {
            System.err.println("[orbis] Could not apply the world height pack: " + e);
            e.printStackTrace();
            awaitingHeight = 0;
            pending = null;
            fit = null;
            applySettings(screen, OrbisConfig.DIMENSION_HEIGHT);
            inv.orbis$invokeOnCreate();
        }
    }
}
