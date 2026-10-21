package com.berg.orbis.water;

import com.berg.orbis.osm.LatLon;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The real width of rivers 30 m and wider, from GRWL (Global River Widths from Landsat, Allen & Pavelsky 2018, CC BY 4.0):
 * each stretch of about 20 km with its median width, simplified to 400 m and shipped with the mod
 * ({@code orbis-river-widths.bin.gz}, made by {@code tools/river_widths.py}), so every region knows them at once and
 * alike. An OSM river line without a width tag takes the width of the stretch it follows: most points along it within
 * 600 m of a stretch running the same way (a tributary near a big river runs another way, and keeps its own).
 */
public final class RiverWidths {
    public static final String ATTRIBUTION = "GRWL, Global River Widths from Landsat (Allen & Pavelsky 2018, CC BY 4.0)";

    private static final double CELL = 0.05;
    private static final double REACH_M = 600;
    private static final double MAX_BEARING_DEG = 40;

    private static volatile RiverWidths loaded;

    private final float[] lat, lon;
    /** For each point, its stretch's width in metres, or 0 for a stretch's last point (no edge starts there). */
    private final short[] width;
    /** The grid: the cells holding edges (sorted keys), where each one's edges start in {@link #edges}, and the edges. */
    private final long[] cellKeys;
    private final int[] cellStart;
    private final int[] edges;

    private RiverWidths(float[] lat, float[] lon, short[] width, long[] cellKeys, int[] cellStart, int[] edges) {
        this.lat = lat;
        this.lon = lon;
        this.width = width;
        this.cellKeys = cellKeys;
        this.cellStart = cellStart;
        this.edges = edges;
    }

