package com.berg.orbis.osm;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Road databases outside Norway that know a road's width or lanes (France's BD TOPO, Finland's Digiroad, the US
 * federal-aid roads in FHWA's HPMS, British Columbia's road atlas), turned into the same lines NVDB gives, so the
 * NVDB matcher pairs them with OSM roads (a line running the same way within a few metres). Asked per 0.01 degree
 * tile (WFS 2.0, OGC API Features or ArcGIS GeoJSON, pages of up to 2 000), kept on disk one line a road. A
 * service split by state ({state}) is asked for each state whose outline touches the tile.
 */
public final class RoadDatabases {

    private static final double TILE = 0.01;
    private static final int PAGES = 10;
    private final List<OrbisConfig.RoadSource> sources = new ArrayList<>();
    private final Path cacheDir;
    private final Map<String, CompletableFuture<List<NvdbRoads.Segment>>> tiles = new ConcurrentHashMap<>();
    private final Map<String, Long> failingUntil = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "Orbis-roads");
        t.setDaemon(true);
        return t;
    });

    public RoadDatabases(OrbisConfig cfg, Path cacheDir) {
        this.cacheDir = cacheDir;
        for (OrbisConfig.RoadSource s : cfg.roadSources) {
            if (s != null && s.enabled && s.urlTemplate != null && !s.urlTemplate.isBlank()) sources.add(s);
        }
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Whether any database covers the box (its box and its country's outline). */
    public boolean covers(double south, double west, double north, double east) {
        for (OrbisConfig.RoadSource s : sources) {
            if (north < s.south || south > s.north || east < s.west || west > s.east) continue;
            if (com.berg.orbis.config.DataSources.covers(s.name, south, west, north, east)) return true;
        }
        return false;
    }

    /** Every database's lines in the box, its tiles fetched in parallel. */
    public List<NvdbRoads.Segment> get(double south, double west, double north, double east) {
        List<CompletableFuture<List<NvdbRoads.Segment>>> jobs = new ArrayList<>();
        for (OrbisConfig.RoadSource src : sources) {
            if (north < src.south || south > src.north || east < src.west || west > src.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(src.name, south, west, north, east)) continue;
            for (long ty = (long) Math.floor(south / TILE); ty <= (long) Math.floor(north / TILE); ty++) {
                for (long tx = (long) Math.floor(west / TILE); tx <= (long) Math.floor(east / TILE); tx++) jobs.add(tile(src, ty, tx));
            }
        }
        List<NvdbRoads.Segment> out = new ArrayList<>();
        for (CompletableFuture<List<NvdbRoads.Segment>> f : jobs) {
            try {
                out.addAll(f.join());
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private CompletableFuture<List<NvdbRoads.Segment>> tile(OrbisConfig.RoadSource src, long ty, long tx) {
        String key = src.name + "/" + ty + "_" + tx;
        return tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Path file = cacheDir.resolve(src.name.replaceAll("[^A-Za-z0-9_-]", "_")).resolve(ty + "_" + tx + ".txt");
            try {
                if (Files.exists(file)) return read(file);
                Long until = failingUntil.get(src.name);
                if (until != null && System.currentTimeMillis() < until) {
                    tiles.remove(k);
                    return List.<NvdbRoads.Segment>of();
                }
                double s = ty * TILE, w = tx * TILE, n = s + TILE, e = w + TILE;
                List<NvdbRoads.Segment> list = new ArrayList<>();
                if (src.urlTemplate.contains("{state}")) {
                    for (String area : com.berg.orbis.config.DataSources.areasTouching(src.statePrefix, s, w, n, e)) {
                        list.addAll(download(src, s, w, n, e, area.substring(src.statePrefix.length())));
                    }
                } else {
                    list.addAll(download(src, s, w, n, e, ""));
                }
                write(file, list);
                return list;
            } catch (IOException | InterruptedException | RuntimeException ex) {
                tiles.remove(k);
                if (failingUntil.put(src.name, System.currentTimeMillis() + 3 * 60_000L) == null) {
                    System.err.println("[orbis] Road database '" + src.name + "' unavailable (" + ex + "); default road widths for 3 minutes");
                }
                return List.<NvdbRoads.Segment>of();
            }
        }, POOL));
    }

    private List<NvdbRoads.Segment> download(OrbisConfig.RoadSource src, double s, double w, double n, double e, String state)
            throws IOException, InterruptedException {
        // Lines of one road link: lanes summed over its records (Digiroad keeps one a direction), the widest width.
        Map<String, double[]> byGroup = new LinkedHashMap<>(); // group -> {width, lanes}
        Map<String, List<double[]>> geometry = new HashMap<>();
        List<NvdbRoads.Segment> out = new ArrayList<>();
        for (int page = 0; page < PAGES; page++) {
            String url = src.urlTemplate.replace("{south}", fmt(s)).replace("{west}", fmt(w)).replace("{north}", fmt(n)).replace("{east}", fmt(e))
                    .replace("{count}", String.valueOf(src.pageSize)).replace("{start}", String.valueOf(page * src.pageSize))
                    .replace("{state}", state.toUpperCase(Locale.ROOT));
            OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0", "Accept-Encoding", "identity"), 90);
            if (r.status() == 404 && !state.isEmpty()) return out; // no service for this state
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            JsonElement root = JsonParser.parseString(r.text());
            if (!root.isJsonObject() || !root.getAsJsonObject().has("features")) {
                if (!state.isEmpty()) return out; // ArcGIS answers "service not found" as a JSON error
                throw new IOException("not a feature collection");
            }
            JsonArray feats = root.getAsJsonObject().getAsJsonArray("features");
            for (int i = 0; i < feats.size(); i++) {
                JsonObject f = feats.get(i).getAsJsonObject();
                JsonObject props = f.has("properties") && f.get("properties").isJsonObject() ? f.getAsJsonObject("properties") : new JsonObject();
                List<double[]> line = line(f.get("geometry"));
                if (line == null) continue;
                double width = number(props, src.widthField) * src.widthScale;
                int lanes = (int) Math.round(number(props, src.lanesField));
                double laneW = number(props, src.laneWidthField) * src.laneWidthScale;
                if (Double.isNaN(width) && lanes > 0 && laneW > 1.5 && laneW < 6) width = lanes * laneW;
                if (!(width >= 2 && width < 60)) width = Double.NaN;
                if (lanes <= 0 || lanes > 16) lanes = -1;
                if (Double.isNaN(width) && lanes < 0) continue;
                String group = src.groupField == null || src.groupField.isBlank() || !props.has(src.groupField) ? null
                        : props.get(src.groupField).getAsString();
                boolean both = src.bothWays && !(src.oneWayField != null && !src.oneWayField.isBlank() && props.has(src.oneWayField)
                        && !props.get(src.oneWayField).isJsonNull() && src.oneWayValue.equals(props.get(src.oneWayField).getAsString()));
                if (group == null) {
                    out.add(new NvdbRoads.Segment(line, width, lanes, both));
                    continue;
                }
                double[] v = byGroup.computeIfAbsent(group, g -> new double[]{Double.NaN, 0});
                if (!Double.isNaN(width)) v[0] = Double.isNaN(v[0]) ? width : Math.max(v[0], width);
                if (lanes > 0) v[1] += lanes;
                geometry.putIfAbsent(group, line);
            }
            if (feats.size() < src.pageSize || !src.urlTemplate.contains("{start}")) break;
        }
        for (var g : byGroup.entrySet()) {
            int lanes = g.getValue()[1] > 0 ? (int) g.getValue()[1] : -1;
            out.add(new NvdbRoads.Segment(geometry.get(g.getKey()), g.getValue()[0], lanes, src.bothWays));
        }
        return out;
    }

    /** A LineString (or a MultiLineString's longest part) as lat, lon points. */
    private static List<double[]> line(JsonElement g) {
        if (g == null || !g.isJsonObject()) return null;
        JsonObject o = g.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";
        JsonArray coords = o.getAsJsonArray("coordinates");
        if (coords == null) return null;
        JsonArray pts = null;
        if (type.equals("LineString")) {
            pts = coords;
        } else if (type.equals("MultiLineString")) {
            for (JsonElement part : coords) if (pts == null || part.getAsJsonArray().size() > pts.size()) pts = part.getAsJsonArray();
        }
        if (pts == null || pts.size() < 2) return null;
        List<double[]> out = new ArrayList<>(pts.size());
        for (JsonElement p : pts) {
            JsonArray a = p.getAsJsonArray();
            out.add(new double[]{a.get(1).getAsDouble(), a.get(0).getAsDouble()});
        }
        return out;
    }

    private static double number(JsonObject props, String field) {
        if (field == null || field.isBlank() || !props.has(field)) return Double.NaN;
        JsonElement v = props.get(field);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return Double.NaN;
        try {
            return v.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return Double.NaN;
        }
    }

    /** One road a line: width (empty if unknown), lanes (negative: counting both directions, -100 none), lat, lon pairs. */
    private static void write(Path file, List<NvdbRoads.Segment> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (NvdbRoads.Segment seg : list) {
            int lanes = seg.bothWays() ? (seg.lanes() > 0 ? -seg.lanes() : -100) : seg.lanes();
            sb.append(Double.isNaN(seg.widthM()) ? "" : String.format(Locale.ROOT, "%.2f", seg.widthM())).append(' ').append(lanes);
            for (double[] p : seg.latLon()) sb.append(' ').append(String.format(Locale.ROOT, "%.6f %.6f", p[0], p[1]));
            sb.append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<NvdbRoads.Segment> read(Path file) throws IOException {
        List<NvdbRoads.Segment> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split(" ");
            if (p.length < 6) continue;
            List<double[]> pts = new ArrayList<>();
            for (int i = 2; i + 1 < p.length; i += 2) pts.add(new double[]{Double.parseDouble(p[i]), Double.parseDouble(p[i + 1])});
            int l = Integer.parseInt(p[1]);
            boolean both = l < -1;
            int lanes = l == -100 ? -1 : both ? -l : l;
            out.add(new NvdbRoads.Segment(pts, p[0].isEmpty() ? Double.NaN : Double.parseDouble(p[0]), lanes, both));
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
