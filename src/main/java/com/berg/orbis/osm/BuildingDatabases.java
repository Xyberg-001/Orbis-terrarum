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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * National and city building databases that know each building's height or number of floors (France's BD TOPO,
 * the Netherlands' 3DBAG, Slovenia's building cadastre, Vienna's building model, New York's footprints). Asked
 * per 0.01 degree tile (WFS 2.0 or Socrata, one page of up to 5 000 buildings at a time), kept on disk in a compact
 * form, and matched to an OSM building by its centre: the database building it stands in, else the nearest within
 * 8 m. Each database only inside its own country or city (see DataSources).
 */
public final class BuildingDatabases {

    /** What a database says about a building: height in metres (NaN if only the floors are known), floors (0 if not known). */
    public record Hit(double heightM, int floors, boolean flatRoof, boolean eaveHeight, String source) {
    }

    /** One database building: its outline as lat, lon pairs with its box, and what is known about it. */
    private record Building(double s, double w, double n, double e, double[] ring, float heightM, int floors, boolean flat) {
        boolean contains(double lat, double lon) {
            if (lat < s || lat > n || lon < w || lon > e) return false;
            boolean in = false;
            int pts = ring.length / 2;
            for (int i = 0, j = pts - 1; i < pts; j = i++) {
                double yi = ring[i * 2], xi = ring[i * 2 + 1], yj = ring[j * 2], xj = ring[j * 2 + 1];
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }
    }

