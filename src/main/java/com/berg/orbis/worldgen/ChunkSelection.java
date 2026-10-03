package com.berg.orbis.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A set of chunks drawn on the world map: rectangles, ellipses and lasso outlines, each added to or cut out of
 * what came before, in order (as in an image editor). Kept as sorted chunk-X runs per chunk row, so adding and
 * cutting are interval operations and a sweep reads the runs directly. The world map builds one for its preview
 * and the server the same one from the same shapes, so what is shown is what is generated.
 * <p>
 * A chunk belongs to a shape when its centre lies inside the outline (even-odd rule); a shape too small to hold
 * any chunk centre still takes the chunk under its middle.
 */
public final class ChunkSelection {

    /** One drawn outline in block coordinates (x0, z0, x1, z1, ...), added or cut out. */
    public record Shape(boolean subtract, int[] xz) {
        public int vertices() {
            return xz.length / 2;
        }
    }

    /** Row (chunk Z) -> sorted, disjoint, inclusive chunk-X runs {a0, b0, a1, b1, ...}. */
    private final TreeMap<Integer, int[]> rows = new TreeMap<>();

    /** The selection drawn on the world generator map (lat/lon outlines in the settings), in this world's chunks. */
    public static ChunkSelection ofSettings(List<com.berg.orbis.config.OrbisConfig.PregenShape> shapes, com.berg.orbis.osm.CoordinateMapper mapper) {
        List<Shape> list = new ArrayList<>();
        if (shapes != null) {
            for (com.berg.orbis.config.OrbisConfig.PregenShape s : shapes) {
                if (s == null || s.latLon == null || s.latLon.length < 3) continue;
                int[] xz = new int[s.latLon.length * 2];
                for (int i = 0; i < s.latLon.length; i++) {
                    double[] b = mapper.toBlockExact(s.latLon[i][0], s.latLon[i][1]);
                    xz[2 * i] = (int) Math.round(b[0]);
                    xz[2 * i + 1] = (int) Math.round(b[1]);
                }
                list.add(new Shape(s.subtract, xz));
            }
        }
        return of(list);
    }

    public static ChunkSelection of(List<Shape> shapes) {
        ChunkSelection s = new ChunkSelection();
        for (Shape shape : shapes) s.apply(shape);
        return s;
    }

