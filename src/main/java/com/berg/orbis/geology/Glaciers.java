package com.berg.orbis.geology;

import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.net.OrbisHttp;
import com.berg.orbis.osm.CoordinateMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * How thick the ice of a glacier is. Where each glacier lies comes from the Randolph Glacier Inventory 7.0 (GLIMS, CC BY 4.0,
 * outlines for about the year 2000, with names); where the ice is today stays with OpenStreetMap and ESA WorldCover. The
 * inventory has no thickness everyone may use, so it is worked out the way a glaciologist would at first go: a perfectly
 * plastic ice sheet (Nye) grows with the square root of the distance from its edge, about 47 m at 100 m in and 150 m at a
 * kilometre, and no glacier holds more than twice the mean thickness its area gives (Bahr's volume-area scaling: 12 m for a
 * small cirque glacier, 190 m for one of 100 km²). Glaciers that touch (the outlets of an ice cap) count as one body, so
 * the ice stays thick under the divides between them. Outlines come by tiles of a quarter degree, kept on disk; a failure
 * is not kept (asked again after 10 minutes).
 */
public final class Glaciers {
    public static final String ATTRIBUTION = "Randolph Glacier Inventory 7.0 (RGI Consortium 2023, GLIMS, CC BY 4.0) glacier outlines";

