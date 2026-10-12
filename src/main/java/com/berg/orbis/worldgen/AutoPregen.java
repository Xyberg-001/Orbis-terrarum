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
 * in). A few seconds after the world first opens it becomes a task of the world map's pre-generation list
 * ({@link PregenTasks}), which runs, pauses and resumes it from then on. {@code <world>/orbis-pregen/auto-state.txt} says it was
 * handed over ("handed"; "done" and "paused" are from 1.1.0, when this class ran it: a paused one is listed as paused).
 */
public final class AutoPregen {
    private static final long START_DELAY_MS = 5_000;
    private static long startAt;
    private static boolean checked;
    private static boolean looking;

    private AutoPregen() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            checked = false;
            looking = false;
            startAt = System.currentTimeMillis() + START_DELAY_MS;
        });
        ServerTickEvents.END_SERVER_TICK.register(AutoPregen::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!checked && System.currentTimeMillis() >= startAt) {
            checked = true;
            begin(server);
        }
    }

    private static void begin(MinecraftServer server) {
        WorldModel model = OrbisMod.model();
        ServerLevel level = server.overworld();
        if (model == null || !(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
        OrbisConfig cfg = model.cfg();
        if (!cfg.pregenOnCreate || looking) return;
        String state = readState(server);
        if ("done".equals(state) || "handed".equals(state)) return;
        boolean paused = "paused".equals(state); // stopped by hand in 1.1.0: listed, waiting for Resume

        List<OrbisConfig.PregenShape> shapes = cfg.pregenShapes == null ? List.of() : cfg.pregenShapes;
        if (!shapes.isEmpty()) {
            launch(server, PregenTask.startSelection(level, ChunkSelection.ofSettings(shapes, model.mapper()), cfg.pregenSelectionSkipsSea,
                    PregenTasks.AUTO, paused), "the selection drawn in the world preview");
            return;
        }
        String q = cfg.pregenArea == null ? "" : cfg.pregenArea.trim();
        if (q.isEmpty()) {
            // Nothing was selected on the world generator map.
            System.out.println("[orbis] Automatic pre-generation is on but nothing was selected; nothing to do");
            return;
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
            launch(server, PregenTask.startSelection(level, ChunkSelection.of(List.of(new ChunkSelection.Shape(false, xz))), null, PregenTasks.AUTO, paused),
                    String.format(Locale.ROOT, "%s km round the location", q.replaceAll("(?i)\\s*km", "")));
            return;
        }
        looking = true;
        var lookup = Geocoder.lookupArea(q);
        String what = q;
        System.out.println("[orbis] Automatic pre-generation: looking up the outline of " + what);
        lookup.whenComplete((outline, error) -> server.execute(() -> {
            looking = false;
            if (error != null || outline == null) {
                String why = error == null ? "no result" : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                say(server, "Automatic pre-generation could not find the outline of " + what + " (" + why + "); it tries again when the world opens next.");
                return;
            }
            AreaOutline main = outline.largestOnly();
            launch(server, PregenTask.startArea(level, main, true, PregenTasks.AUTO, paused), main.name().split(",")[0]);
        }));
    }

    /** The area is on the world map's list now (running, queued or paused): this class is done with it. */
    private static void launch(MinecraftServer server, Component message, String what) {
        writeState(server, "handed");
        say(server, "Automatic pre-generation of " + what + " (chosen when the world was created):");
        server.getPlayerList().broadcastSystemMessage(message, false);
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
