package com.berg.orbis.client;

import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.worldgen.AreaSweep;

import java.util.List;

/**
 * What a pre-generation of one area would make, at the origin and scale being set up: the area's outline, the
 * sweep's own chunk rows (so the preview is exactly the sweep), and the totals shown next to the map. Plain Java
 * (no Minecraft classes), so the preview rasterizer can be tested outside the game.
 */
public final class PreviewPlan {
    /** Disk per generated chunk, measured on Bergen worlds at 1:1 and 1:2 (6.1 and 6.4 KB with the partial ring). */
    public static final double KB_PER_CHUNK = 6.3;
    /** Pre-generation speed measured on Bergen sweeps (20 to 25 chunks a second), for the time estimate. */
    public static final double CHUNKS_PER_SECOND = 22;

    public final AreaOutline outline;
    /** The radius in km for a circle around the origin, or null for a named area. */
    public final Double radiusKm;
    public final CoordinateMapper mapper;
    public final String projection;
    public final int firstRow;
    /** Per chunk row (firstRow + i): the generated chunk X ranges as flattened pairs [from0, to0, from1, to1, ...]. */
    public final int[][] rows;
    public final long chunks;
    public final long regionFiles;
    /** The generated chunks' extremes. */
    public final int minCx, maxCx;
    public final String command;
    /** The outline's bounding box in Web Mercator units (0..1 from the west and north edges). */
    public final double mx0, my0, mx1, my1;

    private PreviewPlan(AreaOutline outline, Double radiusKm, CoordinateMapper mapper, String projection, int firstRow, int[][] rows,
                        long chunks, long regionFiles, int minCx, int maxCx, String command) {
        this.outline = outline;
        this.radiusKm = radiusKm;
        this.mapper = mapper;
        this.projection = projection;
        this.firstRow = firstRow;
        this.rows = rows;
        this.chunks = chunks;
        this.regionFiles = regionFiles;
        this.minCx = minCx;
        this.maxCx = maxCx;
        this.command = command;
        this.mx0 = mercX(outline.west());
        this.mx1 = mercX(outline.east());
        this.my0 = mercY(outline.north());
        this.my1 = mercY(outline.south());
    }

    public static PreviewPlan of(AreaOutline outline, Double radiusKm, CoordinateMapper mapper, String projection) {
        AreaSweep sweep = AreaSweep.of(outline, mapper);
        int n = Math.max(0, sweep.lastRow() - sweep.firstRow() + 1);
        int[][] rows = new int[n][];
        // Rows are independent: all cores (a country at 1:1 has a quarter of a million of them).
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            List<int[]> ranges = sweep.rowRanges(sweep.firstRow() + i);
            int[] flat = new int[ranges.size() * 2];
            for (int k = 0; k < ranges.size(); k++) {
                flat[2 * k] = ranges.get(k)[0];
                flat[2 * k + 1] = ranges.get(k)[1];
            }
            rows[i] = flat;
        });
        long chunks = 0;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
        for (int[] r : rows) {
            for (int k = 0; k < r.length; k += 2) chunks += r[k + 1] - r[k] + 1;
            if (r.length > 0) {
                minCx = Math.min(minCx, r[0]);
                maxCx = Math.max(maxCx, r[r.length - 1]);
            }
        }
        long regionFiles = countRegions(sweep.firstRow(), rows);
        String command = radiusKm != null ? "/orbis pregen " + fmt(radiusKm) : "/orbis pregen area " + outline.name().split(",")[0].trim();
        return new PreviewPlan(outline, radiusKm, mapper, projection, sweep.firstRow(), rows, chunks, regionFiles, minCx, maxCx, command);
    }

    /**
     * Region files touched: per band of 32 chunk rows, the union of the rows' ranges in region columns. (A set of
     * every region file took gigabytes for a country at 1:1.)
     */
    private static long countRegions(int firstRow, int[][] rows) {
        long total = 0;
        if (rows.length == 0) return 0;
        int rz0 = Math.floorDiv(firstRow, 32), rz1 = Math.floorDiv(firstRow + rows.length - 1, 32);
        for (int rz = rz0; rz <= rz1; rz++) {
            int from = Math.max(0, rz * 32 - firstRow), to = Math.min(rows.length - 1, rz * 32 + 31 - firstRow);
            int count = 0;
            for (int i = from; i <= to; i++) count += rows[i].length / 2;
            long[][] iv = new long[count][];
            int k = 0;
            for (int i = from; i <= to; i++) {
                int[] r = rows[i];
                for (int j = 0; j < r.length; j += 2) iv[k++] = new long[]{Math.floorDiv(r[j], 32), Math.floorDiv(r[j + 1], 32)};
            }
            java.util.Arrays.sort(iv, (x, y) -> Long.compare(x[0], y[0]));
            boolean open = false;
            long curA = 0, curB = 0;
            for (long[] v : iv) {
                if (!open || v[0] > curB + 1) {
                    if (open) total += curB - curA + 1;
                    curA = v[0];
                    curB = v[1];
                    open = true;
                } else {
                    curB = Math.max(curB, v[1]);
                }
            }
            if (open) total += curB - curA + 1;
        }
        return total;
    }

    /** Minecraft cannot generate past 29,999,984 blocks from 0 on either axis. */
    public boolean beyondWorldEdge() {
        final long edge = 29_999_984L;
        return (long) minCx * 16 < -edge || ((long) maxCx + 1) * 16 > edge
                || (long) firstRow * 16 < -edge || ((long) firstRow + rows.length) * 16 > edge;
    }

    /** Whether the sweep generates chunk (cx, cz). */
    public boolean contains(int cx, int cz) {
        int i = cz - firstRow;
        if (i < 0 || i >= rows.length) return false;
        int[] r = rows[i];
        for (int k = 0; k < r.length; k += 2) {
            if (cx < r[k]) return false;
            if (cx <= r[k + 1]) return true;
        }
        return false;
    }

    /** East-west extent of the generated chunks, in blocks. */
    public long widthBlocks() {
        return maxCx < minCx ? 0 : ((long) maxCx - minCx + 1) * 16;
    }

    /** North-south extent of the generated chunks, in blocks. */
    public long heightBlocks() {
        return rows.length * 16L;
    }

    public String shortName() {
        String[] parts = outline.name().split(",");
        return parts.length > 1 ? parts[0].trim() + "," + parts[1] : parts[0].trim();
    }

    public double gigabytes() {
        return chunks * KB_PER_CHUNK / 1048576.0;
    }

    public double hours() {
        return chunks / CHUNKS_PER_SECOND / 3600.0;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ Web Mercator (the map tiles' projection)

    public static double mercX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    public static double mercY(double lat) {
        double l = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double r = Math.toRadians(l);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
    }

    public static double lonOf(double mx) {
        return mx * 360.0 - 180.0;
    }

    public static double latOf(double my) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * my))));
    }

    /** Web Mercator units per metre on the ground at a latitude. */
    public static double mercPerMeter(double lat) {
        return 1.0 / (40_075_016.686 * Math.cos(Math.toRadians(lat)));
    }
}
