package com.berg.orbis.water;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Norway's surveyed lakes, from NVE's lake database (Innsjødatabase, Norwegian Water Resources and Energy
 * Directorate, open under NLOD): about 600 lakes sounded by echo sounder, each with its outline, its greatest
 * depth and depth contours (every 5 or 10 m), which give its real bed. Fetched from NVE's map service and kept in
 * {@code lake-survey-cache/}: the lake outlines per 0.1-degree tile, the contours per lake (a whole lake at once,
 * so every region crossing it builds the same bed).
 */
public final class NveLakes {

    /** A surveyed lake: NVE's number, name, greatest depth (m, NaN if not given) and outline (lat/lon rings). */
    public record Lake(long id, String name, double maxDepthM, List<double[][]> rings) {}

    /** A sounded point of a lake's bed: latitude, longitude, depth in metres. */
    public record Sounding(double lat, double lon, double depthM) {}

    private static final double TILE = 0.1;
    /** Mainland Norway (NVE has nothing outside). */
    private static final double SOUTH = 57.8, NORTH = 71.4, WEST = 4.0, EAST = 31.5;

    private final Path cacheDir;
    private final String serviceUrl;
    private final Map<Long, List<Lake>> tiles = new ConcurrentHashMap<>();
    private final Map<Long, List<Sounding>> soundings = new ConcurrentHashMap<>();
    private volatile long failingUntil;

    public NveLakes(Path cacheDir, String serviceUrl) {
        this.cacheDir = cacheDir;
        this.serviceUrl = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
    }

    public static boolean covers(double south, double west, double north, double east) {
        return north >= SOUTH && south <= NORTH && east >= WEST && west <= EAST;
    }

