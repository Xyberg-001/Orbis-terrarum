package com.berg.orbis.worldgen;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.geology.Rocks;
import com.berg.orbis.geology.Soils;
import com.berg.orbis.landcover.ForestTypes;
import com.berg.orbis.osm.FeatureRasterizer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The map layers a region raster carries for its columns (the rock, the soil, the kind of forest), for the far view's
 * columns where no region is loaded: the far view showed plain stone on every bare mountain and the climate's mix of
 * trees in every wood until the chunks came. Read from the same maps (and their downloads) as the regions, a sixteenth
 * of a degree at a time, the forest maps at no finer than 10 m.
 *
 * <p>Thread-safe; a column waits for its cell's maps at most {@link #WAIT_MS}, then goes without them.
 */
public final class FarGround {

    /** What the maps say at one column (0 / UNKNOWN where they say nothing). */
    public record Sample(int rock, int soil, ForestTypes.Leaf leaf, int kind) {
        static final Sample NONE = new Sample(0, 0, ForestTypes.Leaf.UNKNOWN, 0);
    }

    private static final double CELL = 1 / 16.0;
    private static final long WAIT_MS = 30_000;
    private static final int MAX_CELLS = 256;
    /** The soil map's pixel in degrees (250 m), for the same half-pixel jitter the regions use. */
    private static final double SOIL_PIXEL = 0.0020833;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Orbis-far-ground");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private record Cell(Soils.Area soil, ForestTypes.Area forest) {}

    private final WorldModel model;
    private final OrbisConfig cfg;
    private final Rocks rocks;
    private final Soils soils;
    private final ForestTypes forests;
    private final Map<Long, CompletableFuture<Cell>> cells = new ConcurrentHashMap<>();

    private FarGround(WorldModel model, Rocks rocks, Soils soils, ForestTypes forests) {
        this.model = model;
        this.cfg = model.cfg();
        this.rocks = rocks;
        this.soils = soils;
        this.forests = forests;
    }

    /** The far view's maps for a world, or null when it has none of these layers turned on. */
    public static FarGround of(WorldModel model) {
        FeatureRasterizer r = model.rasterizer();
        if (r == null) return null;
        OrbisConfig cfg = model.cfg();
        Rocks rocks = r.bedrock();
        Soils soils = cfg.groundLayers ? r.soils() : null;
        ForestTypes forests = cfg.generateLandCover && cfg.forestTypes ? r.forestTypes() : null;
        if (rocks == null && soils == null && forests == null) return null;
        return new FarGround(model, rocks, soils, forests);
    }

    /** The maps at a block ({@code forest}: whether its land cover is a wood, so the forest maps are worth reading). */
    public Sample at(int x, int z, boolean forest) {
        double[] ll = model.mapper().toLatLonExact(x + 0.5, z + 0.5);
        Cell c = cell(ll[0], ll[1]);
        long h = FeatureRasterizer.groundHash(x, z);
        int rock = rocks == null ? 0 : rocks.rockAt(ll[0], ll[1]).ordinal();
        int soil = 0;
        if (c != null && c.soil() != null && c.soil().hasProfiles()) {
            double jLat = ((h & 0xFFFF) / 65535.0 - 0.5) * SOIL_PIXEL, jLon = (((h >>> 16) & 0xFFFF) / 65535.0 - 0.5) * SOIL_PIXEL;
            soil = c.soil().soilAt(ll[0] + jLat, ll[1] + jLon);
        }
        ForestTypes.Leaf leaf = ForestTypes.Leaf.UNKNOWN;
        int kind = 0;
        if (forest && c != null && c.forest() != null) {
            double jx = (h & 0xFFFF) / 65536.0, jy = ((h >>> 16) & 0xFFFF) / 65536.0;
            leaf = c.forest().at(ll[0], ll[1], jx, jy);
            ForestTypes.Kind k = c.forest().kindAt(ll[0], ll[1], jx, jy);
            // As the regions keep it: spruce or pine only in a conifer or mixed wood, broadleaf only in a broadleaf or mixed one.
            boolean fits = switch (k) {
                case SPRUCE, PINE -> leaf != ForestTypes.Leaf.BROADLEAF;
                case BROADLEAF -> leaf != ForestTypes.Leaf.CONIFER;
                default -> false;
            };
            if (fits) kind = k.ordinal();
        }
        if (rock == 0 && soil == 0 && leaf == ForestTypes.Leaf.UNKNOWN && kind == 0) return Sample.NONE;
        return new Sample(rock, soil, leaf, kind);
    }

    /** The cell's soil and forest maps, read once (null while they cannot be had in time). */
    private Cell cell(double lat, double lon) {
        if (soils == null && forests == null && rocks == null) return null;
        int i = (int) Math.floor(lat / CELL), j = (int) Math.floor(lon / CELL);
        long key = (long) i << 32 | (j & 0xFFFFFFFFL);
        CompletableFuture<Cell> f = cells.get(key);
        if (f == null) {
            if (cells.size() >= MAX_CELLS) trim();
            f = cells.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> read(i, j), POOL));
        }
        try {
            return f.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if (f.isDone()) cells.remove(key, f); // failed: asked again later
            return null;
        }
    }

    private Cell read(int i, int j) {
        double south = i * CELL, north = south + CELL, west = j * CELL, east = west + CELL;
        if (rocks != null && rocks.overlaps(south, west, north, east)) rocks.prefetch(south, west, north, east);
        Soils.Area soil = soils == null ? null : soils.area(south, west, north, east, false);
        ForestTypes.Area forest = forests == null ? null : forests.area(south, west, north, east, Math.max(10, cfg.metersPerBlock));
        return new Cell(soil, forest);
    }

    /** Keeps the cache small: the cells already read go (the far view moves on; a cell asked again is read from disk). */
    private void trim() {
        cells.entrySet().removeIf(e -> e.getValue().isDone());
    }
}