    /** The widths shipped with the mod (read once), or null when the file is missing or damaged. */
    public static RiverWidths get() {
        RiverWidths r = loaded;
        if (r != null) return r;
        synchronized (RiverWidths.class) {
            if (loaded != null) return loaded;
            try (InputStream raw = RiverWidths.class.getResourceAsStream("/orbis-river-widths.bin.gz")) {
                if (raw == null) return null;
                loaded = read(new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw), 1 << 16)));
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] River widths unreadable: " + e);
                return null;
            }
            return loaded;
        }
    }

    private static RiverWidths read(DataInputStream in) throws IOException {
        byte[] head = new byte[20];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (h.get(0) != 'G' || h.get(1) != 'R' || h.get(2) != 'W' || h.get(3) != 'L' || h.getInt(4) != 1) throw new IOException("not a GRWL file");
        int stretches = h.getInt(8);
        double q = h.getDouble(12);
        float[] la = new float[1 << 20], lo = new float[1 << 20];
        short[] w = new short[1 << 20];
        int n = 0;
        for (int s = 0; s < stretches; s++) {
            int width = (int) varint(in), count = (int) varint(in);
            long x = 0, y = 0;
            for (int k = 0; k < count; k++) {
                x += zigzag(varint(in));
                y += zigzag(varint(in));
                if (n == la.length) {
                    la = Arrays.copyOf(la, n * 2);
                    lo = Arrays.copyOf(lo, n * 2);
                    w = Arrays.copyOf(w, n * 2);
                }
                la[n] = (float) (y * q);
                lo[n] = (float) (x * q);
                w[n] = k == count - 1 ? 0 : (short) Math.min(Short.MAX_VALUE, Math.max(1, width));
                n++;
            }
        }
        la = Arrays.copyOf(la, n);
        lo = Arrays.copyOf(lo, n);
        w = Arrays.copyOf(w, n);
        if (n >= 1 << 21) throw new IOException("too many points");
        // Each edge (point i to i + 1 of a stretch) in the cells its box touches, as (cell, edge) pairs in one sorted array
        // of longs (a map of lists took four times the memory).
        long[] pairs = new long[n + n / 2];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (w[i] == 0) continue;
            int cx0 = (int) Math.floor(Math.min(lo[i], lo[i + 1]) / CELL), cx1 = (int) Math.floor(Math.max(lo[i], lo[i + 1]) / CELL);
            int cy0 = (int) Math.floor(Math.min(la[i], la[i + 1]) / CELL), cy1 = (int) Math.floor(Math.max(la[i], la[i + 1]) / CELL);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cy = cy0; cy <= cy1; cy++) {
                    if (m == pairs.length) pairs = Arrays.copyOf(pairs, m * 2);
                    pairs[m++] = key(cx, cy) << 21 | i;
                }
            }
        }
        Arrays.sort(pairs, 0, m);
        int[] edges = new int[m];
        long[] keys = new long[m];
        int[] starts = new int[m + 1];
        int cells = 0;
        for (int k = 0; k < m; k++) {
            long key = pairs[k] >>> 21;
            edges[k] = (int) (pairs[k] & ((1 << 21) - 1));
            if (cells == 0 || keys[cells - 1] != key) {
                keys[cells] = key;
                starts[cells] = k;
                cells++;
            }
        }
        starts[cells] = m;
        return new RiverWidths(la, lo, w, Arrays.copyOf(keys, cells), Arrays.copyOf(starts, cells + 1), edges);
    }

    /** A cell's key: 0.05° cells, both indices offset to stay positive (42 bits in all). */
    private static long key(int cx, int cy) {
        return (long) (cx + 4000) << 21 | (cy + 2000);
    }

    private static long varint(DataInputStream in) throws IOException {
        long v = 0;
        int shift = 0;
        while (true) {
            int c = in.readUnsignedByte();
            v |= (long) (c & 0x7F) << shift;
            if (c < 0x80) return v;
            shift += 7;
        }
    }

    private static long zigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    /**
     * The width in metres of the river an OSM line follows, or NaN when it follows none (too few of its points near a
     * stretch running its way). The line is sampled along its whole length, so every region it crosses gets the same answer.
     */
    public double widthFor(List<LatLon> line) {
        if (line.size() < 2) return Double.NaN;
        double kx0 = 111_320 * Math.cos(Math.toRadians(line.get(0).lat())), ky = 110_574;
        double total = 0;
        for (int i = 1; i < line.size(); i++) total += dist(line.get(i - 1), line.get(i), kx0, ky);
        if (total < 50) return Double.NaN;
        int samples = (int) Math.max(3, Math.min(40, total / 100));
        double step = total / samples;
        List<Integer> found = new ArrayList<>();
        int seg = 0;
        double segStart = 0;
        for (int s = 0; s < samples; s++) {
            double at = (s + 0.5) * step;
            while (seg < line.size() - 2 && segStart + dist(line.get(seg), line.get(seg + 1), kx0, ky) < at) {
                segStart += dist(line.get(seg), line.get(seg + 1), kx0, ky);
                seg++;
            }
            LatLon a = line.get(seg), b = line.get(seg + 1);
            double len = Math.max(1e-9, dist(a, b, kx0, ky)), t = Math.max(0, Math.min(1, (at - segStart) / len));
            double plat = a.lat() + t * (b.lat() - a.lat()), plon = a.lon() + t * (b.lon() - a.lon());
            double kx = 111_320 * Math.cos(Math.toRadians(plat));
            double bearing = Math.atan2((b.lat() - a.lat()) * ky, (b.lon() - a.lon()) * kx);
            int w = nearest(plat, plon, bearing, kx, ky);
            if (w > 0) found.add(w);
        }
        if (found.size() * 2 < samples) return Double.NaN;
        found.sort(null);
        return found.get(found.size() / 2);
    }

    private static double dist(LatLon a, LatLon b, double kx, double ky) {
        double dx = (b.lon() - a.lon()) * kx, dy = (b.lat() - a.lat()) * ky;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** The width of the nearest stretch within reach running the same way (either direction), or 0. */
    private int nearest(double plat, double plon, double bearing, double kx, double ky) {
        double reachLat = REACH_M / ky, reachLon = REACH_M / kx;
        int cx0 = (int) Math.floor((plon - reachLon) / CELL), cx1 = (int) Math.floor((plon + reachLon) / CELL);
        int cy0 = (int) Math.floor((plat - reachLat) / CELL), cy1 = (int) Math.floor((plat + reachLat) / CELL);
        double best = REACH_M * REACH_M;
        int bestWidth = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                int c = Arrays.binarySearch(cellKeys, key(cx, cy));
                if (c < 0) continue;
                for (int e = cellStart[c]; e < cellStart[c + 1]; e++) {
                    int i = edges[e];
                    double ax = (lon[i] - plon) * kx, ay = (lat[i] - plat) * ky;
                    double bx = (lon[i + 1] - plon) * kx, by = (lat[i + 1] - plat) * ky;
                    double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                    double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                    double ex = ax + t * dx, ey = ay + t * dy, d2 = ex * ex + ey * ey;
                    if (d2 >= best) continue;
                    double diff = Math.abs(Math.toDegrees(Math.atan2(dy, dx) - bearing)) % 180;
                    if (Math.min(diff, 180 - diff) > MAX_BEARING_DEG) continue;
                    best = d2;
                    bestWidth = width[i];
                }
            }
        }
        return bestWidth;
    }
}
