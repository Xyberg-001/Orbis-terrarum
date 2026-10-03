package com.berg.orbis.water;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lake depth surveys outside Norway, as depth contours and sounded points (Finland's SYKE, Minnesota's DNR,
 * Ontario's MNRF): turned into the soundings NVE's lakes give, so an OSM lake with enough of them inside gets the
 * same surveyed bed. Asked per 0.05 degree tile (WFS 2.0 or ArcGIS GeoJSON), kept on disk one line a contour.
 * A lake bigger than 400 tiles keeps its estimated bowl.
 */
public final class LakeSurveys {

    private static final double TILE = 0.05;
    private static final int PAGES = 20, MAX_TILES = 400;
    private final List<OrbisConfig.LakeSurveySource> sources = new ArrayList<>();
    private final Path cacheDir;
    private final Map<String, CompletableFuture<List<double[]>>> tiles = new ConcurrentHashMap<>();
    private final Map<String, Long> failingUntil = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Orbis-lake-surveys");
        t.setDaemon(true);
        return t;
    });

    public LakeSurveys(OrbisConfig cfg, Path cacheDir) {
        this.cacheDir = cacheDir;
        for (OrbisConfig.LakeSurveySource s : cfg.lakeSurveySources) {
            if (s != null && s.enabled && s.urlTemplate != null && !s.urlTemplate.isBlank()) sources.add(s);
        }
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    public boolean covers(double south, double west, double north, double east) {
        for (OrbisConfig.LakeSurveySource s : sources) {
            if (north < s.south || south > s.north || east < s.west || west > s.east) continue;
            if (com.berg.orbis.config.DataSources.covers(s.name, south, west, north, east)) return true;
        }
        return false;
    }

    /**
     * The soundings in a box: each contour's points about every 5 m, in order (the bed joins them into lines), and
     * the sounded points. Empty for a box of more than 400 tiles.
     */
    public List<NveLakes.Sounding> soundings(double south, double west, double north, double east) {
        List<CompletableFuture<List<double[]>>> jobs = new ArrayList<>();
        for (OrbisConfig.LakeSurveySource src : sources) {
            if (north < src.south || south > src.north || east < src.west || west > src.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(src.name, south, west, north, east)) continue;
            long ty0 = (long) Math.floor(south / TILE), ty1 = (long) Math.floor(north / TILE), tx0 = (long) Math.floor(west / TILE), tx1 = (long) Math.floor(east / TILE);
            if ((ty1 - ty0 + 1) * (tx1 - tx0 + 1) > MAX_TILES) continue;
            for (long ty = ty0; ty <= ty1; ty++) for (long tx = tx0; tx <= tx1; tx++) jobs.add(tile(src, ty, tx));
        }
        List<NveLakes.Sounding> out = new ArrayList<>();
        for (CompletableFuture<List<double[]>> f : jobs) {
            List<double[]> lines;
            try {
                lines = f.join();
            } catch (RuntimeException e) {
                continue;
            }
            for (double[] line : lines) densify(line, out);
        }
        return out;
    }

    /** {depth, lat, lon, lat, lon, ...}: points every 5 m along it, all at its depth. */
    private static void densify(double[] line, List<NveLakes.Sounding> out) {
        double depth = line[0];
        if (line.length == 3) {
            out.add(new NveLakes.Sounding(line[1], line[2], depth));
            return;
        }
        for (int i = 1; i + 3 < line.length; i += 2) {
            double la = line[i], lo = line[i + 1], lb = line[i + 2], lob = line[i + 3];
            double dy = (lb - la) * 111_320.0, dx = (lob - lo) * 111_320.0 * Math.cos(Math.toRadians(la));
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 5.0));
            for (int k = 0; k < steps; k++) {
                double t = (double) k / steps;
                out.add(new NveLakes.Sounding(la + (lb - la) * t, lo + (lob - lo) * t, depth));
            }
        }
        out.add(new NveLakes.Sounding(line[line.length - 2], line[line.length - 1], depth));
    }

    private CompletableFuture<List<double[]>> tile(OrbisConfig.LakeSurveySource src, long ty, long tx) {
        String key = src.name + "/" + ty + "_" + tx;
        return tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Path file = cacheDir.resolve(src.name.replaceAll("[^A-Za-z0-9_-]", "_")).resolve(ty + "_" + tx + ".txt");
            try {
                if (Files.exists(file)) return read(file);
                Long until = failingUntil.get(src.name);
                if (until != null && System.currentTimeMillis() < until) {
                    tiles.remove(k);
                    return List.<double[]>of();
                }
                List<double[]> list = download(src, ty * TILE, tx * TILE, (ty + 1) * TILE, (tx + 1) * TILE);
                write(file, list);
                return list;
            } catch (IOException | InterruptedException | RuntimeException e) {
                tiles.remove(k);
                if (failingUntil.put(src.name, System.currentTimeMillis() + 3 * 60_000L) == null) {
                    System.err.println("[orbis] Lake survey '" + src.name + "' unavailable (" + e + "); estimated lake beds for 3 minutes");
                }
                return List.<double[]>of();
            }
        }, POOL));
    }

    private static List<double[]> download(OrbisConfig.LakeSurveySource src, double s, double w, double n, double e) throws IOException, InterruptedException {
        List<double[]> out = new ArrayList<>();
        for (int page = 0; page < PAGES; page++) {
            String url = src.urlTemplate.replace("{south}", fmt(s)).replace("{west}", fmt(w)).replace("{north}", fmt(n)).replace("{east}", fmt(e))
                    .replace("{count}", String.valueOf(src.pageSize)).replace("{start}", String.valueOf(page * src.pageSize));
            OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0", "Accept-Encoding", "identity"), 90);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            JsonElement root = JsonParser.parseString(r.text());
            if (!root.isJsonObject() || !root.getAsJsonObject().has("features")) throw new IOException("not a feature collection");
            JsonArray feats = root.getAsJsonObject().getAsJsonArray("features");
            for (JsonElement fe : feats) {
                JsonObject f = fe.getAsJsonObject();
                JsonObject props = f.has("properties") && f.get("properties").isJsonObject() ? f.getAsJsonObject("properties") : new JsonObject();
                double depth = Math.abs(number(props, src.depthField)) * src.depthScale;
                if (Double.isNaN(depth) || depth > 2000) continue;
                JsonElement g = f.get("geometry");
                if (g == null || !g.isJsonObject()) continue;
                JsonObject go = g.getAsJsonObject();
                String type = go.has("type") ? go.get("type").getAsString() : "";
                JsonArray c = go.getAsJsonArray("coordinates");
                if (c == null) continue;
                switch (type) {
                    case "LineString" -> out.add(line(depth, c));
                    case "MultiLineString" -> {
                        for (JsonElement part : c) out.add(line(depth, part.getAsJsonArray()));
                    }
                    case "Point" -> out.add(new double[]{depth, c.get(1).getAsDouble(), c.get(0).getAsDouble()});
                    case "MultiPoint" -> {
                        for (JsonElement p : c) out.add(new double[]{depth, p.getAsJsonArray().get(1).getAsDouble(), p.getAsJsonArray().get(0).getAsDouble()});
                    }
                    default -> { }
                }
            }
            if (feats.size() < src.pageSize || !src.urlTemplate.contains("{start}")) break;
        }
        return out;
    }

    private static double[] line(double depth, JsonArray pts) {
        double[] out = new double[1 + pts.size() * 2];
        out[0] = depth;
        for (int i = 0; i < pts.size(); i++) {
            JsonArray p = pts.get(i).getAsJsonArray();
            out[1 + i * 2] = p.get(1).getAsDouble();
            out[2 + i * 2] = p.get(0).getAsDouble();
        }
        return out;
    }

    private static double number(JsonObject props, String field) {
        if (field == null || field.isBlank() || !props.has(field)) return Double.NaN;
        JsonElement v = props.get(field);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return Double.NaN;
        try {
            return v.getAsDouble(); // numbers, and numbers in strings ("3")
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return Double.NaN;
        }
    }

    private static void write(Path file, List<double[]> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (double[] l : lines) {
            sb.append(String.format(Locale.ROOT, "%.2f", l[0]));
            for (int i = 1; i < l.length; i++) sb.append(' ').append(String.format(Locale.ROOT, "%.6f", l[i]));
            sb.append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<double[]> read(Path file) throws IOException {
        List<double[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split(" ");
            if (p.length < 3) continue;
            double[] v = new double[p.length];
            for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
            out.add(v);
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
