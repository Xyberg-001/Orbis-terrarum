package com.berg.orbis.dem;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.osm.CoordinateMapper;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a real elevation (metres above mean sea level) into a block Y.
 *
 * Minecraft's engine allows at most 4064 blocks between the bottom and the
 * top of a dimension (Y -2032..2031). Earth needs about 20 000 (Mariana
 * Trench to Everest), and even the 8 849 m of Everest alone do not fit, so a
 * pure 1:1 mapping has to give up somewhere. This class keeps the world
 * exactly 1:1 wherever people live and only bends the mapping where it has
 * to:
 *
 * <ul>
 *   <li><b>relative</b> (default): everything below {@code reliefKneeMeters}
 *   is 1:1 (sea level at {@code seaLevelY}). Above the knee the terrain is
 *   lowered by how far the <i>regional</i> average elevation (a ~25 km
 *   smoothed base, from the coarse global DEM) exceeds the knee, so
 *   plateaus and high valleys sink but every peak keeps its full local
 *   relief above them: Everest still rises 3 500 blocks over the Khumbu
 *   glacier. The remaining excess near the ceiling is squeezed by a smooth
 *   asymptotic curve so nothing ever flattens into a plateau.</li>
 *   <li><b>compress</b>: no regional shift, only the asymptotic squeeze near
 *   the ceiling and floor.</li>
 *   <li><b>clamp</b>: plain 1:1, cut off at the dimension limits (the old
 *   behaviour: Everest becomes a mesa).</li>
 * </ul>
 *
 * Everything that converts metres to Y goes through {@link #toY} so
 * terrain, water surfaces, bridge decks and building bases agree.
 */
public final class VerticalMapping {

    public enum Mode { RELATIVE, COMPRESS, CLAMP }

    private final OrbisConfig cfg;
    private final Mode mode;
    private final double kneeBlocks;
    private final double softRange;
    private final double ceilingKnee;   // blocks above sea level where the top squeeze starts
    private final double floorKnee;     // blocks below sea level where the bottom squeeze starts (positive number)
    private final RegionalBase base;

    public VerticalMapping(OrbisConfig cfg, CoordinateMapper mapper, TileSource coarseTiles) {
        this.cfg = cfg;
        Mode m;
        try {
            m = Mode.valueOf(cfg.verticalMode == null ? "RELATIVE" : cfg.verticalMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            m = Mode.RELATIVE;
        }
        this.mode = m;
        this.kneeBlocks = cfg.reliefKneeMeters / cfg.metersPerBlock;
        this.softRange = Math.max(50, cfg.softCeilingBlocks);
        double roomUp = (cfg.maxY() - 16) - cfg.seaLevelY;
        double roomDown = cfg.seaLevelY - (cfg.minY + 8);
        this.ceilingKnee = Math.max(0, roomUp - softRange);
        this.floorKnee = Math.max(0, roomDown - Math.min(softRange, 150));
        this.base = (m == Mode.RELATIVE && coarseTiles != null && mapper != null)
                ? new RegionalBase(coarseTiles, mapper, cfg.reliefSmoothingKm) : null;
    }

    public Mode mode() {
        return mode;
    }

    /** Block Y (fractional) that a real elevation maps to at this column. */
    public double toY(double elevationMeters, int blockX, int blockZ) {
        double e = elevationMeters / cfg.metersPerBlock;
        if (mode == Mode.CLAMP) {
            return cfg.seaLevelY + e;
        }
        if (mode == Mode.RELATIVE && e > kneeBlocks) {
            e -= shiftBlocks(blockX, blockZ);
        }
        if (e > ceilingKnee) {
            e = ceilingKnee + softRange * (1.0 - Math.exp(-(e - ceilingKnee) / softRange));
        } else if (e < -floorKnee) {
            double r = Math.min(softRange, 150);
            e = -floorKnee - r * (1.0 - Math.exp(-(-floorKnee - e) / r));
        }
        return cfg.seaLevelY + e;
    }

    /**
     * Block Y (fractional) of a real elevation before the squeeze near the ceiling and floor: where the column would
     * be if the world had room. Above {@link #toY} only where a world is too low for its mountains.
     */
    public double unsqueezedY(double elevationMeters, int blockX, int blockZ) {
        double e = elevationMeters / cfg.metersPerBlock;
        if (mode == Mode.RELATIVE && e > kneeBlocks) e -= shiftBlocks(blockX, blockZ);
        return cfg.seaLevelY + e;
    }

    /** How many blocks the relief mode lowers this column's terrain (0 in the 1:1 zone). */
    public double shiftBlocks(int blockX, int blockZ) {
        if (base == null) return 0;
        double b = base.baseMeters(blockX, blockZ);
        if (Double.isNaN(b)) return 0;
        double shift = b / cfg.metersPerBlock - kneeBlocks;
        return shift > 0 ? shift : 0;
    }

    /** Regional average elevation in metres (NaN when unavailable / not in relative mode). */
    public double regionalBaseMeters(int blockX, int blockZ) {
        return base == null ? Double.NaN : base.baseMeters(blockX, blockZ);
    }

    // ------------------------------------------------------------------

    /**
     * Very smooth "regional base" elevation: the mean of the coarse global
     * DEM over a disc of the configured radius, evaluated on a 4 km grid and
     * bilinearly interpolated. Sea counts as 0 so coasts never drag mountain
     * ranges down.
     */
    static final class RegionalBase {
        private static final int ZOOM = 8;
        private static final int NODE_SPACING = 4096;

        private final TileSource tiles;
        private final CoordinateMapper mapper;
        private final double radiusM;
        private final ConcurrentHashMap<Long, Double> nodes = new ConcurrentHashMap<>();

        RegionalBase(TileSource tiles, CoordinateMapper mapper, double radiusKm) {
            this.tiles = tiles;
            this.mapper = mapper;
            this.radiusM = Math.max(2000, radiusKm * 1000.0);
        }

        double baseMeters(int blockX, int blockZ) {
            int nx = Math.floorDiv(blockX, NODE_SPACING), nz = Math.floorDiv(blockZ, NODE_SPACING);
            double fx = (blockX - nx * (double) NODE_SPACING) / NODE_SPACING;
            double fz = (blockZ - nz * (double) NODE_SPACING) / NODE_SPACING;
            double a = node(nx, nz), b = node(nx + 1, nz), c = node(nx, nz + 1), d = node(nx + 1, nz + 1);
            if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) {
                // Partial data: use whatever nodes answered.
                double sum = 0;
                int n = 0;
                for (double v : new double[]{a, b, c, d}) {
                    if (!Double.isNaN(v)) {
                        sum += v;
                        n++;
                    }
                }
                return n == 0 ? Double.NaN : sum / n;
            }
            return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
        }

        private double node(int nx, int nz) {
            long key = ((long) nx << 32) ^ (nz & 0xffffffffL);
            Double v = nodes.get(key);
            if (v != null) return v;
            double computed;
            try {
                computed = compute(nx, nz);
            } catch (RuntimeException e) {
                return Double.NaN; // not cached: the tile may arrive later
            }
            nodes.put(key, computed);
            return computed;
        }

        private double compute(int nx, int nz) {
            double[] ll = mapper.toLatLonExact(nx * (double) NODE_SPACING, nz * (double) NODE_SPACING);
            double lat = ll[0], lon = ll[1];
            double mPerDegLat = 111320.0;
            double mPerDegLon = 111320.0 * Math.max(0.05, Math.cos(Math.toRadians(lat)));
            int steps = 10;
            double step = radiusM / steps;
            double sum = 0, wsum = 0;
            double sigma2 = 2 * (radiusM * 0.5) * (radiusM * 0.5);
            for (int i = -steps; i <= steps; i++) {
                for (int j = -steps; j <= steps; j++) {
                    double dx = i * step, dy = j * step;
                    double d2 = dx * dx + dy * dy;
                    if (d2 > radiusM * radiusM) continue;
                    double sLat = lat + dy / mPerDegLat;
                    double sLon = lon + dx / mPerDegLon;
                    if (sLat > 85 || sLat < -85) continue;
                    double e = sample(sLat, sLon);
                    if (Double.isNaN(e)) continue;
                    double w = Math.exp(-d2 / sigma2);
                    sum += Math.max(0, e) * w;
                    wsum += w;
                }
            }
            if (wsum == 0) throw new IllegalStateException("no coarse DEM samples");
            return sum / wsum;
        }

        private double sample(double lat, double lon) {
            double[] frac = DemTileProvider.latLonToTileFraction(lat, lon, ZOOM);
            int tx = (int) Math.floor(frac[0]), ty = (int) Math.floor(frac[1]);
            float[][] tile = tiles.getTile(ZOOM, tx, ty);
            int size = tile.length;
            int px = Math.min(size - 1, Math.max(0, (int) ((frac[0] - tx) * size)));
            int py = Math.min(size - 1, Math.max(0, (int) ((frac[1] - ty) * size)));
            return tile[py][px];
        }
    }
}