    /** The surveyed lakes whose outlines touch a box. */
    public List<Lake> lakesIn(double south, double west, double north, double east) {
        List<Lake> out = new ArrayList<>();
        if (!covers(south, west, north, east) || !com.berg.orbis.config.DataSources.covers("nve-lakes", south, west, north, east)) return out;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long ty = (long) Math.floor(south / TILE); ty <= (long) Math.floor(north / TILE); ty++) {
            for (long tx = (long) Math.floor(west / TILE); tx <= (long) Math.floor(east / TILE); tx++) {
                for (Lake l : tile(ty, tx)) if (seen.add(l.id())) out.add(l);
            }
        }
        return out;
    }

    /** A lake's soundings: its depth contours as points (densified to about every 5 m) and its sounded points. */
    public List<Sounding> soundings(long lakeId) {
        List<Sounding> s = soundings.get(lakeId);
        if (s != null) return s;
        Path file = cacheDir.resolve("lake_" + lakeId + ".json");
        List<Sounding> result = new ArrayList<>();
        try {
            JsonArray all = new JsonArray();
            if (Files.exists(file)) {
                all = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            } else {
                if (System.currentTimeMillis() < failingUntil) return result;
                for (int layer : new int[]{2, 1}) { // depth contours, then sounded points
                    for (JsonElement f : query(layer, "vatnlnr=" + lakeId, null, "dybde_m")) all.add(f);
                }
                Files.createDirectories(cacheDir);
                Files.writeString(file, all.toString(), StandardCharsets.UTF_8);
            }
            for (JsonElement e : all) {
                JsonObject f = e.getAsJsonObject();
                JsonElement d = f.getAsJsonObject("properties").get("dybde_m");
                if (d == null || d.isJsonNull() || f.get("geometry").isJsonNull()) continue;
                double depth = d.getAsDouble();
                JsonObject g = f.getAsJsonObject("geometry");
                String type = g.get("type").getAsString();
                JsonArray c = g.getAsJsonArray("coordinates");
                switch (type) {
                    case "Point" -> result.add(new Sounding(c.get(1).getAsDouble(), c.get(0).getAsDouble(), depth));
                    case "LineString" -> densify(c, depth, result);
                    case "MultiLineString" -> {
                        for (JsonElement line : c) densify(line.getAsJsonArray(), depth, result);
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            failed(e);
        }
        soundings.put(lakeId, result);
        return result;
    }

    private List<Lake> tile(long ty, long tx) {
        long key = ty * 10_000L + tx;
        List<Lake> t = tiles.get(key);
        if (t != null) return t;
        Path file = cacheDir.resolve("tile_" + ty + "_" + tx + ".json");
        List<Lake> lakes = new ArrayList<>();
        try {
            JsonArray features;
            if (Files.exists(file)) {
                features = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            } else {
                if (System.currentTimeMillis() < failingUntil) return lakes;
                String box = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", tx * TILE, ty * TILE, (tx + 1) * TILE, (ty + 1) * TILE);
                features = query(4, "1=1", box, "vatnlnr,innsjonavn,maksdyp_m");
                Files.createDirectories(cacheDir);
                Files.writeString(file, features.toString(), StandardCharsets.UTF_8);
            }
            for (JsonElement e : features) {
                JsonObject f = e.getAsJsonObject();
                JsonObject p = f.getAsJsonObject("properties");
                if (f.get("geometry").isJsonNull()) continue;
                List<double[][]> rings = new ArrayList<>();
                JsonObject g = f.getAsJsonObject("geometry");
                JsonArray c = g.getAsJsonArray("coordinates");
                if ("Polygon".equals(g.get("type").getAsString())) {
                    for (JsonElement ring : c) rings.add(ring(ring.getAsJsonArray()));
                } else if ("MultiPolygon".equals(g.get("type").getAsString())) {
                    for (JsonElement poly : c) for (JsonElement ring : poly.getAsJsonArray()) rings.add(ring(ring.getAsJsonArray()));
                }
                JsonElement max = p.get("maksdyp_m");
                lakes.add(new Lake(p.get("vatnlnr").getAsLong(), p.get("innsjonavn").isJsonNull() ? "" : p.get("innsjonavn").getAsString(),
                        max == null || max.isJsonNull() ? Double.NaN : max.getAsDouble(), rings));
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            failed(e);
            return lakes; // not cached: asked again next time
        }
        tiles.put(key, lakes);
        return lakes;
    }

    /** Every feature of a layer matching a filter (and a lat/lon box), as GeoJSON features, page by page. */
    private JsonArray query(int layer, String where, String box, String fields) throws IOException, InterruptedException {
        JsonArray out = new JsonArray();
        for (int offset = 0; offset < 200_000; ) {
            StringBuilder url = new StringBuilder(serviceUrl).append('/').append(layer).append("/query?where=")
                    .append(URLEncoder.encode(where, StandardCharsets.UTF_8)).append("&outFields=").append(fields)
                    .append("&outSR=4326&f=geojson&resultOffset=").append(offset);
            if (box != null) {
                url.append("&geometry=").append(box).append("&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects");
            }
            OrbisHttp.Response r = OrbisHttp.get(url.toString(), Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 60);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            JsonObject page = JsonParser.parseString(r.text()).getAsJsonObject();
            if (page.has("error")) throw new IOException(page.get("error").toString());
            JsonArray fs = page.getAsJsonArray("features");
            out.addAll(fs);
            boolean more = page.has("exceededTransferLimit") && page.get("exceededTransferLimit").getAsBoolean()
                    || (page.has("properties") && page.getAsJsonObject("properties").has("exceededTransferLimit")
                    && page.getAsJsonObject("properties").get("exceededTransferLimit").getAsBoolean());
            if (!more || fs.isEmpty()) break;
            offset += fs.size();
        }
        return out;
    }

    private static double[][] ring(JsonArray coords) {
        double[][] r = new double[coords.size()][];
        for (int i = 0; i < coords.size(); i++) {
            JsonArray p = coords.get(i).getAsJsonArray();
            r[i] = new double[]{p.get(1).getAsDouble(), p.get(0).getAsDouble()};
        }
        return r;
    }

    /** Points along a contour at about every 5 m, all at the contour's depth. */
    private static void densify(JsonArray line, double depth, List<Sounding> out) {
        double[] prev = null;
        for (JsonElement e : line) {
            JsonArray p = e.getAsJsonArray();
            double[] cur = {p.get(1).getAsDouble(), p.get(0).getAsDouble()};
            if (prev != null) {
                double dy = (cur[0] - prev[0]) * 111_320, dx = (cur[1] - prev[1]) * 111_320 * Math.cos(Math.toRadians(cur[0]));
                int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 5.0));
                for (int i = 1; i <= n; i++) {
                    double t = (double) i / n;
                    out.add(new Sounding(prev[0] + (cur[0] - prev[0]) * t, prev[1] + (cur[1] - prev[1]) * t, depth));
                }
            } else {
                out.add(new Sounding(cur[0], cur[1], depth));
            }
            prev = cur;
        }
    }

    private void failed(Exception e) {
        if (System.currentTimeMillis() >= failingUntil) {
            System.err.println("[orbis] NVE lake survey service unavailable (" + e + "); estimated lake depths for 5 minutes");
        }
        failingUntil = System.currentTimeMillis() + 5 * 60_000L;
    }

    /** Whether a point lies inside a lake's outline (even-odd over its rings). */
    public static boolean contains(Lake lake, double lat, double lon) {
        boolean in = false;
        for (double[][] ring : lake.rings()) {
            for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                if ((ring[i][0] > lat) != (ring[j][0] > lat)) {
                    double x = ring[i][1] + (lat - ring[i][0]) / (ring[j][0] - ring[i][0]) * (ring[j][1] - ring[i][1]);
                    if (lon < x) in = !in;
                }
            }
        }
        return in;
    }
}