    private static final double TILE = 0.01;
    private static final int PAGES = 8;
    private final List<OrbisConfig.BuildingSource> sources = new ArrayList<>();
    private final Path cacheDir;
    private final Map<String, CompletableFuture<List<Building>>> tiles = new ConcurrentHashMap<>();
    private final Map<String, Long> failingUntil = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Orbis-buildings");
        t.setDaemon(true);
        return t;
    });

    public BuildingDatabases(OrbisConfig cfg, Path cacheDir) {
        this.cacheDir = cacheDir;
        // A source without an address is imported data (tools/lod2_heights.py): its tiles are only read from disk.
        for (OrbisConfig.BuildingSource s : cfg.buildingSources) {
            if (s != null && s.enabled && s.name != null && !s.name.isBlank()) sources.add(s);
        }
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Loads the tiles of every database covering the box (south, west, north, east) in parallel and waits. */
    public void prefetch(double south, double west, double north, double east) {
        List<CompletableFuture<?>> jobs = new ArrayList<>();
        for (OrbisConfig.BuildingSource src : sources) {
            if (north < src.south || south > src.north || east < src.west || west > src.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(src.name, south, west, north, east)) continue;
            for (long ty = (long) Math.floor(south / TILE); ty <= (long) Math.floor(north / TILE); ty++) {
                for (long tx = (long) Math.floor(west / TILE); tx <= (long) Math.floor(east / TILE); tx++) jobs.add(tile(src, ty, tx));
            }
        }
        for (CompletableFuture<?> f : jobs) {
            try {
                f.join();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** What the first database covering the place knows about the building standing there, or null. */
    public Hit lookup(double lat, double lon) {
        for (OrbisConfig.BuildingSource src : sources) {
            if (lat < src.south || lat > src.north || lon < src.west || lon > src.east) continue;
            List<Building> list;
            try {
                list = tile(src, (long) Math.floor(lat / TILE), (long) Math.floor(lon / TILE)).join();
            } catch (RuntimeException e) {
                continue;
            }
            Building best = null;
            for (Building b : list) {
                if (b.contains(lat, lon)) {
                    best = b;
                    break;
                }
            }
            if (best == null) {
                // The OSM outline and the database's differ a little: the nearest database building within 8 m.
                double bestD = 8.0, mLat = 111_320.0, mLon = 111_320.0 * Math.cos(Math.toRadians(lat));
                for (Building b : list) {
                    double dy = Math.max(0, Math.max(b.s - lat, lat - b.n)) * mLat, dx = Math.max(0, Math.max(b.w - lon, lon - b.e)) * mLon;
                    double d = Math.hypot(dx, dy);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
            if (best != null) return new Hit(best.heightM, best.floors, best.flat, src.eaveHeight, src.name);
        }
        return null;
    }

    // ------------------------------------------------------------------ tiles

    private CompletableFuture<List<Building>> tile(OrbisConfig.BuildingSource src, long ty, long tx) {
        String key = src.name + "/" + ty + "_" + tx;
        return tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Path file = cacheDir.resolve(src.name.replaceAll("[^A-Za-z0-9_-]", "_")).resolve(ty + "_" + tx + ".txt");
            try {
                if (Files.exists(file)) return read(file);
                if (src.urlTemplate == null || src.urlTemplate.isBlank()) {
                    tiles.remove(k); // imported data not imported here (yet): look again next time
                    return List.<Building>of();
                }
                Long until = failingUntil.get(src.name);
                if (until != null && System.currentTimeMillis() < until) {
                    tiles.remove(k);
                    return List.<Building>of();
                }
                List<Building> list = download(src, ty * TILE, tx * TILE, (ty + 1) * TILE, (tx + 1) * TILE);
                write(file, list);
                return list;
            } catch (IOException | InterruptedException | RuntimeException e) {
                tiles.remove(k);
                if (failingUntil.put(src.name, System.currentTimeMillis() + 3 * 60_000L) == null) {
                    System.err.println("[orbis] Building database '" + src.name + "' unavailable (" + e + "); estimated heights for 3 minutes");
                }
                return List.<Building>of();
            }
        }, POOL));
    }

    private List<Building> download(OrbisConfig.BuildingSource src, double s, double w, double n, double e) throws IOException, InterruptedException {
        List<Building> out = new ArrayList<>();
        for (int page = 0; page < PAGES; page++) {
            String url = src.urlTemplate.replace("{south}", fmt(s)).replace("{west}", fmt(w)).replace("{north}", fmt(n)).replace("{east}", fmt(e))
                    .replace("{count}", String.valueOf(src.pageSize)).replace("{start}", String.valueOf(page * src.pageSize));
            OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0", "Accept-Encoding", "identity"), 90);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            JsonElement root = JsonParser.parseString(r.text());
            JsonArray feats = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("features");
            if (feats == null) throw new IOException("not a feature collection");
            for (JsonElement fe : feats) {
                Building b = parse(src, fe.getAsJsonObject());
                if (b != null) out.add(b);
            }
            if (feats.size() < src.pageSize || !src.urlTemplate.contains("{start}")) break;
        }
        return out;
    }

    private static Building parse(OrbisConfig.BuildingSource src, JsonObject f) {
        JsonObject props = f.has("properties") && f.get("properties").isJsonObject() ? f.getAsJsonObject("properties") : f;
        JsonElement geom = src.geometryField == null || src.geometryField.isBlank() ? f.get("geometry")
                : props.has(src.geometryField) ? props.get(src.geometryField) : f.get(src.geometryField);
        if (geom == null || !geom.isJsonObject()) geom = f.get("geometry");
        if (geom == null || !geom.isJsonObject()) return null;
        double h = number(props, src.heightField), base = number(props, src.baseField);
        double height = Double.isNaN(h) ? Double.NaN : (h - (Double.isNaN(base) ? 0 : base)) * src.valueScale;
        int floors = (int) Math.round(number(props, src.floorsField));
        if (floors < 0) floors = 0;
        if (!(height >= 2 && height < 1000)) height = Double.NaN;
        if (Double.isNaN(height) && floors <= 0) return null;
        boolean flat = false;
        if (src.roofField != null && !src.roofField.isBlank() && props.has(src.roofField) && props.get(src.roofField).isJsonPrimitive()) {
            String v = props.get(src.roofField).getAsString().toLowerCase(Locale.ROOT);
            flat = v.contains("horizontal") || v.equals("flat");
        }
        double[] ring = outerRing(geom.getAsJsonObject());
        if (ring == null || ring.length < 6) return null;
        double s = 90, w = 180, n = -90, e = -180;
        for (int i = 0; i < ring.length; i += 2) {
            s = Math.min(s, ring[i]);
            n = Math.max(n, ring[i]);
            w = Math.min(w, ring[i + 1]);
            e = Math.max(e, ring[i + 1]);
        }
        return new Building(s, w, n, e, ring, (float) height, floors, flat);
    }

    /** The outer ring of a Polygon, or of a MultiPolygon's largest part, as lat, lon pairs. */
    private static double[] outerRing(JsonObject g) {
        String type = g.has("type") ? g.get("type").getAsString() : "";
        JsonArray coords = g.getAsJsonArray("coordinates");
        if (coords == null) return null;
        JsonArray ring = null;
        if (type.equals("Polygon")) {
            ring = coords.get(0).getAsJsonArray();
        } else if (type.equals("MultiPolygon")) {
            int most = -1;
            for (JsonElement poly : coords) {
                JsonArray r = poly.getAsJsonArray().get(0).getAsJsonArray();
                if (r.size() > most) {
                    most = r.size();
                    ring = r;
                }
            }
        }
        if (ring == null) return null;
        double[] out = new double[ring.size() * 2];
        for (int i = 0; i < ring.size(); i++) {
            JsonArray p = ring.get(i).getAsJsonArray();
            out[i * 2] = p.get(1).getAsDouble();
            out[i * 2 + 1] = p.get(0).getAsDouble();
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

    // ------------------------------------------------------------------ the copies on disk

    /** One building a line: height, floors, flat (0/1), then the outline's lat, lon pairs (6 decimals). */
    private static void write(Path file, List<Building> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Building b : list) {
            sb.append(Float.isNaN(b.heightM) ? "" : String.format(Locale.ROOT, "%.1f", b.heightM)).append(' ').append(b.floors).append(' ').append(b.flat ? 1 : 0);
            for (double v : b.ring) sb.append(' ').append(String.format(Locale.ROOT, "%.6f", v));
            sb.append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Building> read(Path file) throws IOException {
        List<Building> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split(" ");
            if (p.length < 9) continue;
            float h = p[0].isEmpty() ? Float.NaN : Float.parseFloat(p[0]);
            double[] ring = new double[p.length - 3];
            double s = 90, w = 180, n = -90, e = -180;
            for (int i = 3; i < p.length; i++) {
                ring[i - 3] = Double.parseDouble(p[i]);
                if ((i - 3) % 2 == 0) {
                    s = Math.min(s, ring[i - 3]);
                    n = Math.max(n, ring[i - 3]);
                } else {
                    w = Math.min(w, ring[i - 3]);
                    e = Math.max(e, ring[i - 3]);
                }
            }
            out.add(new Building(s, w, n, e, ring, h, Integer.parseInt(p[1]), p[2].equals("1")));
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
