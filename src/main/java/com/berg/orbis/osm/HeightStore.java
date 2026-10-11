package com.berg.orbis.osm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Building heights from the GlobalBuildingAtlas (TUM, CC BY-NC 4.0), written by tools/gba_heights.py into
 * 0.1-degree cell files under config/orbisterrarum/heights/, one JSON array per building:
 * {@code [osmWayId or 0, lat, lon, heightMetres]}. A building the map gives no height or level tag gets the
 * atlas height, matched by its OSM id or, failing that, by the nearest atlas building within a few metres
 * of its centroid (the atlas also holds buildings from Microsoft and Google that are not in OSM).
 */
public final class HeightStore {

    static final double CELL_DEG = 0.1;
    private static final int CACHE_CELLS = 64;
    private static final double MATCH_METERS = 20;

    private static final Map<Path, HeightStore> STORES = new HashMap<>();

    /** One cell's buildings: ids, positions and heights in parallel arrays. */
    private record Cell(long[] ids, double[] lats, double[] lons, float[] heights) {
        static final Cell EMPTY = new Cell(new long[0], new double[0], new double[0], new float[0]);
    }

    private final Path root;
    private final Map<String, Cell> cells = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cell> eldest) {
            return size() > CACHE_CELLS;
        }
    });
    private volatile int fileCount = -1;

    private HeightStore(Path root) {
        this.root = root;
    }

    public static synchronized HeightStore get(Path root) {
        return STORES.computeIfAbsent(root, HeightStore::new);
    }

    public void rescan() {
        cells.clear();
        fileCount = -1;
    }

    public boolean isEmpty() {
        int n = fileCount;
        if (n < 0) {
            n = 0;
            if (Files.isDirectory(root)) {
                try (var s = Files.list(root)) {
                    n = (int) s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz")).count();
                } catch (IOException e) {
                    n = 0;
                }
            }
            fileCount = n;
        }
        return n == 0;
    }

    /** The atlas buildings of a box, indexed for the two lookups the rasteriser makes. */
    public static final class Loaded {
        private final Map<Long, Float> byId = new HashMap<>();
        private final Map<Long, List<int[]>> grid = new HashMap<>(); // 0.0005-degree buckets -> indices into the arrays
        private final List<double[]> points = new ArrayList<>();      // {lat, lon, height}
        public int size;

        /** Height in metres for a building, or NaN. */
        public double heightFor(long osmId, double lat, double lon) {
            Float h = byId.get(osmId);
            if (h != null) return h;
            double best = Double.NaN, bestD = MATCH_METERS;
            double kx = 111_320.0 * Math.cos(Math.toRadians(lat));
            int bx = (int) Math.floor(lon / 0.0005), bz = (int) Math.floor(lat / 0.0005);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<int[]> bucket = grid.get(bucketKey(bz + dz, bx + dx));
                    if (bucket == null) continue;
                    for (int[] ref : bucket) {
                        double[] p = points.get(ref[0]);
                        double d = Math.hypot((p[1] - lon) * kx, (p[0] - lat) * 111_320.0);
                        if (d < bestD) {
                            bestD = d;
                            best = p[2];
                        }
                    }
                }
            }
            return best;
        }

        /**
         * The tallest atlas building whose centre lies inside the outline (lat, lon rings), or NaN. For buildings too
         * big for the 20 m centre match: a sports hall's centre can be 40 m from the atlas point of its own roof.
         */
        public double heightWithin(List<List<LatLon>> rings) {
            double s = 90, w = 180, n = -90, e = -180;
            for (List<LatLon> ring : rings) {
                for (LatLon p : ring) {
                    s = Math.min(s, p.lat());
                    n = Math.max(n, p.lat());
                    w = Math.min(w, p.lon());
                    e = Math.max(e, p.lon());
                }
            }
            if (s > n || (n - s) * (e - w) > 1e-4) return Double.NaN; // nothing, or larger than a few hundred metres
            double best = Double.NaN;
            for (int bz = (int) Math.floor(s / 0.0005); bz <= (int) Math.floor(n / 0.0005); bz++) {
                for (int bx = (int) Math.floor(w / 0.0005); bx <= (int) Math.floor(e / 0.0005); bx++) {
                    List<int[]> bucket = grid.get(bucketKey(bz, bx));
                    if (bucket == null) continue;
                    for (int[] ref : bucket) {
                        double[] p = points.get(ref[0]);
                        if (p[0] < s || p[0] > n || p[1] < w || p[1] > e || !inside(rings, p[0], p[1])) continue;
                        if (Double.isNaN(best) || p[2] > best) best = p[2];
                    }
                }
            }
            return best;
        }

        private static boolean inside(List<List<LatLon>> rings, double lat, double lon) {
            boolean in = false;
            for (List<LatLon> ring : rings) {
                for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
                    LatLon a = ring.get(i), b = ring.get(j);
                    if ((a.lat() > lat) != (b.lat() > lat) && lon < (b.lon() - a.lon()) * (lat - a.lat()) / (b.lat() - a.lat()) + a.lon()) in = !in;
                }
            }
            return in;
        }

        void add(long id, double lat, double lon, float h) {
            if (id != 0) byId.put(id, h);
            int index = points.size();
            points.add(new double[]{lat, lon, h});
            grid.computeIfAbsent(bucketKey((int) Math.floor(lat / 0.0005), (int) Math.floor(lon / 0.0005)), k -> new ArrayList<>()).add(new int[]{index});
            size++;
        }

        private static long bucketKey(int bz, int bx) {
            return ((long) bz << 32) ^ (bx & 0xFFFFFFFFL);
        }
    }

    /** Everything the store has inside the box. */
    public Loaded load(double south, double west, double north, double east) {
        Loaded out = new Loaded();
        if (isEmpty()) return out;
        int la0 = (int) Math.floor(south / CELL_DEG), la1 = (int) Math.floor(north / CELL_DEG);
        int lo0 = (int) Math.floor(west / CELL_DEG), lo1 = (int) Math.floor(east / CELL_DEG);
        for (int la = la0; la <= la1; la++) {
            for (int lo = lo0; lo <= lo1; lo++) {
                Cell c = cell(la, lo);
                for (int i = 0; i < c.ids.length; i++) {
                    if (c.lats[i] < south || c.lats[i] > north || c.lons[i] < west || c.lons[i] > east) continue;
                    out.add(c.ids[i], c.lats[i], c.lons[i], c.heights[i]);
                }
            }
        }
        return out;
    }

    private Cell cell(int la, int lo) {
        String name = "h_" + la + "_" + lo + ".jsonl.gz";
        Cell cached = cells.get(name);
        if (cached != null) return cached;
        Path file = root.resolve(name);
        Cell cell = Cell.EMPTY;
        if (Files.exists(file)) {
            List<long[]> ids = new ArrayList<>();
            List<double[]> vals = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int a = line.indexOf('['), b = line.lastIndexOf(']');
                    if (a < 0 || b < a) continue;
                    String[] parts = line.substring(a + 1, b).split(",");
                    if (parts.length < 4) continue;
                    try {
                        ids.add(new long[]{Long.parseLong(parts[0].trim())});
                        vals.add(new double[]{Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])});
                    } catch (NumberFormatException ignored) {
                        // a malformed line: skip it
                    }
                }
                long[] idArr = new long[ids.size()];
                double[] lats = new double[ids.size()], lons = new double[ids.size()];
                float[] hs = new float[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    idArr[i] = ids.get(i)[0];
                    lats[i] = vals.get(i)[0];
                    lons[i] = vals.get(i)[1];
                    hs[i] = (float) vals.get(i)[2];
                }
                cell = new Cell(idArr, lats, lons, hs);
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Height cell " + name + " unreadable: " + e);
            }
        }
        cells.put(name, cell);
        return cell;
    }
}
