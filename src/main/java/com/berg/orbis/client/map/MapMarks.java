package com.berg.orbis.client.map;

import com.berg.orbis.OrbisMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places marked on the world map, kept per world (per singleplayer save, per server address) in
 * {@code config/orbisterrarum/map-marks.json} on this computer.
 */
public final class MapMarks {
    public record Mark(String name, double lat, double lon) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, List<Mark>> all;

    private MapMarks() {}

    private static Path file() {
        return OrbisMod.configDir().resolve("map-marks.json");
    }

    /** "sp:<save name>" or "mp:<server address>": the world the player is in now. */
    public static String worldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return mc.getCurrentServer() != null ? "mp:" + mc.getCurrentServer().ip : "mp:?";
    }

    private static synchronized Map<String, List<Mark>> load() {
        if (all != null) return all;
        all = new LinkedHashMap<>();
        try {
            if (Files.exists(file())) {
                Map<String, List<Mark>> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, List<Mark>>>() {}.getType());
                if (m != null) all.putAll(m);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not read map marks: " + e);
        }
        return all;
    }

    public static synchronized List<Mark> get(String world) {
        return new ArrayList<>(load().getOrDefault(world, List.of()));
    }

    public static synchronized void add(String world, Mark mark) {
        load().computeIfAbsent(world, k -> new ArrayList<>()).add(mark);
        save();
    }

    public static synchronized void remove(String world, Mark mark) {
        List<Mark> list = load().get(world);
        if (list != null && list.remove(mark)) save();
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save map marks: " + e);
        }
    }
}
