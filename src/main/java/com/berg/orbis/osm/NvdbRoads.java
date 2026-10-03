package com.berg.orbis.osm;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Surveyed road widths and lane layouts from NVDB, the Norwegian national road database (Statens vegvesen,
 * open under NLOD, no key). OpenStreetMap rarely carries a width, so the mod otherwise guesses one from the
 * road class; NVDB has the measured carriageway width of nearly every public road, and its road network
 * knows the lanes of each link. The matching is geometric: an OSM way is sampled along its length, and each
 * sample takes the width of the nearest NVDB segment that runs the same way within a few metres.
 *
 * Data comes per region from the NVDB API and is cached under config/orbisterrarum/nvdb-cache. Any failure
 * (offline, outside Norway, API down) means the guess stays, never a missing road.
 */
public final class NvdbRoads {

    private static final String BASE = "https://nvdbapiles.atlas.vegvesen.no";
    private static final int WIDTH_TYPE = 583;
    private static final int PROP_TOTAL_WIDTH = 5264, PROP_PAVED_WIDTH = 5555, PROP_CARRIAGEWAY_WIDTH = 5556;
    private static final double MATCH_METERS = 7.0;
    private static final double MATCH_BEARING_DEG = 35.0;

    /**
     * One road line: lat/lon points, carriageway width in metres (NaN if unknown), lane count (-1 if unknown), and
     * whether width and lanes count both directions of a road OSM may draw as two one-way carriageways (HPMS).
     */
    public record Segment(List<double[]> latLon, double widthM, int lanes, boolean bothWays) {
        public Segment(List<double[]> latLon, double widthM, int lanes) {
            this(latLon, widthM, lanes, false);
        }
    }

