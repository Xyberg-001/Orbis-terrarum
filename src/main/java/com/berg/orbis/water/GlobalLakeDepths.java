package com.berg.orbis.water;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Greatest depths of the world's lakes over 10 ha from GLOBathy (Khazaei et al. 2022, CC0: an estimate for each
 * of HydroLAKES' 1.4 million lakes, checked against 1,503 surveyed ones), for the area a player imported with
 * {@code tools/lake_depths.py} into {@code config/orbisterrarum/lake-depths/}. Each lake is known by its outlet
 * (HydroLAKES' pour point, on its shore) and its area, which is how an OpenStreetMap lake finds its entry.
 */
public final class GlobalLakeDepths {

    /** One lake: outlet position, area (km²), greatest depth (m), name. */
    public record Entry(double lat, double lon, double areaKm2, double maxDepthM, String name) {}

    private static final double CELL = 0.1;
    private final Map<Long, List<Entry>> cells = new HashMap<>();
    private int count;

    private GlobalLakeDepths() {
    }

    /** The lakes imported into a folder, or null when there are none. */
    public static GlobalLakeDepths open(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        GlobalLakeDepths g = new GlobalLakeDepths();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonArray a = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement e : a) {
                    JsonObject o = e.getAsJsonObject();
                    Entry en = new Entry(o.get("lat").getAsDouble(), o.get("lon").getAsDouble(), o.get("area_km2").getAsDouble(),
                            o.get("dmax_m").getAsDouble(), o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "");
                    g.cells.computeIfAbsent(key(en.lat(), en.lon()), k -> new ArrayList<>()).add(en);
                    g.count++;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Lake depths in " + dir + " unreadable: " + e);
        }
        return g.count == 0 ? null : g;
    }

    public int size() {
        return count;
    }

    /** The lakes whose outlets lie in a box. */
    public List<Entry> near(double south, double west, double north, double east) {
        List<Entry> out = new ArrayList<>();
        for (long y = (long) Math.floor(south / CELL); y <= (long) Math.floor(north / CELL); y++) {
            for (long x = (long) Math.floor(west / CELL); x <= (long) Math.floor(east / CELL); x++) {
                List<Entry> l = cells.get(y * 100_000L + x);
                if (l == null) continue;
                for (Entry e : l) if (e.lat() >= south && e.lat() <= north && e.lon() >= west && e.lon() <= east) out.add(e);
            }
        }
        return out;
    }

    private static long key(double lat, double lon) {
        return (long) Math.floor(lat / CELL) * 100_000L + (long) Math.floor(lon / CELL);
    }
}
