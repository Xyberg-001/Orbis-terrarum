package com.berg.orbis.osm.extract;

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
import java.util.Map;

/**
 * Geofabrik's list of downloadable OpenStreetMap regions (download.geofabrik.de, {@code index-v1.json}: 554 regions
 * with their outlines, 3.8 MB), kept on disk and refreshed once a month. Answers which region's file holds a whole
 * area: the smallest whose outline contains it (Norway for Bergen; Nordrhein-Westfalen, not all of Germany, for
 * Düsseldorf). An area across a border has no such region short of a continent: then there is no answer.
 */
public final class Geofabrik {

    public static final String INDEX_URL = "https://download.geofabrik.de/index-v1.json";
    static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; map data for a new world)");
    private static final long REFRESH_MS = 30L * 24 * 3600 * 1000;

    /** One region: its file, and its outline as polygons of rings of [lon, lat] (the first ring outer, then holes). */
    public record Region(String id, String name, String url, double areaDeg2, List<List<double[][]>> polygons) {
        boolean contains(double lat, double lon) {
            for (List<double[][]> polygon : polygons) {
                if (polygon.isEmpty() || !inRing(polygon.get(0), lon, lat)) continue;
                boolean inHole = false;
                for (int i = 1; i < polygon.size() && !inHole; i++) inHole = inRing(polygon.get(i), lon, lat);
                if (!inHole) return true;
            }
            return false;
        }
    }

    private static volatile List<Region> regions;

    private Geofabrik() {
    }

    /** The regions, from the copy on disk (fetched again when a month old; an old copy serves when that fails). */
    public static synchronized List<Region> regions(Path cacheDir) throws IOException, InterruptedException {
        if (regions != null) return regions;
        Path file = cacheDir.resolve("geofabrik-index.json");
        boolean fresh = Files.isRegularFile(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < REFRESH_MS;
        if (!fresh) {
            try {
                OrbisHttp.Response r = OrbisHttp.get(INDEX_URL, HEADERS, 60);
                if (r.status() == 200) {
                    Files.createDirectories(cacheDir);
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(tmp, r.body());
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (!Files.isRegularFile(file)) {
                    throw new IOException("Geofabrik's index answered HTTP " + r.status());
                }
            } catch (IOException e) {
                if (!Files.isRegularFile(file)) throw e;
            }
        }
        regions = parse(Files.readString(file, StandardCharsets.UTF_8));
        return regions;
    }

    static List<Region> parse(String json) {
        List<Region> out = new ArrayList<>();
        for (JsonElement fe : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("features")) {
            JsonObject f = fe.getAsJsonObject();
            JsonObject p = f.getAsJsonObject("properties");
            JsonObject urls = p.getAsJsonObject("urls");
            if (urls == null || !urls.has("pbf") || f.get("geometry") == null || f.get("geometry").isJsonNull()) continue;
            JsonObject g = f.getAsJsonObject("geometry");
            List<List<double[][]>> polygons = new ArrayList<>();
            JsonArray coords = g.getAsJsonArray("coordinates");
            if ("Polygon".equals(g.get("type").getAsString())) {
                polygons.add(rings(coords));
            } else {
                for (JsonElement poly : coords) polygons.add(rings(poly.getAsJsonArray()));
            }
            double area = 0;
            for (List<double[][]> polygon : polygons) if (!polygon.isEmpty()) area += Math.abs(shoelace(polygon.get(0)));
            out.add(new Region(p.get("id").getAsString(), p.get("name").getAsString(), urls.get("pbf").getAsString(), area, polygons));
        }
        return out;
    }

    private static List<double[][]> rings(JsonArray polygon) {
        List<double[][]> rings = new ArrayList<>();
        for (JsonElement re : polygon) {
            JsonArray ring = re.getAsJsonArray();
            double[][] pts = new double[ring.size()][];
            for (int i = 0; i < pts.length; i++) {
                JsonArray c = ring.get(i).getAsJsonArray();
                pts[i] = new double[]{c.get(0).getAsDouble(), c.get(1).getAsDouble()};
            }
            rings.add(pts);
        }
        return rings;
    }

    private static double shoelace(double[][] ring) {
        double s = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) s += (ring[j][0] - ring[i][0]) * (ring[j][1] + ring[i][1]);
        return s / 2;
    }

    private static boolean inRing(double[][] ring, double x, double y) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if ((ring[i][1] > y) != (ring[j][1] > y)
                    && x < (ring[j][0] - ring[i][0]) * (y - ring[i][1]) / (ring[j][1] - ring[i][1]) + ring[i][0]) in = !in;
        }
        return in;
    }

    /**
     * The smallest region whose outline holds the whole box {south, west, north, east} (its corners, the middles of
     * its sides and its centre), or null when none does (an area across a border).
     */
    public static Region smallestContaining(List<Region> all, double[] box) {
        double s = box[0], w = box[1], n = box[2], e = box[3], ml = (s + n) / 2, mo = (w + e) / 2;
        double[][] pts = {{s, w}, {s, e}, {n, w}, {n, e}, {s, mo}, {n, mo}, {ml, w}, {ml, e}, {ml, mo}};
        Region best = null;
        for (Region r : all) {
            if (best != null && r.areaDeg2() >= best.areaDeg2()) continue;
            boolean all9 = true;
            for (double[] p : pts) {
                if (!r.contains(p[0], p[1])) {
                    all9 = false;
                    break;
                }
            }
            if (all9) best = r;
        }
        return best;
    }
}
