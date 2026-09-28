package com.berg.orbis.client;

/**
 * Draws the preview's overlay for one view of the map: which chunks the sweep generates (green), the chunk grid
 * when chunks are big enough to see, the region-file grid (amber), and the area's outline (red), as ARGB pixels
 * lined up with Web Mercator map tiles. Each pixel is mapped to its block through the world's own projection,
 * so the chunks sit exactly where the game will put them. Plain Java: runs off the render thread.
 */
public final class PreviewRaster {
    static final int FILL = 0x5222C55E;
    static final int DIM = 0x30000000;
    static final int CHUNK_LINE_IN = 0x9915803D;
    static final int CHUNK_LINE_OUT = 0x30000000;
    static final int REGION_LINE = 0xE6F59E0B, REGION_LINE_OUT = 0x80F59E0B;
    static final int OUTLINE = 0xFFEF4444;
    /** Chunk lines appear when a chunk is at least this many pixels across; region lines likewise. */
    static final double CHUNK_LINES_PX = 10, REGION_LINES_PX = 40;

    private PreviewRaster() {}

    /** Result of one render: the pixels, and how many pixels a chunk spans at the view's centre. */
    public record Result(int[] argb, int width, int height, double chunkPx) {}

    /**
     * Renders the overlay for a view centred on Web Mercator (cx, cy) at {@code scale} pixels per Mercator unit,
     * {@code w} x {@code h} pixels.
     */
    public static Result render(PreviewPlan plan, double cx, double cy, double scale, int w, int h) {
        final int step = 8;
        int gw = w / step + 2, gh = h / step + 2;
        double[] gbx = new double[gw * gh], gbz = new double[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            double my = cy + (gy * step - h / 2.0) / scale;
            double lat = PreviewPlan.latOf(Math.max(0, Math.min(1, my)));
            for (int gx = 0; gx < gw; gx++) {
                double lon = PreviewPlan.lonOf(cx + (gx * step - w / 2.0) / scale);
                double[] b = plan.mapper.toBlockExact(lat, lon);
                gbx[gy * gw + gx] = b[0];
                gbz[gy * gw + gx] = b[1];
            }
        }
        int cgx = Math.min(gw - 2, (w / 2) / step), cgy = Math.min(gh - 2, (h / 2) / step);
        int c = cgy * gw + cgx;
        double blocksPerPx = Math.hypot(gbx[c + 1] - gbx[c], gbz[c + 1] - gbz[c]) / step;
        double chunkPx = blocksPerPx > 0 ? 16 / blocksPerPx : 1e9;
        boolean chunkLines = chunkPx >= CHUNK_LINES_PX, regionLines = chunkPx * 32 >= REGION_LINES_PX;

        int[] out = new int[w * h];
        // Bands of rows in parallel; each band first works out the row above it, for the horizontal grid lines.
        int bands = Math.max(1, Math.min(h / 16, Runtime.getRuntime().availableProcessors() * 2));
        java.util.stream.IntStream.range(0, bands).parallel().forEach(band -> {
            int yStart = (int) ((long) h * band / bands), yEnd = (int) ((long) h * (band + 1) / bands);
            int[] upCz = new int[w];
            for (int y = Math.max(0, yStart - 1); y < yEnd; y++) {
                boolean write = y >= yStart;
                int gy = y / step;
                double fy = (y - gy * step) / (double) step;
                int leftCx = Integer.MIN_VALUE, leftCz = Integer.MIN_VALUE;
                boolean leftIn = false;
                for (int x = 0; x < w; x++) {
                    int gx = x / step;
                    double fx = (x - gx * step) / (double) step;
                    int i00 = gy * gw + gx;
                    double bx = lerp(lerp(gbx[i00], gbx[i00 + 1], fx), lerp(gbx[i00 + gw], gbx[i00 + gw + 1], fx), fy);
                    double bz = lerp(lerp(gbz[i00], gbz[i00 + 1], fx), lerp(gbz[i00 + gw], gbz[i00 + gw + 1], fx), fy);
                    int ccx = (int) Math.floor(bx / 16.0), ccz = (int) Math.floor(bz / 16.0);
                    if (write) {
                        boolean in = (ccx == leftCx && ccz == leftCz) ? leftIn : plan.contains(ccx, ccz);
                        int color = in ? FILL : DIM;
                        boolean edgeX = x > 0 && ccx != leftCx, edgeZ = y > 0 && ccz != upCz[x];
                        if (regionLines && ((edgeX && Math.floorDiv(ccx, 32) != Math.floorDiv(leftCx, 32))
                                || (edgeZ && Math.floorDiv(ccz, 32) != Math.floorDiv(upCz[x], 32)))) {
                            color = in ? REGION_LINE : REGION_LINE_OUT;
                        } else if (chunkLines && (edgeX || edgeZ)) {
                            color = in ? CHUNK_LINE_IN : CHUNK_LINE_OUT;
                        }
                        out[y * w + x] = color;
                        leftIn = in;
                    }
                    leftCx = ccx;
                    leftCz = ccz;
                    upCz[x] = ccz;
                }
            }
        });
        for (double[][] ring : plan.outline.polygons()) {
            int n = ring.length;
            if (n < 2) continue;
            double px = Double.NaN, py = Double.NaN;
            for (int k = 0; k <= n; k++) {
                double[] p = ring[k % n];
                double sx = (PreviewPlan.mercX(p[1]) - cx) * scale + w / 2.0;
                double sy = (PreviewPlan.mercY(p[0]) - cy) * scale + h / 2.0;
                if (k > 0) line(out, w, h, px, py, sx, sy, OUTLINE);
                px = sx;
                py = sy;
            }
        }
        return new Result(out, w, h, chunkPx);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** A 2-pixel-wide line, clipped to the image. */
    private static void line(int[] out, int w, int h, double x0, double y0, double x1, double y1, int color) {
        if ((x0 < -2 && x1 < -2) || (y0 < -2 && y1 < -2) || (x0 > w + 2 && x1 > w + 2) || (y0 > h + 2 && y1 > h + 2)) return;
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps > 20_000) return;
        for (int s = 0; s <= steps; s++) {
            double t = steps == 0 ? 0 : s / (double) steps;
            int x = (int) Math.floor(x0 + dx * t - 0.5), y = (int) Math.floor(y0 + dy * t - 0.5);
            for (int oy = 0; oy < 2; oy++) {
                for (int ox = 0; ox < 2; ox++) {
                    int xx = x + ox, yy = y + oy;
                    if (xx >= 0 && yy >= 0 && xx < w && yy < h) out[yy * w + xx] = color;
                }
            }
        }
    }
}
