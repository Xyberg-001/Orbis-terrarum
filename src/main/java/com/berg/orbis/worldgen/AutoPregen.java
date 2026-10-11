package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.net.Geocoder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pre-generation chosen when the world was created (World tab: "Pre-generate when the world is created"). The
 * area travels with the world's settings: the selection drawn in the world preview if there is one, else the area
 * field (a place name, a radius in km around the location, or empty for the city or municipality the location is
 * in). It starts a few seconds after the world opens, resumes by itself whenever the world opens again, and is
 * marked done when it finishes; stopped by hand ({@code /orbis pregen stop}) it waits for {@code /orbis pregen
 * resume}. The state lives in {@code <world>/orbis-pregen/auto-state.txt} (done / paused).
 */
public final class AutoPregen {
    private static final long START_DELAY_MS = 5_000;
    private static long startAt;
    private static boolean checked;
    private static PregenTask started;
    private static boolean looking;

    private AutoPregen() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            checked = false;
            started = null;
            looking = false;
            startAt = System.currentTimeMillis() + START_DELAY_MS;
        });
        ServerTickEvents.END_SERVER_TICK.register(AutoPregen::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!checked && System.currentTimeMillis() >= startAt) {
            checked = true;
            begin(server, false);
        }
        PregenTask t = started;
        if (t != null && PregenTask.active() != t && !OrbisMod.stopping()) {
            started = null;
            if (PregenTask.lastFinished() == t) {
                writeState(server, "done");
                say(server, "Pre-generation of the world's area is complete.");
            } else {
                writeState(server, "paused");
                say(server, "Automatic pre-generation paused; /orbis pregen resume carries on from where it stopped.");
            }
        }
    }

    /** Whether the world's own automatic pre-generation was stopped by hand and waits to be resumed. */
    public static boolean paused(MinecraftServer server) {
        OrbisConfig cfg = cfg();
        return cfg != null && cfg.pregenOnCreate && "paused".equals(readState(server)) && !PregenTask.isRunning();
    }

    /** /orbis pregen resume: carry on with the world's own area after a stop. */
    public static Component resume(MinecraftServer server) {
        OrbisConfig cfg = cfg();
        if (cfg == null || !cfg.pregenOnCreate) {
            return Component.literal("This world has no area to pre-generate: it was created without \"Pre-generate when the world is created\".");
        }
        if (PregenTask.isRunning()) return Component.literal("A pre-generation is already running; /orbis pregen status shows it.");
        writeState(server, null);
        return begin(server, true);
    }

    private static OrbisConfig cfg() {
        WorldModel m = OrbisMod.model();
        return m == null ? null : m.cfg();
    }

    private static Component begin(MinecraftServer server, boolean asked) {
        WorldModel model = OrbisMod.model();
        ServerLevel level = server.overworld();
        if (model == null || !(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return Component.literal("Not an Orbis Terrarum world.");
        OrbisConfig cfg = model.cfg();
        if (!cfg.pregenOnCreate) return Component.literal("This world has no area to pre-generate.");
        String state = readState(server);
        if ("done".equals(state) && !asked) return Component.empty();
        if ("paused".equals(state)) {
            System.out.println("[orbis] Automatic pre-generation is paused (stopped by hand); /orbis pregen resume carries on");
            return Component.empty();
        }
        if (PregenTask.isRunning() || looking) return Component.literal("A pre-generation is already running.");

        List<OrbisConfig.PregenShape> shapes = cfg.pregenShapes == null ? List.of() : cfg.pregenShapes;
        if (!shapes.isEmpty()) {
            return launch(server, PregenTask.startSelection(level, ChunkSelection.ofSettings(shapes, model.mapper()), cfg.pregenSelectionSkipsSea),
                    "the selection drawn in the world preview");
        }
        String q = cfg.pregenArea == null ? "" : cfg.pregenArea.trim();
        if (q.isEmpty()) {
            // Nothing was selected on the world generator map.
            System.out.println("[orbis] Automatic pre-generation is on but nothing was selected; nothing to do");
            return Component.literal("Nothing was selected to pre-generate when this world was created. Select an area on the world map and press Generate.");
        }
        Double km = km(q);
        if (km != null) {
            // A circle of real kilometres round the location, as a selection (so it resumes like one).
            int r = (int) Math.round(km * 1000 / cfg.metersPerBlock);
            int[] xz = new int[192];
            for (int i = 0; i < 96; i++) {
                double th = 2 * Math.PI * i / 96;
                xz[2 * i] = (int) Math.round(r * Math.cos(th));
                xz[2 * i + 1] = (int) Math.round(r * Math.sin(th));
            }
            return launch(server, PregenTask.startSelection(level, ChunkSelection.of(List.of(new ChunkSelection.Shape(false, xz)))),
                    String.format(Locale.ROOT, "%s km round the location", q.replaceAll("(?i)\\s*km", "")));
        }
        looking = true;
        var lookup = Geocoder.lookupArea(q);
        String what = q;
        System.out.println("[orbis] Automatic pre-generation: looking up the outline of " + what);
        lookup.whenComplete((outline, error) -> server.execute(() -> {
            looking = false;
            if (error != null || outline == null) {
                String why = error == null ? "no result" : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                say(server, "Automatic pre-generation could not find the outline of " + what + " (" + why + "); it tries again when the world opens next, or /orbis pregen resume.");
                return;
            }
            if (PregenTask.isRunning()) {
                say(server, "Automatic pre-generation waits: another pre-generation is running (/orbis pregen resume once it is done).");
                return;
            }
            AreaOutline main = outline.largestOnly();
            launch(server, PregenTask.startArea(level, main, true), main.name().split(",")[0]);
        }));
        return Component.literal("Looking up the outline of " + what + "...");
    }

    /** Called right after a start (nothing else was running, see begin), so the active task is ours if there is one. */
    private static Component launch(MinecraftServer server, Component message, String what) {
        started = PregenTask.active();
        System.out.println("[orbis] Automatic pre-generation of " + what + ": " + message.getString());
        if (started != null) say(server, "Automatic pre-generation of " + what + " (chosen when the world was created):");
        server.getPlayerList().broadcastSystemMessage(message, false);
        return message;
    }

    private static void say(MinecraftServer server, String text) {
        System.out.println("[orbis] " + text);
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    private static Double km(String q) {
        String s = q.toLowerCase(Locale.ROOT).replace("km", "").trim();
        try {
            double v = Double.parseDouble(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path stateFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen").resolve("auto-state.txt");
    }

    private static String readState(MinecraftServer server) {
        try {
            Path f = stateFile(server);
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeState(MinecraftServer server, String state) {
        try {
            Path f = stateFile(server);
            if (state == null) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, state + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not write the automatic pre-generation state: " + e);
        }
    }
}
