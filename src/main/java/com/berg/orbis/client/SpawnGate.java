package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.DataSources;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.mixin.client.CreateWorldScreenInvoker;
import com.berg.orbis.osm.OsmRegionManager;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldHeight;
import com.berg.orbis.worldgen.WorldModel;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
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
    /** The high-altitude window the fit chose for the world being created, or null (see WorldHeight.window). */
    private static volatile WorldHeight.Window window;
    /** The uniform heights the fit chose for the world being created, or null (see WorldHeight.uniform). */
    private static volatile WorldHeight.Uniform uniform;
    /** Non-zero while the creation context is reloading with the height pack; the value it must reach. */
    private static int awaitingHeight;
    private static long awaitingSince;

    private SpawnGate() {}

    static RealWorldChunkGenerator generatorForScreen(CreateWorldScreen screen) {
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

    /**
     * True while the new world's structure pack (fewer mineshafts, trial chambers and Ancient Cities) is not yet in
     * the creation context; it goes in with the height pack, in the same data pack reload.
     */
    private static boolean structuresPending(CreateWorldScreen screen) {
        if (generatorForScreen(screen) == null || OrbisMod.config().undergroundStructureShare >= 1) return false;
        try {
            return !screen.getUiState().getSettings().dataConfiguration().dataPacks().getEnabled()
                    .contains("file/" + com.berg.orbis.worldgen.StructureDensity.PACK_NAME);
        } catch (RuntimeException e) {
            return false;
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

    /** The create screen whose notice was read (Continue): creating it again does not show the notice again. */
    private static CreateWorldScreen noticeRead;
    private static Screen noticeShown;

    /**
     * Before anything is generated: if the world will use a service that needs something of the player (a VPN to
     * Norway for Kartverket's lidar, patience for slow services), says so, with whether each one answers from this
     * network (checked while the notice is up). Continue creates the world. True when the notice is shown.
     */
    /**
     * Puts what was chosen on the world generator map into the new world's settings: the area (its pre-generation and
     * hard limit, and the area the world height is fitted to), the spawn (the world's centre) and the two
     * pre-generation switches. Customize hands them over when saved, but leaving Customize with Done saves nothing
     * when only the map changed, and the world then fell back to the defaults'.
     */
    public static void takeSelection(CreateWorldScreen screen) {
        RealWorldChunkGenerator rw = generatorForScreen(screen);
        if (rw == null) return;
        List<OrbisConfig.PregenShape> shapes = com.berg.orbis.client.map.MapSelectTool.previewShapes();
        WorldSettings s = rw.settings().isPresent() ? rw.settings().get().copy() : OrbisMod.defaultWorldSettings();
        boolean skipSea = com.berg.orbis.client.map.MapSelectTool.previewSkipsSea();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        boolean choices = com.berg.orbis.client.map.PreviewChoices.applyTo(s);
        // Nothing selected: nothing to pre-generate, and no hard limit. Left on (they carry over from the last world),
        // the first area pre-generated later would quietly become the only part of the world that ever generates.
        if (shapes.isEmpty() && (s.pregenArea == null || s.pregenArea.isBlank()) && (s.pregenOnCreate || s.pregenHardLimit)) {
            s.pregenOnCreate = false;
            s.pregenHardLimit = false;
            choices = true;
        }
        if (rw.settings().isPresent() && !choices && gson.toJson(shapes).equals(gson.toJson(s.pregenShapes)) && s.pregenSelectionSkipsSea == skipSea) return;
        s.pregenShapes = new ArrayList<>(shapes);
        s.pregenSelectionSkipsSea = skipSea;
        screen.getUiState().updateDimensions((registries, dimensions) -> dimensions.replaceOverworldGenerator(registries,
                new RealWorldChunkGenerator(dimensions.overworld().getBiomeSource(), Optional.of(s))));
    }

    /** Offers Geofabrik's map data for the new world's area when none is on disk (MapDataOffer); true while it waits. */
    public static boolean offerMapData(CreateWorldScreen screen) {
        return MapDataOffer.offer(screen);
    }

    /** The world is being created with the selection: clear the generator map for the next world. */
    public static void selectionTaken(CreateWorldScreen screen) {
        if (generatorForScreen(screen) != null) {
            com.berg.orbis.client.map.MapSelectTool.clearPreview();
            com.berg.orbis.client.map.PreviewChoices.clear();
        }
    }

    public static boolean showNeeds(CreateWorldScreen screen) {
        if (noticeRead == screen) return false; // read and accepted for this world: creation resuming
        if (generatorForScreen(screen) == null) return false;
        WorldModel m = modelForScreen(screen);
        if (m == null) return false;
        List<DataSources.Requirement> needs = DataSources.requirements(m.cfg());
        if (needs.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        java.util.Map<DataSources.Requirement, Boolean> answers = new java.util.concurrent.ConcurrentHashMap<>();
        Runnable show = () -> {
            noticeShown = new net.minecraft.client.gui.screens.ConfirmScreen(go -> {
                noticeShown = null;
                mc.setScreenAndShow(screen);
                if (go) {
                    noticeRead = screen;
                    ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
                }
            }, Component.translatable("orbisterrarum.needs.title"), needsText(needs, answers),
                    Component.translatable("orbisterrarum.needs.continue"), net.minecraft.network.chat.CommonComponents.GUI_BACK);
            mc.setScreenAndShow(noticeShown);
        };
        show.run();
        CompletableFuture.runAsync(() -> {
            for (DataSources.Requirement r : needs) answers.put(r, DataSources.reachable(r));
        }).thenRun(() -> mc.execute(() -> {
            if (noticeShown != null && mc.gui.screen() == noticeShown) show.run();
        }));
        return true;
    }

    private static Component needsText(List<DataSources.Requirement> needs, java.util.Map<DataSources.Requirement, Boolean> answers) {
        net.minecraft.network.chat.MutableComponent text = Component.empty();
        for (DataSources.Requirement r : needs) {
            Boolean ok = answers.get(r);
            text.append(Component.literal(r.name()).withStyle(net.minecraft.ChatFormatting.GOLD)).append("\n")
                    .append(r.text()).append("\n")
                    .append(ok == null ? Component.translatable("orbisterrarum.needs.checking").withStyle(net.minecraft.ChatFormatting.GRAY)
                            : ok ? Component.translatable("orbisterrarum.needs.ok").withStyle(net.minecraft.ChatFormatting.GREEN)
                            : Component.translatable("orbisterrarum.needs.down").withStyle(net.minecraft.ChatFormatting.RED))
                    .append("\n\n");
        }
        return text;
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
            window = null;
            uniform = null;
            fit = CompletableFuture.supplyAsync(() -> {
                // Uniform heights: one factor for every height, as small as the area's highest ground allows.
                WorldHeight.Uniform u = WorldHeight.uniform(model, requested);
                uniform = u;
                if (u != null) return requested > 0 ? WorldHeight.snap(requested) : WorldHeight.uniformHeight(model, u);
                // A world of mountains with no sea gets its heights laid over its own range (worked out with the
                // model as it is: the elevations, not the mapping, decide it).
                WorldHeight.Window w = requested <= 0 ? WorldHeight.window(model) : null;
                window = w;
                return w != null ? WorldHeight.windowHeight(model, w) : WorldHeight.resolve(requested, model);
            });
        }
        show();
    }

    /**
     * Shows the wait screen once; it asks {@link #waitText} for its text every second itself. It used to be replaced
     * by a new screen every second to update the count, and each replacement flashed the background and the button.
     */
    private static void show() {
        Minecraft mc = Minecraft.getInstance();
        CreateWorldScreen back = pending;
        shown = new WaitScreen(Component.translatable("orbisterrarum.spawnwait.title"), SpawnGate::waitText, () -> {
            pending = null;
            shown = null;
            fit = null;
            mc.gui.setScreen(back);
        });
        mc.gui.setScreen(shown);
    }

    private static Component waitText() {
        WorldModel m = modelForScreen(pending);
        OsmRegionManager regions = m == null ? null : m.regions();
        int ready = regions == null ? 0 : regions.spawnRegionsReady();
        int total = regions == null ? 0 : regions.spawnRegionsTotal();
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        String place = m == null ? "" : m.cfg().customSpawn
                ? String.format(java.util.Locale.ROOT, "%.4f, %.4f", m.cfg().spawnLat, m.cfg().spawnLon)
                : String.format(java.util.Locale.ROOT, "%.4f, %.4f", m.cfg().originLat, m.cfg().originLon);
        return Component.translatable("orbisterrarum.spawnwait.text", place, ready, total, seconds)
                .append("\n\n")
                .append(Component.translatable(seconds > 90 ? "orbisterrarum.spawnwait.slow" : "orbisterrarum.spawnwait.hint"));
    }

    /** A title, a text that changes while it waits (asked once a second) and a Back button, laid out like AlertScreen. */
    static final class WaitScreen extends Screen {
        private final java.util.function.Supplier<Component> text;
        private final Runnable back;
        private List<net.minecraft.util.FormattedCharSequence> lines = List.of();
        private long nextText;

        WaitScreen(Component title, java.util.function.Supplier<Component> text, Runnable back) {
            super(title);
            this.text = text;
            this.back = back;
        }

        private void refresh() {
            lines = font.split(text.get(), width - 50);
            nextText = System.currentTimeMillis() + 1000;
        }

        private int top() {
            return Math.max(10, (height - (lines.size() * 9 + 20 + 32)) / 2);
        }

        @Override
        protected void init() {
            refresh();
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.back"), b -> back.run())
                    .bounds(width / 2 - 100, Math.min(height - 24, top() + 20 + lines.size() * 9 + 12), 200, 20).build());
        }

        @Override
        public void tick() {
            if (System.currentTimeMillis() < nextText) return;
            int before = lines.size();
            refresh();
            if (lines.size() != before) rebuildWidgets(); // the button follows the text, only when its line count changes
        }

        @Override
        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            int y = top();
            g.centeredText(font, title, width / 2, y, 0xFFFFFFFF);
            y += 20;
            for (net.minecraft.util.FormattedCharSequence l : lines) {
                g.centeredText(font, l, width / 2, y, 0xFFFFFFFF);
                y += 9;
            }
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        if (pending == null) return;
        CreateWorldScreen screen = pending;
        if (awaitingHeight != 0) {
            // The creation context is reloading with the height pack. Once the create screen is back and the
            // context carries the height, put the world's settings back on the generator and create.
            boolean back = mc.gui.screen() == screen;
            if (back && contextHeight(screen) == awaitingHeight && !structuresPending(screen)) {
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
            // With a height to apply, the wait screen stays up into the data pack reload (which shows its own screen
            // and comes back to the create screen): switching to the create screen first flashed it for a frame.
            boolean packs = heightPending(screen) || structuresPending(screen);
            if (!packs) mc.gui.setScreen(screen);
            if (packs) {
                int height = contextHeight(screen);
                if (heightPending(screen)) {
                    try {
                        height = fit.join();
                    } catch (RuntimeException e) {
                        System.err.println("[orbis] World height fit failed: " + e);
                        height = OrbisConfig.DIMENSION_HEIGHT;
                    }
                }
                applyHeight(screen, height);
                return;
            }
            pending = null;
            fit = null;
            ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
            return;
        }
        ++ticks;
    }

    /** Stores the resolved height in the world's settings on the generator (they are saved with the world). */
    private static void applySettings(CreateWorldScreen screen, int height) {
        RealWorldChunkGenerator rw = generatorForScreen(screen);
        WorldSettings s = rw != null && rw.settings().isPresent() ? rw.settings().get().copy() : OrbisMod.defaultWorldSettings();
        // A fitted world squeezes only above the mountains it was fitted to (see WorldHeight.FITTED_SOFT_CEILING).
        if (s.worldHeight <= 0 && height < OrbisConfig.DIMENSION_HEIGHT) s.softCeilingBlocks = WorldHeight.FITTED_SOFT_CEILING;
        WorldHeight.Window w = window;
        if (w != null && s.worldHeight <= 0) {
            s.seaLevelY = w.seaLevelY();
            s.verticalMode = "compress"; // 1:1 throughout: no regional lowering, only the squeezes at floor and ceiling
            s.softCeilingBlocks = WorldHeight.WINDOW_SOFT_CEILING;
        }
        window = null;
        WorldHeight.Uniform u = uniform;
        if (u != null) {
            s.heightSquash = u.squash();
            s.verticalMode = "compress"; // no regional lowering: the one factor, and the short squeeze at the ceiling
            s.softCeilingBlocks = WorldHeight.WINDOW_SOFT_CEILING;
        }
        uniform = null;
        s.worldHeight = height;
        screen.getUiState().updateDimensions((registries, dimensions) -> dimensions.replaceOverworldGenerator(registries,
                new RealWorldChunkGenerator(dimensions.overworld().getBiomeSource(), Optional.of(s))));
    }

    /** Gives the world its height: the data pack into the new world's pack folder, then a context reload. */
    private static void applyHeight(CreateWorldScreen screen, int height) {
        CreateWorldScreenInvoker inv = (CreateWorldScreenInvoker) screen;
        if (height == contextHeight(screen) && !structuresPending(screen)) {
            // Nothing to override (full height, or the packs are already in place): only the settings change.
            pending = null;
            fit = null;
            Minecraft.getInstance().gui.setScreen(screen);
            applySettings(screen, height);
            inv.orbis$invokeOnCreate();
            return;
        }
        try {
            Path temp = inv.orbis$tempDataPackDir();
            WorldHeight.writePack(temp, height);
            double share = OrbisMod.config().undergroundStructureShare;
            com.berg.orbis.worldgen.StructureDensity.writePack(temp, share);
            Pair<Path, PackRepository> selection = inv.orbis$dataPackSelection(screen.getUiState().getSettings().dataConfiguration());
            PackRepository repository = selection.getSecond();
            repository.reload();
            List<String> ids = new ArrayList<>(repository.getSelectedIds());
            String id = "file/" + WorldHeight.PACK_NAME;
            if (!ids.contains(id)) ids.add(id);
            String structures = "file/" + com.berg.orbis.worldgen.StructureDensity.PACK_NAME;
            if (share < 1 && !ids.contains(structures)) ids.add(structures);
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
            Minecraft.getInstance().gui.setScreen(screen);
            applySettings(screen, OrbisConfig.DIMENSION_HEIGHT);
            inv.orbis$invokeOnCreate();
        }
    }
}
