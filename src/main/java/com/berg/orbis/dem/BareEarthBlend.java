package com.berg.orbis.dem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts {@link BareEarth} under the terrain tiles where Mapterhorn has no national survey, and keeps the survey exactly
 * where it has one. Mapterhorn serves zoom 13 and finer only over surveys (Copernicus alone stops at 12), so a zoom-13
 * tile on the server is the sign of a survey; Iceland, the Faroes and Latvia have 10-20 m surveys that stop at 12
 * too and are named here. Survey tiles pass unchanged. Elsewhere the tile becomes the bare-earth model, reached
 * gradually over {@link #BLEND_METRES} from the nearest survey tile, so the lidar and the model meet without a step.
 *
 * Applied to the world's own terrain zoom only, and only for worlds that ask for it: chunks generated on Copernicus
 * beside chunks on the bare-earth model would differ by metres at their border.
 */
public final class BareEarthBlend implements DemTileProvider.TileCorrection {

    /** The distance over which terrain goes from the survey to the bare-earth model. */
    public static final double BLEND_METRES = 400;
    private static final double EQUATOR_M = 40_075_016.686;
    /** Surveys Mapterhorn only serves to zoom 12 (south, west, north, east). */
    private static final double[][] LOW_RES_SURVEYS = {
            {63.0, -25.0, 67.0, -13.0},  // Iceland, 10 m
            {61.3, -7.8, 62.5, -6.1},    // Faroe Islands, 10 m
            {55.6, 20.9, 58.1, 28.3},    // Latvia, 20 m (its box takes in edges of its neighbours, which keep Copernicus)
    };

    private final DemTileProvider tiles;
    private final BareEarth model;
    private final Map<Long, Boolean> survey = new ConcurrentHashMap<>();
    private volatile boolean announced;

    public BareEarthBlend(DemTileProvider tiles, BareEarth model) {
        this.tiles = tiles;
        this.model = model;
    }

    @Override
    public float[][] correct(int zoom, int x, int y, float[][] tile) {
        int shift = zoom - 13;
        if (shift < 0) return tile; // coarse worlds: a few metres are less than a block there
        int ax = x >> shift, ay = y >> shift;
        if (survey(ax, ay)) return tile;
        int n = tile.length;
        double per = 1.0 / (1 << shift); // this tile's size in zoom-13 tiles
        double ox = x * per, oy = y * per;
        double latMid = lat(oy + per / 2, 13);
        double unitM = EQUATOR_M * Math.cos(Math.toRadians(latMid)) / 8192; // a zoom-13 tile's width here
        int rings = Math.max(1, Math.min(4, (int) Math.ceil(BLEND_METRES / unitM)));
        List<int[]> near = new ArrayList<>();
        for (int dy = -rings; dy <= rings; dy++) {
            int ty = ay + dy;
            if (ty < 0 || ty >= 8192) continue;
            for (int dx = -rings; dx <= rings; dx++) {
                if ((dx != 0 || dy != 0) && survey(Math.floorMod(ax + dx, 8192), ty)) near.add(new int[]{ax + dx, ty});
            }
        }
        double north = lat(y, zoom), south = lat(y + 1, zoom);
        double west = x / (double) (1 << zoom) * 360 - 180, east = (x + 1) / (double) (1 << zoom) * 360 - 180;
        BareEarth.Window win;
        try {
            win = model.window(south, west, north, east);
        } catch (IOException e) {
            throw new RuntimeException("bare-earth terrain unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
        float[][] out = new float[n][n];
        for (int j = 0; j < n; j++) {
            double lat = lat(y + (j + 0.5) / n, zoom);
            double py = oy + (j + 0.5) / n * per;
            for (int i = 0; i < n; i++) {
                double m = tile[j][i];
                // The sea and the shore at sea level stay as they are (the coast is drawn from them).
                if (Math.abs(m) <= 0.5) {
                    out[j][i] = (float) m;
                    continue;
                }
                double w = 1;
                if (!near.isEmpty()) {
                    double px = ox + (i + 0.5) / n * per, d = Double.MAX_VALUE;
                    for (int[] r : near) {
                        double ddx = Math.max(0, Math.max(r[0] - px, px - (r[0] + 1))), ddy = Math.max(0, Math.max(r[1] - py, py - (r[1] + 1)));
                        d = Math.min(d, Math.hypot(ddx, ddy));
                    }
                    double t = Math.min(1, d * unitM / BLEND_METRES);
                    w = t * t * (3 - 2 * t);
                }
                double g = w > 0 ? win.sample(lat, (west + (i + 0.5) / n * (east - west))) : Double.NaN;
                if (Double.isNaN(g)) {
                    out[j][i] = (float) m;
                    continue;
                }
                double v = m + w * (g - m);
                if (m > 0.5 && v < 0.51) v = 0.51; // land stays land
                out[j][i] = (float) v;
            }
        }
        if (!announced) {
            announced = true;
            System.out.println("[orbis] Bare-earth terrain is live here (no national survey): " + BareEarth.ATTRIBUTION);
        }
        return out;
    }

    /** Whether Mapterhorn has a national survey under this zoom-13 tile (asks the server once; the answer stays on disk). */
    private boolean survey(int x, int y) {
        long key = (long) y * 8192 + x;
        Boolean known = survey.get(key);
        if (known != null) return known;
        double lat = lat(y + 0.5, 13), lon = (x + 0.5) / 8192 * 360 - 180;
        boolean s = lowResSurvey(lat, lon) || tiles.hasNativeTile(13, x, y);
        survey.put(key, s);
        return s;
    }

    private static boolean lowResSurvey(double lat, double lon) {
        for (double[] b : LOW_RES_SURVEYS) if (lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3]) return true;
        return false;
    }

    /** Latitude of a (fractional) tile row. */
    private static double lat(double row, int zoom) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI - 2 * Math.PI * row / (1 << zoom))));
    }
}
