package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Austria's snow depth at 1 km: GeoSphere Austria's SNOWGRID (the snow model run every day on INCA's observed weather;
 * CC BY 4.0), posted about two days late. Read from GeoSphere's data hub in tiles of a fifth of a degree (some 400
 * cells, each a point with its depth), kept on disk for the day; a place takes its nearest cell. The newest day is
 * looked up again every three hours.
 */
public final class AustriaSnow {
    public static final String ATTRIBUTION = "SNOWGRID snow depth (GeoSphere Austria, CC BY 4.0)";

    private static final String META = "https://dataset.api.hub.geosphere.at/v1/timeseries/historical/snowgrid_cl-v2-1d-1km/metadata";
    private static final String GRID = "https://dataset.api.hub.geosphere.at/v1/grid/historical/snowgrid_cl-v2-1d-1km?parameters=snow_depth"
            + "&start=%1$sT00:00&end=%1$sT00:00&bbox=%2$.2f,%3$.2f,%4$.2f,%5$.2f&output_format=geojson";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final double TILE = 0.2, BUCKET = 0.01;
    private static final long DAY_CHECK_MS = 3 * 3600_000L, RETRY_MS = 15 * 60_000L;

    private static final Map<Path, AustriaSnow> SHARED = new HashMap<>();

    /** A tile's cells, hashed by 0.01 degree buckets: {lat, lon, depth m} each. */
    private record Tile(Map<Long, java.util.List<double[]>> buckets) {
    }

    private final Path cacheDir;
    private volatile String day;
    private volatile long dayCheckedAt, failedUntil;
    private final Map<String, CompletableFuture<Tile>> tiles = new ConcurrentHashMap<>();

    private AustriaSnow(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static synchronized AustriaSnow shared(Path cacheDir) {
        return SHARED.computeIfAbsent(cacheDir, AustriaSnow::new);
    }

    /** Whether a place lies in SNOWGRID's box (Austria; its cells outside Austria have no depth). */
    public static boolean covers(double lat, double lon) {
        return lat > 46.16 && lat < 49.18 && lon > 9.39 && lon < 17.38;
    }

    /** Snow depth in metres today at a place, NaN where SNOWGRID has none or it cannot be had in time. */
    public double depthM(double lat, double lon, long waitMs) {
        if (!covers(lat, lon) || System.currentTimeMillis() < failedUntil) return Double.NaN;
        String d = day(waitMs);
        if (d == null) return Double.NaN;
        int ty = (int) Math.floor(lat / TILE), tx = (int) Math.floor(lon / TILE);
        String key = d + "_" + ty + "_" + tx;
        CompletableFuture<Tile> f = tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> tile(d, ty, tx)));
        Tile t;
        try {
            t = waitMs <= 0 ? f.getNow(null) : f.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Double.NaN;
        }
        if (t == null) {
            if (f.isDone()) { // failed: asked again after a rest
                tiles.remove(key, f);
                failedUntil = System.currentTimeMillis() + RETRY_MS;
            }
            return Double.NaN;
        }
        // the nearest cell (1 km apart: within the 3 x 3 buckets of 0.01 degrees round the place)
        double best = Double.MAX_VALUE, depth = Double.NaN, kx = Math.cos(Math.toRadians(lat));
        long by = (long) Math.floor(lat / BUCKET), bx = (long) Math.floor(lon / BUCKET);
        for (long y = by - 1; y <= by + 1; y++) {
            for (long x = bx - 1; x <= bx + 1; x++) {
                var cells = t.buckets.get(y << 32 ^ (x & 0xffffffffL));
                if (cells == null) continue;
                for (double[] c : cells) {
                    double dy = c[0] - lat, dx = (c[1] - lon) * kx, dd = dy * dy + dx * dx;
                    if (dd < best) {
                        best = dd;
                        depth = c[2];
                    }
                }
            }
        }
        return depth;
    }

    /** The newest day posted (yyyy-mm-dd), looked up every three hours; null while unknown. */
    private String day(long waitMs) {
        long now = System.currentTimeMillis();
        if (day != null && now - dayCheckedAt < DAY_CHECK_MS) return day;
        synchronized (this) {
            if (day != null && now - dayCheckedAt < DAY_CHECK_MS) return day;
            dayCheckedAt = now;
            try {
                OrbisHttp.Response r = OrbisHttp.get(META, HEADERS, (int) Math.max(5, Math.min(30, waitMs / 1000)));
                if (r.status() != 200) throw new java.io.IOException("HTTP " + r.status());
                String end = JsonParser.parseString(r.text()).getAsJsonObject().get("end_time").getAsString();
                String newest = end.substring(0, 10);
                if (!newest.equals(day)) tiles.clear();
                day = newest;
            } catch (Exception e) {
                if (day == null) {
                    failedUntil = now + RETRY_MS;
                    System.err.println("[orbis] Austria's snow depth (SNOWGRID) unavailable (" + e + "); the model's snow is used");
                }
            }
            return day;
        }
    }

    private Tile tile(String d, int ty, int tx) {
        Path file = cacheDir.resolve(d + "_" + ty + "_" + tx + ".json");
        try {
            String text;
            if (Files.exists(file)) {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } else {
                double south = ty * TILE, west = tx * TILE;
                OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, GRID, d, south, west, south + TILE, west + TILE), HEADERS, 60);
                if (r.status() != 200) return null;
                text = r.text();
                Files.createDirectories(cacheDir);
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                removeOlderThan(d);
            }
            Map<Long, java.util.List<double[]>> buckets = new HashMap<>();
            JsonArray features = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("features");
            for (JsonElement e : features) {
                JsonObject f = e.getAsJsonObject();
                JsonArray xy = f.getAsJsonObject("geometry").getAsJsonArray("coordinates");
                JsonArray v = f.getAsJsonObject("properties").getAsJsonObject("parameters").getAsJsonObject("snow_depth").getAsJsonArray("data");
                if (v.isEmpty() || v.get(0).isJsonNull()) continue; // outside Austria
                double lon = xy.get(0).getAsDouble(), lat = xy.get(1).getAsDouble();
                long key = (long) Math.floor(lat / BUCKET) << 32 ^ ((long) Math.floor(lon / BUCKET) & 0xffffffffL);
                buckets.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new double[]{lat, lon, Math.max(0, v.get(0).getAsDouble())});
            }
            return new Tile(buckets);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tiles of older days go. */
    private void removeOlderThan(String d) {
        try (var files = Files.list(cacheDir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".json") && n.compareTo(d) < 0) Files.deleteIfExists(p);
            }
        } catch (Exception ignored) {
        }
    }
}