    public void apply(Shape shape) {
        int n = shape.vertices();
        if (n < 3) return;
        int[] v = shape.xz();
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long sx = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            minZ = Math.min(minZ, v[2 * i + 1]);
            maxZ = Math.max(maxZ, v[2 * i + 1]);
            sx += v[2 * i];
            sz += v[2 * i + 1];
        }
        boolean any = false;
        double[] xs = new double[16];
        for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) {
            double z = cz * 16 + 8.0; // the chunk centres of this row
            int k = 0;
            for (int i = 0; i < n; i++) {
                double x0 = v[2 * i], z0 = v[2 * i + 1];
                double x1 = v[2 * ((i + 1) % n)], z1 = v[2 * ((i + 1) % n) + 1];
                if ((z0 <= z) == (z1 <= z)) continue; // half-open: never on a vertex twice
                if (k == xs.length) xs = Arrays.copyOf(xs, k * 2);
                xs[k++] = x0 + (z - z0) * (x1 - x0) / (z1 - z0);
            }
            if (k < 2) continue;
            Arrays.sort(xs, 0, k);
            int[] runs = new int[k];
            int m = 0;
            for (int i = 0; i + 1 < k; i += 2) {
                // Chunks whose centre x (cx * 16 + 8) lies in [xs[i], xs[i + 1]).
                int a = (int) Math.ceil((xs[i] - 8) / 16.0);
                int b = (int) Math.ceil((xs[i + 1] - 8) / 16.0) - 1;
                if (a > b) continue;
                runs[m++] = a;
                runs[m++] = b;
            }
            if (m == 0) continue;
            any = true;
            int[] r = Arrays.copyOf(runs, m);
            if (shape.subtract()) cut(cz, r);
            else add(cz, r);
        }
        if (!any) {
            int cx = Math.floorDiv((int) (sx / n), 16), cz = Math.floorDiv((int) (sz / n), 16);
            if (shape.subtract()) cut(cz, new int[]{cx, cx});
            else add(cz, new int[]{cx, cx});
        }
    }

    private void add(int cz, int[] runs) {
        int[] cur = rows.get(cz);
        rows.put(cz, cur == null ? runs : union(cur, runs));
    }

    private void cut(int cz, int[] runs) {
        int[] cur = rows.get(cz);
        if (cur == null) return;
        int[] left = difference(cur, runs);
        if (left.length == 0) rows.remove(cz);
        else rows.put(cz, left);
    }

    /** Union of two run lists (each sorted and disjoint). */
    public static int[] union(int[] a, int[] b) {
        int[] all = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length || j < b.length) {
            if (j >= b.length || (i < a.length && a[i] <= b[j])) {
                all[k++] = a[i++];
                all[k++] = a[i++];
            } else {
                all[k++] = b[j++];
                all[k++] = b[j++];
            }
        }
        int[] out = new int[all.length];
        int m = 0;
        for (int p = 0; p < k; p += 2) {
            if (m > 0 && all[p] <= out[m - 1] + 1) out[m - 1] = Math.max(out[m - 1], all[p + 1]);
            else {
                out[m++] = all[p];
                out[m++] = all[p + 1];
            }
        }
        return Arrays.copyOf(out, m);
    }

    /** a minus b (each sorted and disjoint). */
    public static int[] difference(int[] a, int[] b) {
        List<Integer> out = new ArrayList<>();
        int j = 0;
        for (int i = 0; i < a.length; i += 2) {
            int s = a[i], e = a[i + 1];
            while (j < b.length && b[j + 1] < s) j += 2;
            int t = j;
            while (s <= e) {
                if (t >= b.length || b[t] > e) {
                    out.add(s);
                    out.add(e);
                    break;
                }
                if (b[t] > s) {
                    out.add(s);
                    out.add(b[t] - 1);
                }
                s = Math.max(s, b[t + 1] + 1);
                t += 2;
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Adds every chunk of another selection. */
    public void addAll(ChunkSelection other) {
        for (Map.Entry<Integer, int[]> e : other.rows.entrySet()) add(e.getKey(), e.getValue().clone());
    }

    /** Adds a row's runs {a0, b0, a1, b1, ...} (sorted, disjoint, inclusive). */
    public void addRow(int cz, int[] runs) {
        if (runs.length > 0) add(cz, runs.clone());
    }

    /** True when any chunk of the rectangle (inclusive chunk coordinates) is selected. */
    public boolean intersects(int cx0, int cz0, int cx1, int cz1) {
        for (int[] r : rows.subMap(cz0, true, cz1, true).values()) {
            for (int i = 0; i < r.length; i += 2) {
                if (r[i + 1] >= cx0 && r[i] <= cx1) return true;
            }
        }
        return false;
    }

    /** The selected chunk nearest to (cx, cz), or null when nothing is selected. */
    public int[] nearest(int cx, int cz) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
            long dz = e.getKey() - cz;
            if (dz * dz >= bestD) continue;
            int[] r = e.getValue();
            for (int i = 0; i < r.length; i += 2) {
                int x = Math.max(r[i], Math.min(r[i + 1], cx));
                long d = (long) (x - cx) * (x - cx) + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{x, e.getKey()};
                }
            }
        }
        return best;
    }

    /** {"rows": {"cz": [a0, b0, ...], ...}} */
    public String toJson() {
        com.google.gson.JsonObject rowsJson = new com.google.gson.JsonObject();
        for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (int v : e.getValue()) a.add(v);
            rowsJson.add(String.valueOf(e.getKey()), a);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("rows", rowsJson);
        return root.toString();
    }

    public static ChunkSelection fromJson(String json) {
        ChunkSelection s = new ChunkSelection();
        com.google.gson.JsonObject rowsJson = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("rows");
        for (Map.Entry<String, com.google.gson.JsonElement> e : rowsJson.entrySet()) {
            com.google.gson.JsonArray a = e.getValue().getAsJsonArray();
            int[] r = new int[a.size() & ~1];
            for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
            s.addRow(Integer.parseInt(e.getKey()), r);
        }
        return s;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int firstRow() {
        return rows.isEmpty() ? 0 : rows.firstKey();
    }

    public int lastRow() {
        return rows.isEmpty() ? -1 : rows.lastKey();
    }

    /** The runs of a row as {a, b} pairs (inclusive), west to east; empty when the row has none. */
    public List<int[]> runs(int cz) {
        int[] r = rows.get(cz);
        if (r == null) return List.of();
        List<int[]> out = new ArrayList<>(r.length / 2);
        for (int i = 0; i < r.length; i += 2) out.add(new int[]{r[i], r[i + 1]});
        return out;
    }

    /** The raw runs of a row {a0, b0, a1, b1, ...}, or null. Do not modify. */
    public int[] rawRuns(int cz) {
        return rows.get(cz);
    }

    public boolean contains(int cx, int cz) {
        int[] r = rows.get(cz);
        if (r == null) return false;
        for (int i = 0; i < r.length; i += 2) {
            if (cx < r[i]) return false;
            if (cx <= r[i + 1]) return true;
        }
        return false;
    }

    public long count() {
        long n = 0;
        for (int[] r : rows.values()) {
            for (int i = 0; i < r.length; i += 2) n += r[i + 1] - r[i] + 1;
        }
        return n;
    }

    /** West and east chunk X over all rows, or null when empty. */
    public int[] xRange() {
        if (rows.isEmpty()) return null;
        int a = Integer.MAX_VALUE, b = Integer.MIN_VALUE;
        for (int[] r : rows.values()) {
            a = Math.min(a, r[0]);
            b = Math.max(b, r[r.length - 1]);
        }
        return new int[]{a, b};
    }

    // ------------------------------------------------------------------ growing, shrinking, outlines

    /** The chunks within {@code k} chunks of the selection (a round brush, so corners grow round). */
    public ChunkSelection grown(int k) {
        ChunkSelection out = new ChunkSelection();
        if (rows.isEmpty() || k <= 0) {
            out.addAll(this);
            return out;
        }
        int[] reach = reach(k);
        for (int z = firstRow() - k; z <= lastRow() + k; z++) {
            int[] acc = new int[0];
            for (int dz = -k; dz <= k; dz++) {
                int[] r = rows.get(z + dz);
                if (r == null) continue;
                int w = reach[Math.abs(dz)];
                int[] g = new int[r.length];
                for (int i = 0; i < r.length; i += 2) {
                    g[i] = r[i] - w;
                    g[i + 1] = r[i + 1] + w;
                }
                acc = union(acc, union(g, new int[0]));
            }
            if (acc.length > 0) out.rows.put(z, acc);
        }
        return out;
    }

    /** The chunks whose whole round neighbourhood of {@code k} chunks is selected. */
    public ChunkSelection shrunk(int k) {
        ChunkSelection out = new ChunkSelection();
        if (rows.isEmpty() || k <= 0) {
            out.addAll(this);
            return out;
        }
        int[] reach = reach(k);
        for (int z : rows.keySet()) {
            int[] acc = null;
            for (int dz = -k; dz <= k && (acc == null || acc.length > 0); dz++) {
                int[] r = rows.get(z + dz);
                if (r == null) {
                    acc = new int[0];
                    break;
                }
                int w = reach[Math.abs(dz)];
                int[] s = new int[r.length];
                int m = 0;
                for (int i = 0; i < r.length; i += 2) {
                    if (r[i] + w > r[i + 1] - w) continue;
                    s[m++] = r[i] + w;
                    s[m++] = r[i + 1] - w;
                }
                s = Arrays.copyOf(s, m);
                acc = acc == null ? s : difference(acc, difference(acc, s));
            }
            if (acc != null && acc.length > 0) out.rows.put(z, acc);
        }
        return out;
    }

    /** Half-widths of a disc of radius k, row by row out from the middle. */
    private static int[] reach(int k) {
        int[] w = new int[k + 1];
        for (int dz = 0; dz <= k; dz++) w[dz] = (int) Math.floor(Math.sqrt((double) k * k - (double) dz * dz) + 1e-9);
        return w;
    }

    /**
     * The selection as outlines along chunk edges, in block coordinates: outer edges as added shapes, holes as cut
     * ones, largest first so a hole is cut after its outline and an island in a hole added after the hole. The
     * shapes select exactly these chunks again (every chunk centre lies inside its outline).
     */
    public List<Shape> outlines() {
        // Directed edges in chunk-corner units with the selection on their left (Z grows southwards).
        List<int[]> edges = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
            int z = e.getKey();
            int[] r = e.getValue();
            int[] above = rows.get(z - 1), below = rows.get(z + 1);
            int[] top = above == null ? r : difference(r, above);
            for (int i = 0; i < top.length; i += 2) edges.add(new int[]{top[i + 1] + 1, z, top[i], z});
            int[] bottom = below == null ? r : difference(r, below);
            for (int i = 0; i < bottom.length; i += 2) edges.add(new int[]{bottom[i], z + 1, bottom[i + 1] + 1, z + 1});
            for (int i = 0; i < r.length; i += 2) {
                edges.add(new int[]{r[i], z, r[i], z + 1});
                edges.add(new int[]{r[i + 1] + 1, z + 1, r[i + 1] + 1, z});
            }
        }
        Map<Long, List<Integer>> from = new java.util.HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            int[] ed = edges.get(i);
            from.computeIfAbsent(key(ed[0], ed[1]), k -> new ArrayList<>(2)).add(i);
        }
        boolean[] used = new boolean[edges.size()];
        List<int[]> loops = new ArrayList<>();
        List<Double> areas = new ArrayList<>();
        for (int start = 0; start < edges.size(); start++) {
            if (used[start]) continue;
            List<int[]> pts = new ArrayList<>();
            int cur = start;
            while (cur >= 0 && !used[cur]) {
                used[cur] = true;
                int[] ed = edges.get(cur);
                pts.add(new int[]{ed[0], ed[1]});
                int dx = Integer.signum(ed[2] - ed[0]), dz = Integer.signum(ed[3] - ed[1]);
                int next = -1, bestTurn = Integer.MAX_VALUE;
                for (int c : from.getOrDefault(key(ed[2], ed[3]), List.of())) {
                    if (used[c]) continue;
                    int[] ce = edges.get(c);
                    // Where two outlines touch at a corner, turn left: each keeps to its own chunks.
                    int turn = dx * Integer.signum(ce[3] - ce[1]) - dz * Integer.signum(ce[2] - ce[0]);
                    if (turn < bestTurn) {
                        bestTurn = turn;
                        next = c;
                    }
                }
                cur = next;
            }
            // Drop the corners in the middle of straight runs.
            List<int[]> corners = new ArrayList<>();
            int n = pts.size();
            for (int i = 0; i < n; i++) {
                int[] p = pts.get((i + n - 1) % n), c = pts.get(i), q = pts.get((i + 1) % n);
                long cross = (long) (c[0] - p[0]) * (q[1] - c[1]) - (long) (c[1] - p[1]) * (q[0] - c[0]);
                if (cross != 0) corners.add(c);
            }
            if (corners.size() < 3) continue;
            int[] xz = new int[corners.size() * 2];
            double area = 0;
            for (int i = 0; i < corners.size(); i++) {
                int[] c = corners.get(i), q = corners.get((i + 1) % corners.size());
                xz[2 * i] = c[0] * 16;
                xz[2 * i + 1] = c[1] * 16;
                area += (double) c[0] * q[1] - (double) q[0] * c[1];
            }
            loops.add(xz);
            areas.add(area / 2);
        }
        Integer[] order = new Integer[loops.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(Math.abs(areas.get(b)), Math.abs(areas.get(a))));
        List<Shape> out = new ArrayList<>();
        // With the selection on the left of every edge, an outer outline winds negative here and a hole positive.
        for (int i : order) out.add(new Shape(areas.get(i) > 0, loops.get(i)));
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** A fingerprint of exactly which chunks are selected, so a stopped sweep resumes only for the same selection. */
    public String fingerprint() {
        long h = 0xcbf29ce484222325L;
        for (Iterator<Map.Entry<Integer, int[]>> it = rows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, int[]> e = it.next();
            h = (h ^ e.getKey()) * 0x100000001b3L;
            for (int x : e.getValue()) h = (h ^ x) * 0x100000001b3L;
        }
        return Long.toHexString(h);
    }
}