    /**
     * Threads for the pieces of one region's fetch (the widths, and the lanes of each quarter of the region), which
     * run at the same time: NVDB answers a query slowly (from one to fifty seconds for a region's lanes), so one
     * after another they were the slowest part of preparing a fresh region.
     */
    private static final java.util.concurrent.ExecutorService PARTS = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "Orbis-NVDB-part");
        t.setDaemon(true);
        return t;
    });

    private NvdbRoads() {
    }

    public static boolean inNorway(double south, double west, double north, double east) {
        return north > 57.5 && south < 71.5 && east > 4.0 && west < 31.5;
    }

    // ------------------------------------------------------------------ store

    public static final class Store {
        private final Path cacheDir;
        private volatile long failedUntil;

        public Store(Path cacheDir) {
            this.cacheDir = cacheDir;
        }

        /** All width and lane segments touching the box; empty on any failure. */
        public List<Segment> get(double south, double west, double north, double east) {
            if (!inNorway(south, west, north, east)) return List.of();
            Path file = cacheDir.resolve(String.format(Locale.ROOT, "%.5f_%.5f_%.5f_%.5f.json.gz", south, west, north, east));
            try {
                if (Files.exists(file)) return parseCached(readGzip(file));
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] NVDB cache " + file.getFileName() + " unreadable, refetching: " + e);
            }
            if (System.currentTimeMillis() < failedUntil) return List.of();
            try {
                long t0 = System.currentTimeMillis();
                // The widths and the lanes of the four quarters of the box, all at once. A lane link crossing a
                // quarter's edge comes back from both quarters and is kept once.
                java.util.concurrent.Future<List<Segment>> widthsF = PARTS.submit(() -> fetchWidths(south, west, north, east));
                double midLat = (south + north) / 2, midLon = (west + east) / 2;
                double[][] quarters = {{south, west, midLat, midLon}, {south, midLon, midLat, east}, {midLat, west, north, midLon}, {midLat, midLon, north, east}};
                List<java.util.concurrent.Future<List<Segment>>> laneF = new ArrayList<>();
                for (double[] q : quarters) laneF.add(PARTS.submit(() -> fetchLanes(q[0], q[1], q[2], q[3])));
                List<Segment> all = new ArrayList<>(join(widthsF));
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (java.util.concurrent.Future<List<Segment>> f : laneF) {
                    for (Segment seg : join(f)) {
                        StringBuilder key = new StringBuilder().append(seg.lanes());
                        for (double[] p : seg.latLon()) key.append(';').append(p[0]).append(',').append(p[1]);
                        if (seen.add(key.toString())) all.add(seg);
                    }
                }
                Files.createDirectories(cacheDir);
                writeGzip(file, serialize(all));
                System.out.println("[orbis] NVDB: " + describe(all) + " for " + String.format(Locale.ROOT, "%.3f,%.3f", south, west)
                        + " in " + (System.currentTimeMillis() - t0) / 1000 + " s");
                return all;
            } catch (IOException | InterruptedException | RuntimeException e) {
                failedUntil = System.currentTimeMillis() + 10 * 60_000L;
                System.err.println("[orbis] NVDB road widths unavailable (" + e.getMessage() + "); road classes decide widths for 10 minutes");
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return List.of();
            }
        }

        private static List<Segment> join(java.util.concurrent.Future<List<Segment>> f) throws IOException, InterruptedException {
            try {
                return f.get();
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable c = ex.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof InterruptedException ie) throw ie;
                if (c instanceof RuntimeException re) throw re;
                throw new IOException(c);
            }
        }

        private static List<Segment> fetchWidths(double s, double w, double n, double e) throws IOException, InterruptedException {
            List<Segment> out = new ArrayList<>();
            String url = BASE + "/vegobjekter/api/v4/vegobjekter/" + WIDTH_TYPE + "?kartutsnitt=" + box(s, w, n, e)
                    + "&srid=4326&inkluder=egenskaper,geometri&antall=1000";
            for (int page = 0; url != null && page < 20; page++) {
                JsonObject root = fetch(url);
                for (JsonElement el : root.getAsJsonArray("objekter")) {
                    JsonObject o = el.getAsJsonObject();
                    double carriageway = Double.NaN, paved = Double.NaN, total = Double.NaN;
                    if (o.has("egenskaper")) {
                        for (JsonElement pe : o.getAsJsonArray("egenskaper")) {
                            JsonObject p = pe.getAsJsonObject();
                            int id = p.has("id") ? p.get("id").getAsInt() : 0;
                            if (!p.has("verdi") || !p.get("verdi").isJsonPrimitive() || !p.get("verdi").getAsJsonPrimitive().isNumber()) continue;
                            double v = p.get("verdi").getAsDouble();
                            if (id == PROP_CARRIAGEWAY_WIDTH) carriageway = v;
                            else if (id == PROP_PAVED_WIDTH) paved = v;
                            else if (id == PROP_TOTAL_WIDTH) total = v;
                        }
                    }
                    double width = !Double.isNaN(carriageway) ? carriageway : !Double.isNaN(paved) ? paved : total;
                    if (Double.isNaN(width) || width < 2.0 || width > 60.0) continue;
                    for (List<double[]> line : wktLines(o)) out.add(new Segment(line, width, -1));
                }
                url = nextPage(root, url);
            }
            return out;
        }

        private static List<Segment> fetchLanes(double s, double w, double n, double e) throws IOException, InterruptedException {
            List<Segment> out = new ArrayList<>();
            String url = BASE + "/vegnett/api/v4/veglenkesekvenser/segmentert?kartutsnitt=" + box(s, w, n, e) + "&srid=4326&antall=1000";
            for (int page = 0; url != null && page < 20; page++) {
                JsonObject root = fetch(url);
                for (JsonElement el : root.getAsJsonArray("objekter")) {
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("feltoversikt") || !o.get("feltoversikt").isJsonArray()) continue;
                    int lanes = o.getAsJsonArray("feltoversikt").size();
                    if (lanes <= 0 || lanes > 12) continue;
                    for (List<double[]> line : wktLines(o)) out.add(new Segment(line, Double.NaN, lanes));
                }
                url = nextPage(root, url);
            }
            return out;
        }

        private static String box(double s, double w, double n, double e) {
            return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", w, s, e, n); // x/y order: lon,lat
        }

        /**
         * One page, gzip-compressed (a sixth of the bytes). A page that has not arrived after 30 s is asked for once
         * more with a minute to spare: NVDB now and then streams one answer very slowly while the next is quick.
         */
        private static JsonObject fetch(String url) throws IOException, InterruptedException {
            Map<String, String> headers = Map.of("Accept", "application/json", "Accept-Encoding", "gzip", "X-Client", "orbisterrarum");
            OrbisHttp.Response r;
            try {
                r = OrbisHttp.get(url, headers, 15);
            } catch (java.net.http.HttpTimeoutException slow) {
                r = OrbisHttp.get(url, headers, 45);
            }
            if (r.status() != 200) throw new IOException("HTTP " + r.status() + " from NVDB");
            byte[] body = r.body();
            String enc = r.header("content-encoding");
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
                try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        private static String nextPage(JsonObject root, String url) {
            JsonObject meta = root.has("metadata") ? root.getAsJsonObject("metadata") : null;
            if (meta == null || !meta.has("neste") || !meta.get("neste").isJsonObject()) return null;
            JsonObject next = meta.getAsJsonObject("neste");
            if (!next.has("start")) return null;
            int returned = meta.has("returnert") ? meta.get("returnert").getAsInt() : 0;
            if (returned == 0) return null;
            String start = next.get("start").getAsString();
            String base = url.contains("&start=") ? url.substring(0, url.indexOf("&start=")) : url;
            return base + "&start=" + java.net.URLEncoder.encode(start, StandardCharsets.UTF_8);
        }

        /** Every line of the object's WKT geometry as lat/lon points. */
        private static List<List<double[]>> wktLines(JsonObject o) {
            List<List<double[]>> out = new ArrayList<>();
            if (!o.has("geometri") || !o.getAsJsonObject("geometri").has("wkt")) return out;
            String wkt = o.getAsJsonObject("geometri").get("wkt").getAsString();
            int open = wkt.indexOf('(');
            if (open < 0) return out;
            String body = wkt.substring(open);
            for (String part : body.split("\\)\\s*,\\s*\\(")) {
                String coords = part.replace("(", "").replace(")", "").trim();
                if (coords.isEmpty()) continue;
                List<double[]> line = new ArrayList<>();
                for (String pair : coords.split(",")) {
                    String[] c = pair.trim().split("\\s+");
                    if (c.length < 2) continue;
                    try {
                        line.add(new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[1])});
                    } catch (NumberFormatException ignored) {
                        // skip a malformed vertex
                    }
                }
                if (line.size() >= 2) out.add(line);
            }
            return out;
        }

        // ---- cache format: one JSON array of {w, l, p:[[lat,lon],...]} ----

        private static String serialize(List<Segment> segs) {
            JsonArray arr = new JsonArray();
            for (Segment s : segs) {
                JsonObject o = new JsonObject();
                if (!Double.isNaN(s.widthM)) o.addProperty("w", s.widthM);
                if (s.lanes > 0) o.addProperty("l", s.lanes);
                JsonArray pts = new JsonArray();
                for (double[] p : s.latLon) {
                    JsonArray q = new JsonArray();
                    q.add(Math.round(p[0] * 1e7) / 1e7);
                    q.add(Math.round(p[1] * 1e7) / 1e7);
                    pts.add(q);
                }
                o.add("p", pts);
                arr.add(o);
            }
            return arr.toString();
        }

        private static List<Segment> parseCached(String json) {
            List<Segment> out = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                List<double[]> pts = new ArrayList<>();
                for (JsonElement pe : o.getAsJsonArray("p")) {
                    JsonArray q = pe.getAsJsonArray();
                    pts.add(new double[]{q.get(0).getAsDouble(), q.get(1).getAsDouble()});
                }
                out.add(new Segment(pts, o.has("w") ? o.get("w").getAsDouble() : Double.NaN, o.has("l") ? o.get("l").getAsInt() : -1));
            }
            return out;
        }

        private static String readGzip(Path file) throws IOException {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        }

        private static void writeGzip(Path file, String content) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ matching

    /** Segments of one region in block coordinates, on a grid, with the lookups the rasteriser needs. */
    public static final class Index {
        private static final int CELL = 16;
        private final List<double[][]> lines = new ArrayList<>(); // each: [x, z] points
        private final List<Double> widths = new ArrayList<>();
        private final List<Integer> lanes = new ArrayList<>();
        private final List<Boolean> bothWays = new ArrayList<>();
        private final Map<Long, List<Integer>> grid = new HashMap<>();
        private final double matchBlocks;

        public Index(List<Segment> segments, CoordinateMapper mapper) {
            this.matchBlocks = mapper.blocks(MATCH_METERS);
            for (Segment s : segments) {
                double[][] pts = new double[s.latLon.size()][];
                double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (int i = 0; i < pts.length; i++) {
                    double[] p = s.latLon.get(i);
                    pts[i] = mapper.toBlockExact(p[0], p[1]);
                    minX = Math.min(minX, pts[i][0]);
                    maxX = Math.max(maxX, pts[i][0]);
                    minZ = Math.min(minZ, pts[i][1]);
                    maxZ = Math.max(maxZ, pts[i][1]);
                }
                int idx = lines.size();
                lines.add(pts);
                widths.add(s.widthM);
                lanes.add(s.lanes);
                bothWays.add(s.bothWays);
                int cx0 = (int) Math.floor((minX - matchBlocks) / CELL), cx1 = (int) Math.floor((maxX + matchBlocks) / CELL);
                int cz0 = (int) Math.floor((minZ - matchBlocks) / CELL), cz1 = (int) Math.floor((maxZ + matchBlocks) / CELL);
                if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 4096) continue; // a segment spanning the world: skip
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cz = cz0; cz <= cz1; cz++) {
                        grid.computeIfAbsent(((long) cx << 32) ^ (cz & 0xffffffffL), k -> new ArrayList<>()).add(idx);
                    }
                }
            }
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /**
         * Width (metres) and lanes of the NVDB road under this OSM polyline: samples along the way, each taking the
         * nearest same-direction segment within reach; the median width and the commonest lane count of the samples.
         * Returns {width or NaN, lanes or -1}.
         */
        public double[] match(List<double[]> way) {
            if (way.size() < 2) return new double[]{Double.NaN, -1, 0};
            double total = 0;
            for (int i = 0; i + 1 < way.size(); i++) total += Math.hypot(way.get(i + 1)[0] - way.get(i)[0], way.get(i + 1)[1] - way.get(i)[1]);
            if (total <= 0) return new double[]{Double.NaN, -1, 0};
            boolean both = false;
            int samples = total < 12 ? 1 : total < 60 ? 3 : 5;
            List<Double> ws = new ArrayList<>();
            Map<Integer, Integer> laneVotes = new HashMap<>();
            for (int si = 0; si < samples; si++) {
                double at = total * (si + 1) / (samples + 1);
                double[] p = pointAt(way, at);
                if (p == null) continue;
                double bearing = p[2];
                double bestW = Double.NaN, bestWd = Double.MAX_VALUE;
                int bestL = -1;
                double bestLd = Double.MAX_VALUE;
                int cx = (int) Math.floor(p[0] / CELL), cz = (int) Math.floor(p[1] / CELL);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> cell = grid.get(((long) (cx + dx) << 32) ^ ((cz + dz) & 0xffffffffL));
                        if (cell == null) continue;
                        for (int idx : cell) {
                            double[] near = nearest(lines.get(idx), p[0], p[1]);
                            if (near[0] > matchBlocks) continue;
                            double db = Math.abs(((near[1] - bearing) % 180 + 270) % 180 - 90); // 0 = parallel
                            if (90 - db > MATCH_BEARING_DEG && db > MATCH_BEARING_DEG) continue;
                            double w = widths.get(idx);
                            if (!Double.isNaN(w) && near[0] < bestWd) {
                                bestWd = near[0];
                                bestW = w;
                            }
                            int l = lanes.get(idx);
                            if (l > 0 && near[0] < bestLd) {
                                bestLd = near[0];
                                bestL = l;
                            }
                            if ((!Double.isNaN(w) || l > 0) && bothWays.get(idx)) both = true;
                        }
                    }
                }
                if (!Double.isNaN(bestW)) ws.add(bestW);
                if (bestL > 0) laneVotes.merge(bestL, 1, Integer::sum);
            }
            double width = Double.NaN;
            if (!ws.isEmpty() && (ws.size() >= 2 || samples == 1)) {
                double[] arr = ws.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                width = arr[arr.length / 2];
            }
            int lanesOut = -1, best = 0;
            for (Map.Entry<Integer, Integer> e : laneVotes.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    lanesOut = e.getKey();
                }
            }
            return new double[]{width, lanesOut, both ? 1 : 0};
        }

        /** Point {x, z, bearingDeg} at a distance along the polyline. */
        private static double[] pointAt(List<double[]> way, double dist) {
            double acc = 0;
            for (int i = 0; i + 1 < way.size(); i++) {
                double[] a = way.get(i), b = way.get(i + 1);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (len <= 0) continue;
                if (acc + len >= dist) {
                    double t = (dist - acc) / len;
                    return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]))};
                }
                acc += len;
            }
            return null;
        }

        /** {distance, bearingDeg of the closest piece} from a point to a polyline. */
        private static double[] nearest(double[][] line, double x, double z) {
            double best = Double.MAX_VALUE, bearing = 0;
            for (int i = 0; i + 1 < line.length; i++) {
                double ax = line[i][0], az = line[i][1], bx = line[i + 1][0], bz = line[i + 1][1];
                double dx = bx - ax, dz = bz - az;
                double len2 = dx * dx + dz * dz;
                double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
                double px = ax + dx * t, pz = az + dz * t;
                double d = Math.hypot(x - px, z - pz);
                if (d < best) {
                    best = d;
                    bearing = Math.toDegrees(Math.atan2(dz, dx));
                }
            }
            return new double[]{best, bearing};
        }
    }

    /** Compact description for logs. */
    public static String describe(List<Segment> segs) {
        int w = 0, l = 0;
        for (Segment s : segs) {
            if (!Double.isNaN(s.widthM)) w++;
            if (s.lanes > 0) l++;
        }
        return w + " width segments, " + l + " lane segments";
    }

    static double[] copy(double[] a) {
        return Arrays.copyOf(a, a.length);
    }
}
