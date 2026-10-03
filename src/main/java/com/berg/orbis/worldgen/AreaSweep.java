package com.berg.orbis.worldgen;

import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.osm.CoordinateMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Walks an area's outline chunk row by chunk row from north to south, like a
 * scanner: for each row it yields the chunk-X ranges that lie inside the
 * polygons (even-odd rule on three scan lines per row, so a row never misses
 * a thin fjord arm), each range widened by one chunk so coasts are complete.
 */
public final class AreaSweep {

    private final List<double[][]> polys; // block coordinates: [i] = {x, z}
    private final int firstRow, lastRow;
    /**
     * The edges, bucketed by the bands of Z they span, so a scan line only tests the few edges near it. Without
     * this a country at 1:1 (Russia: 280,000 chunk rows, 16,000 edges) took half an hour per pass over its rows.
     */
    private final double[] ex0, ez0, ex1, ez1;
    private final double bandMinZ, bandSize;
    private final int[][] bands;
    /** A drawn selection's chunks, swept exactly as selected (no widening); null for an outline. */
    private final ChunkSelection selection;

    /** A sweep over exactly the chunks of a selection drawn on the world map; {@code outline} is only drawn. */
    public AreaSweep(ChunkSelection selection, List<double[][]> outline) {
        this.polys = outline;
        this.selection = selection;
        this.firstRow = selection.firstRow();
        this.lastRow = selection.lastRow();
        ex0 = ez0 = ex1 = ez1 = new double[0];
        bandMinZ = 0;
        bandSize = 1;
        bands = new int[0][];
    }

    public AreaSweep(List<double[][]> polysInBlocks) {
        this.polys = polysInBlocks;
        this.selection = null;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[][] poly : polys) {
            for (double[] p : poly) {
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
        }
        this.firstRow = polys.isEmpty() ? 0 : (int) Math.floor(minZ / 16.0) - 1;
        this.lastRow = polys.isEmpty() ? -1 : (int) Math.floor(maxZ / 16.0) + 1;

        int edges = 0;
        for (double[][] poly : polys) edges += poly.length;
        ex0 = new double[edges];
        ez0 = new double[edges];
        ex1 = new double[edges];
        ez1 = new double[edges];
        int n = 0;
        for (double[][] poly : polys) {
            for (int i = 0; i < poly.length; i++) {
                double[] p = poly[i], q = poly[(i + 1) % poly.length];
                if (p[1] == q[1]) continue; // horizontal: never crossed under the half-open rule
                ex0[n] = p[0];
                ez0[n] = p[1];
                ex1[n] = q[0];
                ez1[n] = q[1];
                n++;
            }
        }
        double span = polys.isEmpty() ? 1 : Math.max(1, maxZ - minZ);
        bandMinZ = polys.isEmpty() ? 0 : minZ;
        bandSize = Math.max(256, span / 200_000);
        int nb = (int) Math.min(200_001, Math.floor(span / bandSize) + 1);
        int[] count = new int[nb];
        for (int e = 0; e < n; e++) {
            int b0 = band(Math.min(ez0[e], ez1[e]), nb), b1 = band(Math.max(ez0[e], ez1[e]), nb);
            for (int b = b0; b <= b1; b++) count[b]++;
        }
        bands = new int[nb][];
        for (int b = 0; b < nb; b++) bands[b] = new int[count[b]];
        int[] fill = new int[nb];
        for (int e = 0; e < n; e++) {
            int b0 = band(Math.min(ez0[e], ez1[e]), nb), b1 = band(Math.max(ez0[e], ez1[e]), nb);
            for (int b = b0; b <= b1; b++) bands[b][fill[b]++] = e;
        }
    }

    private int band(double z, int nb) {
        return (int) Math.max(0, Math.min(nb - 1, Math.floor((z - bandMinZ) / bandSize)));
    }

    public static AreaSweep of(AreaOutline outline, CoordinateMapper mapper) {
        List<double[][]> projected = new ArrayList<>(outline.polygons().size());
        for (double[][] poly : outline.polygons()) {
            double[][] p = new double[poly.length][];
            for (int i = 0; i < poly.length; i++) p[i] = mapper.toBlockExact(poly[i][0], poly[i][1]);
            projected.add(p);
        }
        return new AreaSweep(projected);
    }

    /** The outline polygons in block coordinates ({x, z} per vertex). */
    public List<double[][]> polygons() {
        return polys;
    }

    /** Northernmost chunk row (smallest Z). */
    public int firstRow() {
        return firstRow;
    }

    public int lastRow() {
        return lastRow;
    }

    public int rowCount() {
        return Math.max(0, lastRow - firstRow + 1);
    }

    /** Inclusive chunk-X ranges of row {@code cz}, sorted and merged. */
    public List<int[]> rowRanges(int cz) {
        if (selection != null) return selection.runs(cz);
        List<int[]> ranges = new ArrayList<>();
        for (int sample = 2; sample <= 14; sample += 6) {
            double z = cz * 16 + sample + 0.5;
            double[] xs = crossings(z);
            for (int i = 0; i + 1 < xs.length; i += 2) {
                int c0 = (int) Math.floor(xs[i] / 16.0) - 1;
                int c1 = (int) Math.floor(xs[i + 1] / 16.0) + 1;
                ranges.add(new int[]{c0, c1});
            }
        }
        if (ranges.isEmpty()) return ranges;
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (r[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], r[1]);
            } else {
                merged.add(cur);
                cur = r;
            }
        }
        merged.add(cur);
        return merged;
    }

    /** Sorted X coordinates where the scan line at z crosses a polygon edge (even count: inside between pairs). */
    private double[] crossings(double z) {
        double[] xs = new double[64];
        int n = 0;
        if (bands.length == 0 || z < bandMinZ - 1 || z > bandMinZ + bands.length * bandSize + 1) return new double[0];
        for (int e : bands[band(z, bands.length)]) {
            if ((ez0[e] <= z) == (ez1[e] <= z)) continue; // both above or both below: no crossing (half-open rule)
            double x = ex0[e] + (z - ez0[e]) * (ex1[e] - ex0[e]) / (ez1[e] - ez0[e]);
            if (n == xs.length) xs = Arrays.copyOf(xs, n * 2);
            xs[n++] = x;
        }
        double[] out = Arrays.copyOf(xs, n & ~1);
        if (out.length == 2) {
            if (out[0] > out[1]) {
                double t = out[0];
                out[0] = out[1];
                out[1] = t;
            }
        } else if (out.length > 2) {
            Arrays.sort(out);
        }
        return out;
    }

    /** Chunks inside the outline (before any sea skipping). */
    public long estimateChunks() {
        long total = 0;
        for (int cz = firstRow; cz <= lastRow; cz++) {
            for (int[] r : rowRanges(cz)) total += r[1] - r[0] + 1;
        }
        return total;
    }
}
