package com.berg.orbis.dem;

/**
 * Samples smooth elevation at any lat/lon using bicubic (Catmull-Rom)
 * interpolation over a 4x4 neighbourhood of DEM pixels, instead of snapping
 * to the nearest raw pixel. This is the single biggest fix for the "blocky
 * staircase terrain" look: nearest-neighbour sampling reproduces the DEM's
 * native grid spacing (~30 m) as visible terrain steps; bicubic produces
 * continuous slopes so terrain reads as natural at 1-block resolution.
 */
public class BicubicElevationSampler {

    private final TileSource tiles;
    private final int zoom;
    /**
     * The last few tiles this thread used. A sample reads 16 pixels, nearly always from one tile; each read asked
     * the provider, which built a text key and took a global lock (2% of generation plus the garbage). Only tiles
     * that were delivered are remembered, so a missing or failed tile is still asked for every time.
     */
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);

    private static final class Memo {
        final long[] keys = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        final float[][][] tiles = new float[4][][];
        int next;
    }

    public BicubicElevationSampler(TileSource tiles, int zoom) {
        this.tiles = tiles;
        this.zoom = zoom;
    }

    /** Interpolated elevation in metres, or NaN if any neighbouring sample is missing. */
    public double sampleMeters(double lat, double lon) {
        double[] frac = DemTileProvider.latLonToTileFraction(lat, lon, zoom);
        int tileX = (int) Math.floor(frac[0]);
        int tileY = (int) Math.floor(frac[1]);

        Memo m = memo.get();
        float[][] home = tile(m, tileX, tileY);
        int tileSize = home.length; // square tiles (256)

        double localX = (frac[0] - tileX) * tileSize;
        double localY = (frac[1] - tileY) * tileSize;

        int ix = (int) Math.floor(localX);
        int iy = (int) Math.floor(localY);
        double fx = localX - ix;
        double fy = localY - iy;

        double[] rows = new double[4];
        for (int j = -1; j <= 2; j++) {
            double[] samples = new double[4];
            for (int i = -1; i <= 2; i++) {
                double v = sampleAcrossTiles(m, tileX, tileY, home, tileSize, ix + i, iy + j);
                if (Double.isNaN(v)) return Double.NaN;
                samples[i + 1] = v;
            }
            rows[j + 1] = cubicInterpolate(samples, fx);
        }
        return cubicInterpolate(rows, fy);
    }

    /**
     * Samples pixel (x, y) relative to (tileX, tileY), stitching into the
     * neighbouring tile when the 4x4 neighbourhood spills past this tile's
     * edge. Without this every tile boundary shows up as a flat seam.
     */
    private float[][] tile(Memo m, int x, int y) {
        long key = ((long) x << 32) | (y & 0xffffffffL);
        for (int i = 0; i < 4; i++) if (m.keys[i] == key) return m.tiles[i];
        float[][] t = tiles.getTile(zoom, x, y);
        int i = m.next;
        m.keys[i] = key;
        m.tiles[i] = t;
        m.next = (i + 1) & 3;
        return t;
    }

    private double sampleAcrossTiles(Memo m, int tileX, int tileY, float[][] home, int size, int x, int y) {
        int tileDx = Math.floorDiv(x, size);
        int tileDy = Math.floorDiv(y, size);
        int px = Math.floorMod(x, size);
        int py = Math.floorMod(y, size);
        float[][] tile = (tileDx == 0 && tileDy == 0) ? home : tile(m, tileX + tileDx, tileY + tileDy);
        return tile[py][px];
    }

    /** Catmull-Rom cubic interpolation through 4 evenly spaced points at parameter t in [0,1]. */
    private static double cubicInterpolate(double[] p, double t) {
        return p[1] + 0.5 * t * (p[2] - p[0]
                + t * (2.0 * p[0] - 5.0 * p[1] + 4.0 * p[2] - p[3]
                + t * (3.0 * (p[1] - p[2]) + p[3] - p[0])));
    }
}