    private static final String WFS = "https://www.glims.org/geoserver/ows?service=WFS&version=2.0.0&request=GetFeature"
            + "&typeNames=GLIMS:RGI2000-v7.0-G-%s_epsg3857&outputFormat=application/json&srsName=EPSG:4326"
            + "&propertyName=rgi_id,glac_name,area_km2,the_geom&bbox=%.4f,%.4f,%.4f,%.4f,urn:ogc:def:crs:EPSG::4326&count=%d&startIndex=%d";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; glacier ice)");
    private static final double TILE = 0.25;
    private static final int PAGE = 2000;
    /** Distance from the edge past which the ice is taken to be as thick as it gets, in metres. */
    private static final double MAX_EDGE_M = 4000;
    /** 2 τ / (ρ g) for a yield stress of 100 kPa: the Nye profile's thickness squared per metre from the edge. */
    private static final double NYE = 2 * 100_000 / (917 * 9.81);
    private static final double MAX_ICE_M = 600;

    /** The inventory's 19 regions, each with the boxes it covers (south, west, north, east; generous). */
    private static final Object[][] REGIONS = {
            {"01_alaska", new double[][]{{51, -180, 72, -126}, {51, 165, 56, 180}}},
            {"02_western_canada_usa", new double[][]{{32, -140, 63, -100}}},
            {"03_arctic_canada_north", new double[][]{{74, -130, 84, -60}}},
            {"04_arctic_canada_south", new double[][]{{58, -96, 75, -60}}},
            {"05_greenland_periphery", new double[][]{{59, -75, 84, -10}}},
            {"06_iceland", new double[][]{{63, -25, 67, -13}}},
            {"07_svalbard_jan_mayen", new double[][]{{70, -10, 81, 36}}},
            {"08_scandinavia", new double[][]{{58, 4, 72, 35}}},
            {"09_russian_arctic", new double[][]{{70, 35, 82, 160}}},
            {"10_north_asia", new double[][]{{40, 55, 78, 180}, {60, -180, 72, -168}}},
            {"11_central_europe", new double[][]{{40, -2, 51, 20}}},
            {"12_caucasus_middle_east", new double[][]{{28, 26, 46, 62}}},
            {"13_central_asia", new double[][]{{26, 64, 56, 106}}},
            {"14_south_asia_west", new double[][]{{26, 64, 40, 82}}},
            {"15_south_asia_east", new double[][]{{22, 76, 34, 106}}},
            {"16_low_latitudes", new double[][]{{-25, -110, 26, -55}, {-10, 25, 5, 45}, {-6, 130, 0, 142}}},
            {"17_southern_andes", new double[][]{{-57, -77, -17, -62}}},
            {"18_new_zealand", new double[][]{{-48, 165, -38, 179}}},
            {"19_subantarctic_antarctic_islands", new double[][]{{-90, -180, -45, 180}}},
    };

    /** One glacier: its rings (outer and holes, lat/lon pairs), its area and name. */
    public record Glacier(String id, String name, double areaKm2, List<float[]> rings, double south, double west, double north, double east) {
    }

    private final Path cacheDir;
    private volatile long failedUntil;
    private final Map<String, List<Glacier>> tiles = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Glacier>> e) {
            return size() > 24;
        }
    });

    public Glaciers(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Whether the inventory may have glaciers in the box (a cheap test on its regions). */
    public static boolean mayCover(double south, double west, double north, double east) {
        for (Object[] reg : REGIONS) for (double[] b : (double[][]) reg[1]) if (south < b[2] && north > b[0] && west < b[3] && east > b[1]) return true;
        return false;
    }

    /** The glaciers touching the box, or null when the inventory could not be read. */
    public List<Glacier> glaciers(double south, double west, double north, double east) {
        if (!mayCover(south, west, north, east)) return List.of();
        if (System.currentTimeMillis() < failedUntil) return null;
        Map<String, Glacier> out = new LinkedHashMap<>();
        try {
            for (int i = (int) Math.floor(south / TILE); i <= (int) Math.floor(north / TILE); i++) {
                for (int j = (int) Math.floor(west / TILE); j <= (int) Math.floor(east / TILE); j++) {
                    for (Glacier g : tile(i, j)) {
                        if (g.south <= north && g.north >= south && g.west <= east && g.east >= west) out.putIfAbsent(g.id, g);
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Glacier outlines unavailable (" + e.getMessage() + "); glaciers keep a thin ice layer for 10 minutes");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Blocks of ice under each cell of the raster that {@code iceCell} accepts and that lies inside a glacier of the
     * inventory, or null when none does (or the outlines could not be read). Worked out on a lattice fixed in world
     * coordinates (about 32 m apart), so neighbouring regions agree.
     */
    public short[] iceDepth(RegionRaster r, CoordinateMapper mapper, double verticalMetresPerBlock, IntPredicate iceCell) {
        double mpb = mapper.metersPerBlock();
        int step = Math.max(1, (int) Math.round(32 / mpb));
        int pad = (int) Math.ceil(MAX_EDGE_M / mpb) + step;
        int wx0 = Math.floorDiv(r.originX - pad, step) * step, wz0 = Math.floorDiv(r.originZ - pad, step) * step;
        int nx = (r.stride + 2 * pad) / step + 3, nz = nx;
        // The window's box in lat/lon (corners: the projection may be transverse Mercator).
        double south = 90, north = -90, west = 180, east = -180;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                double[] ll = mapper.toLatLonExact(wx0 + cx * (double) nx * step, wz0 + cz * (double) nz * step);
                south = Math.min(south, ll[0]);
                north = Math.max(north, ll[0]);
                west = Math.min(west, ll[1]);
                east = Math.max(east, ll[1]);
            }
        }
        List<Glacier> found = glaciers(south, west, north, east);
        if (found == null || found.isEmpty()) return null;

        // Rings in block coordinates, with each glacier's greatest thickness.
        List<double[][]> polyRings = new ArrayList<>(); // per ring: {xs, zs, {zmin, zmax}}
        List<Integer> ringOwner = new ArrayList<>();
        double[] maxIce = new double[found.size()];
        for (int g = 0; g < found.size(); g++) {
            Glacier gl = found.get(g);
            maxIce[g] = Math.min(MAX_ICE_M, 2 * 34 * Math.pow(Math.max(0.01, gl.areaKm2), 0.375));
            for (float[] ring : gl.rings) {
                int m = ring.length / 2;
                double[] xs = new double[m], zs = new double[m];
                double zmin = Double.MAX_VALUE, zmax = -Double.MAX_VALUE;
                for (int k = 0; k < m; k++) {
                    double[] b = mapper.toBlockExact(ring[2 * k], ring[2 * k + 1]);
                    xs[k] = b[0];
                    zs[k] = b[1];
                    zmin = Math.min(zmin, b[1]);
                    zmax = Math.max(zmax, b[1]);
                }
                polyRings.add(new double[][]{xs, zs, {zmin, zmax}});
                ringOwner.add(g);
            }
        }

        // Which glacier each lattice point lies in (-1: none), even-odd per glacier along each lattice row.
        int[] owner = new int[nx * nz];
        Arrays.fill(owner, -1);
        boolean anyInside = false;
        double[] xsBuf = new double[64];
        for (int j = 0; j < nz; j++) {
            double z = wz0 + (double) j * step;
            int ringIndex = 0;
            while (ringIndex < polyRings.size()) {
                int g = ringOwner.get(ringIndex);
                int n = 0;
                // All rings of glacier g (they are consecutive).
                while (ringIndex < polyRings.size() && ringOwner.get(ringIndex) == g) {
                    double[][] ring = polyRings.get(ringIndex++);
                    if (z < ring[2][0] || z > ring[2][1]) continue;
                    double[] xs = ring[0], zs = ring[1];
                    int m = xs.length;
                    for (int k = 0; k < m; k++) {
                        int k2 = (k + 1) % m;
                        if ((zs[k] <= z && zs[k2] > z) || (zs[k2] <= z && zs[k] > z)) {
                            double t = (z - zs[k]) / (zs[k2] - zs[k]);
                            if (n == xsBuf.length) xsBuf = Arrays.copyOf(xsBuf, n * 2);
                            xsBuf[n++] = xs[k] + t * (xs[k2] - xs[k]);
                        }
                    }
                }
                if (n < 2) continue;
                Arrays.sort(xsBuf, 0, n);
                for (int q = 0; q + 1 < n; q += 2) {
                    int i0 = Math.max(0, (int) Math.ceil((xsBuf[q] - wx0) / step));
                    int i1 = Math.min(nx - 1, (int) Math.floor((xsBuf[q + 1] - wx0) / step));
                    for (int i = i0; i <= i1; i++) {
                        owner[j * nx + i] = g;
                        anyInside = true;
                    }
                }
            }
        }
        if (!anyInside) return null;

        // Distance of each lattice point inside the ice from the nearest point outside all of it (8-neighbour chamfer).
        double inf = Double.MAX_VALUE / 4, diag = Math.sqrt(2);
        double[] d = new double[nx * nz];
        for (int k = 0; k < d.length; k++) d[k] = owner[k] < 0 ? 0 : inf;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (d[k] == 0) continue;
                double v = d[k];
                if (i > 0) v = Math.min(v, d[k - 1] + 1);
                if (j > 0) {
                    v = Math.min(v, d[k - nx] + 1);
                    if (i > 0) v = Math.min(v, d[k - nx - 1] + diag);
                    if (i < nx - 1) v = Math.min(v, d[k - nx + 1] + diag);
                }
                d[k] = v;
            }
        }
        for (int j = nz - 1; j >= 0; j--) {
            for (int i = nx - 1; i >= 0; i--) {
                int k = j * nx + i;
                if (d[k] == 0) continue;
                double v = d[k];
                if (i < nx - 1) v = Math.min(v, d[k + 1] + 1);
                if (j < nz - 1) {
                    v = Math.min(v, d[k + nx] + 1);
                    if (i < nx - 1) v = Math.min(v, d[k + nx + 1] + diag);
                    if (i > 0) v = Math.min(v, d[k + nx - 1] + diag);
                }
                d[k] = v;
            }
        }
        double[] ice = new double[nx * nz];
        for (int k = 0; k < ice.length; k++) {
            if (owner[k] < 0) continue;
            double metres = Math.min(MAX_EDGE_M, d[k] * step * mpb);
            ice[k] = Math.min(maxIce[owner[k]], Math.sqrt(NYE * metres));
        }

        // Each accepted cell: the lattice's thickness, bilinear, in blocks (at least one inside a glacier).
        short[] out = null;
        for (int lz = 0; lz < r.stride; lz++) {
            for (int lx = 0; lx < r.stride; lx++) {
                int idx = lz * r.stride + lx;
                if (!iceCell.test(idx)) continue;
                double fx = (r.originX + lx + 0.5 - wx0) / step, fz = (r.originZ + lz + 0.5 - wz0) / step;
                int i = (int) Math.floor(fx), j = (int) Math.floor(fz);
                if (i < 0 || j < 0 || i + 1 >= nx || j + 1 >= nz) continue;
                double tx = fx - i, tz = fz - j;
                int k = j * nx + i;
                boolean inside = owner[k] >= 0 || owner[k + 1] >= 0 || owner[k + nx] >= 0 || owner[k + nx + 1] >= 0;
                if (!inside) continue;
                double h = (ice[k] * (1 - tx) + ice[k + 1] * tx) * (1 - tz) + (ice[k + nx] * (1 - tx) + ice[k + nx + 1] * tx) * tz;
                int blocks = (int) Math.max(1, Math.min(Short.MAX_VALUE, Math.round(h / verticalMetresPerBlock)));
                if (out == null) out = new short[r.stride * r.stride];
                out[idx] = (short) blocks;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tiles

    private List<Glacier> tile(int i, int j) throws IOException, InterruptedException {
        String key = i + "_" + j;
        List<Glacier> t = tiles.get(key);
        if (t != null) return t;
        double south = i * TILE, west = j * TILE, north = south + TILE, east = west + TILE;
        Path file = cacheDir.resolve(String.format(Locale.ROOT, "%.2f_%.2f.bin.gz", south, west));
        try {
            if (Files.exists(file)) t = load(file);
        } catch (IOException e) {
            System.err.println("[orbis] Glacier tile " + file.getFileName() + " unreadable, fetching again: " + e.getMessage());
        }
        if (t == null) {
            t = new ArrayList<>();
            for (Object[] reg : REGIONS) {
                boolean hit = false;
                for (double[] b : (double[][]) reg[1]) hit |= south < b[2] && north > b[0] && west < b[3] && east > b[1];
                if (hit) t.addAll(fetch((String) reg[0], south, west, north, east));
            }
            Files.createDirectories(cacheDir);
            save(file, t);
        }
        tiles.put(key, t);
        return t;
    }

    private static List<Glacier> fetch(String region, double south, double west, double north, double east) throws IOException, InterruptedException {
        List<Glacier> out = new ArrayList<>();
        for (int page = 0; page < 20; page++) {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            String url = String.format(Locale.ROOT, WFS, region, south, west, north, east, PAGE, page * PAGE);
            JsonArray features = features(url);
            for (JsonElement fe : features) {
                Glacier g = parse(fe.getAsJsonObject());
                if (g != null) out.add(g);
            }
            if (features.size() < PAGE) break;
        }
        return out;
    }

    private static JsonArray features(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 90);
                if (r.status() == 200) {
                    JsonObject root = JsonParser.parseString(r.text()).getAsJsonObject();
                    if (root.has("features") && root.get("features").isJsonArray()) return root.getAsJsonArray("features");
                    last = new IOException("no features in the answer");
                } else {
                    last = new IOException("HTTP " + r.status());
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000L << attempt);
        }
        throw last;
    }

    private static Glacier parse(JsonObject f) {
        JsonObject props = f.has("properties") && f.get("properties").isJsonObject() ? f.getAsJsonObject("properties") : new JsonObject();
        if (!f.has("geometry") || !f.get("geometry").isJsonObject()) return null;
        JsonObject geom = f.getAsJsonObject("geometry");
        String type = geom.has("type") ? geom.get("type").getAsString() : "";
        JsonArray c = geom.getAsJsonArray("coordinates");
        if (c == null) return null;
        List<JsonArray> polygons = new ArrayList<>();
        if (type.equals("Polygon")) polygons.add(c);
        else if (type.equals("MultiPolygon")) for (JsonElement p : c) polygons.add(p.getAsJsonArray());
        List<float[]> rings = new ArrayList<>();
        double s = 90, n = -90, w = 180, e = -180;
        for (JsonArray poly : polygons) {
            for (JsonElement re : poly) {
                JsonArray pts = re.getAsJsonArray();
                float[] ring = new float[pts.size() * 2];
                for (int k = 0; k < pts.size(); k++) {
                    JsonArray p = pts.get(k).getAsJsonArray();
                    // As floats, as they are kept on disk: a region drawn now and one drawn after a restart see the same lines.
                    float lat = (float) p.get(1).getAsDouble(), lon = (float) p.get(0).getAsDouble();
                    ring[2 * k] = lat;
                    ring[2 * k + 1] = lon;
                    s = Math.min(s, lat);
                    n = Math.max(n, lat);
                    w = Math.min(w, lon);
                    e = Math.max(e, lon);
                }
                if (ring.length >= 6) rings.add(ring);
            }
        }
        if (rings.isEmpty()) return null;
        String id = props.has("rgi_id") && !props.get("rgi_id").isJsonNull() ? props.get("rgi_id").getAsString()
                : f.has("id") ? f.get("id").getAsString() : String.valueOf(rings.get(0)[0]) + rings.get(0)[1];
        String name = props.has("glac_name") && !props.get("glac_name").isJsonNull() ? props.get("glac_name").getAsString() : "";
        double area = props.has("area_km2") && !props.get("area_km2").isJsonNull() ? props.get("area_km2").getAsDouble() : 0.1;
        return new Glacier(id, name, area, rings, s, w, n, e);
    }

    private static void save(Path file, List<Glacier> list) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeInt(list.size());
            for (Glacier g : list) {
                out.writeUTF(g.id);
                out.writeUTF(g.name);
                out.writeDouble(g.areaKm2);
                out.writeInt(g.rings.size());
                for (float[] ring : g.rings) {
                    out.writeInt(ring.length);
                    for (float v : ring) out.writeFloat(v);
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Glacier> load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != 1) throw new IOException("unknown tile version");
            int count = in.readInt();
            List<Glacier> out = new ArrayList<>(count);
            for (int q = 0; q < count; q++) {
                String id = in.readUTF(), name = in.readUTF();
                double area = in.readDouble();
                int nr = in.readInt();
                List<float[]> rings = new ArrayList<>(nr);
                double s = 90, n = -90, w = 180, e = -180;
                for (int k = 0; k < nr; k++) {
                    int len = in.readInt();
                    if (len < 0 || len > 20_000_000) throw new IOException("bad ring");
                    float[] ring = new float[len];
                    for (int v = 0; v < len; v++) ring[v] = in.readFloat();
                    for (int v = 0; v + 1 < len; v += 2) {
                        s = Math.min(s, ring[v]);
                        n = Math.max(n, ring[v]);
                        w = Math.min(w, ring[v + 1]);
                        e = Math.max(e, ring[v + 1]);
                    }
                    rings.add(ring);
                }
                out.add(new Glacier(id, name, area, rings, s, w, n, e));
            }
            return out;
        }
    }
}
