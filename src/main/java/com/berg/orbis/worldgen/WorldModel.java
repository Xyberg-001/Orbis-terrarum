package com.berg.orbis.worldgen;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.dem.BlendedElevationService;
import com.berg.orbis.dem.VerticalMapping;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.landmark.LandmarkRegistry;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.OsmRegionManager;
import com.berg.orbis.render.ColumnPainter;
import com.berg.orbis.render.Decorator;
import com.berg.orbis.sky.SnowCover;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the generator needs about the real world, in one place:
 * projection, elevation, climate, streamed OSM rasters, landmarks, and the
 * painters that turn all of it into blocks.
 */
public final class WorldModel {

    private final OrbisConfig cfg;
    private final CoordinateMapper mapper;
    private final BlendedElevationService elevation;
    private final VerticalMapping vertical;
    private final BiomeClassifier classifier;
    private final OsmRegionManager regions;
    private final LandmarkRegistry landmarks;
    private final ColumnPainter painter;
    private final Decorator decorator;
    private final UndergroundBand band;

    public WorldModel(OrbisConfig cfg, CoordinateMapper mapper, BlendedElevationService elevation, VerticalMapping vertical,
                      BiomeClassifier classifier, OsmRegionManager regions, LandmarkRegistry landmarks) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.elevation = elevation;
        this.vertical = vertical;
        this.classifier = classifier;
        this.regions = regions;
        this.landmarks = landmarks;
        this.painter = new ColumnPainter(cfg);
        this.decorator = new Decorator(cfg, this);
        this.band = new UndergroundBand(this);
    }

    /** Where vanilla's underground (caves, dungeons, mineshafts, strongholds) sits under the real ground. */
    public UndergroundBand band() {
        return band;
    }

    public OrbisConfig cfg() {
        return cfg;
    }

    public CoordinateMapper mapper() {
        return mapper;
    }

    public OsmRegionManager regions() {
        return regions;
    }

    public LandmarkRegistry landmarks() {
        return landmarks;
    }

    public ColumnPainter painter() {
        return painter;
    }

    public Decorator decorator() {
        return decorator;
    }

    public BiomeClassifier classifier() {
        return classifier;
    }

    private volatile com.berg.orbis.dem.BicubicElevationSampler coarse;

    /** A low-zoom sampler over the same tiles, for decisions that need no detail (biomes, spawn search). */
    public void setCoarseSampler(com.berg.orbis.dem.BicubicElevationSampler sampler) {
        this.coarse = sampler;
    }

    private volatile com.berg.orbis.dem.DemTileProvider terrainTiles;
    private volatile int terrainZoom;

    /** The full-detail terrain tiles, so a chunk can check that its terrain is really available. */
    public void setTerrainTiles(com.berg.orbis.dem.DemTileProvider tiles, int zoom) {
        this.terrainTiles = tiles;
        this.terrainZoom = zoom;
    }

    /**
     * Whether the terrain tiles under a chunk (and 4 blocks around it, for the interpolation) can be read right
     * now. False when a download failed (no internet, server down): the elevation there would silently fall back
     * to sea level, and the chunk would be saved flat for good.
     */
    public boolean terrainReady(int minBlockX, int minBlockZ) {
        com.berg.orbis.dem.DemTileProvider tiles = terrainTiles;
        if (tiles == null) return true;
        int z = terrainZoom;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] c : new int[][]{{minBlockX - 4, minBlockZ - 4}, {minBlockX + 19, minBlockZ - 4}, {minBlockX - 4, minBlockZ + 19}, {minBlockX + 19, minBlockZ + 19}}) {
            double[] ll = mapper.toLatLon(c[0], c[1]);
            double n = Math.pow(2, z);
            int tx = (int) Math.floor((ll[1] + 180) / 360 * n);
            double lat = Math.toRadians(ll[0]);
            int ty = (int) Math.floor((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * n);
            if (!seen.add(((long) tx << 32) ^ (ty & 0xffffffffL))) continue;
            try {
                tiles.getTile(z, tx, ty);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Elevation in metres from the coarse tiles (about 20 m per pixel), or the full-detail value when no coarse
     * sampler is set. Vanilla's spawn selection and biome queries touch a 20 km circle; at the terrain zoom that
     * is hundreds of tiles for a decision that only needs the rough height.
     */
    public double coarseElevation(int x, int z) {
        com.berg.orbis.dem.BicubicElevationSampler c = coarse;
        if (c == null) return elevation(x, z);
        try {
            double[] ll = mapper.toLatLon(x, z);
            double e = c.sampleMeters(ll[0], ll[1]);
            return Double.isNaN(e) ? elevation(x, z) : e;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Raw elevation in metres at a block column, or NaN when no source can answer. */
    public double elevation(int x, int z) {
        try {
            double[] ll = mapper.toLatLon(x, z);
            return elevation.sampleMeters(ll[0], ll[1]);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Elevation rounded to a block Y and clamped to the dimension; NaN becomes sea level. */
    public int terrainHeight(int x, int z) {
        double e = elevation(x, z);
        if (Double.isNaN(e)) e = 0.0;
        return blockY(e, x, z);
    }

    /** The vertical mapping (metres to Y) shared by terrain, water, roads and buildings. */
    public VerticalMapping vertical() {
        return vertical;
    }

    /** Block Y for a real elevation at this column, clamped to the dimension. */
    public int blockY(double elevationMeters, int x, int z) {
        return clampY((int) Math.round(vertical.toY(elevationMeters, x, z)));
    }

    public int clampY(int y) {
        return Math.max(cfg.minY + 1, Math.min(cfg.maxY() - 1, y));
    }

    public BiomeClassifier.Climate climate(int x, int z, double elevationMeters) {
        double[] ll = mapper.toLatLon(x, z);
        return classifier.classify(ll[0], Double.isNaN(elevationMeters) ? 0.0 : elevationMeters);
    }

    private volatile SnowCover snow;
    /** Relief shift (blocks) per 256-block cell, for turning a Y back into metres on the server thread. */
    private final Map<Long, CompletableFuture<Double>> shifts = new ConcurrentHashMap<>();

    /** Today's snow cover (real snow on); null leaves the year-round climate guess in charge. */
    public void setSnowCover(SnowCover snow) {
        this.snow = snow;
    }

    /**
     * The climate with today's snow depth, for painting a column (waits a few seconds for the snow cells). The depth is
     * for the height the server's snow settler works out again from the column's block Y ({@link #snowLayersIfKnown}),
     * not the real elevation: from the real one generation laid 18-19 layers at Everest where the settler wanted
     * 16-17 (the heights there are squeezed to fit the world), and the settler took them off one column at a time,
     * some 20 minutes per chunk, every chunk saved again and again (and Distant Horizons redoing it each time).
     */
    public BiomeClassifier.Climate climateWithSnow(int x, int z, double elevationMeters) {
        BiomeClassifier.Climate c = climate(x, z, elevationMeters);
        SnowCover s = snow;
        if (s == null || !cfg.realSnow) return c;
        double[] ll = mapper.toLatLon(x, z);
        double snowElevation = Double.isNaN(elevationMeters) ? 0.0 : snowElevationM(x, blockY(elevationMeters, x, z), z, true);
        return c.withSnowDepth(s.depthM(ll[0], ll[1], snowElevation));
    }

    /**
     * Metres above sea level the snow is worked out for at a column whose ground is at block Y: the Y back through the
     * vertical mapping (the squeeze near the ceiling aside). NaN when {@code wait} is false and the relief shift of
     * the column's 256-block cell is not known yet (the first ask starts the lookup).
     */
    private double snowElevationM(int x, int y, int z, boolean wait) {
        long key = ((long) (x >> 8) << 32) ^ ((z >> 8) & 0xffffffffL);
        CompletableFuture<Double> f = shifts.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> vertical.shiftBlocks(x, z)));
        Double shift = wait ? f.join() : f.getNow(null);
        if (shift == null) return Double.NaN;
        double e = y - cfg.seaLevelY;
        if (e + shift > cfg.reliefKneeMeters / cfg.verticalMetersPerBlock()) e += shift;
        return e * cfg.verticalMetersPerBlock();
    }

    /**
     * Today's snow depth in snow layers (eight to a block) for a column whose ground is at block Y {@code y}, or -1
     * when not known yet. Never blocks: the server thread asks this, and the first ask starts the lookups. The
     * height comes from the Y back through the vertical mapping (the squeeze near the ceiling aside).
     */
    public int snowLayersIfKnown(int x, int y, int z) {
        SnowCover s = snow;
        if (s == null || !cfg.realSnow) return -1;
        double e = snowElevationM(x, y, z, false);
        if (Double.isNaN(e)) return -1;
        double[] ll = mapper.toLatLon(x, z);
        double d = s.depthIfKnownM(ll[0], ll[1], e);
        if (Double.isNaN(d)) return -1;
        int layers = ColumnPainter.snowLayers(d, cfg.metersPerBlock);
        // As generation lays it: never bare on the ice cap.
        return climate(x, z, e).zone() == BiomeClassifier.Zone.ICE_CAP ? Math.max(1, layers) : layers;
    }

    /** Block Y of the cloud layer: about 800 m above the ground at the origin, so clouds are neither in the streets nor out of sight. */
    public int cloudHeightY() {
        // The rough height is enough, and it is asked on the render thread when a model is built: the full-detail
        // terrain there can be a download (a bare-earth block took 73 s at Kathmandu and froze the screen).
        double e = coarseElevation(0, 0);
        if (Double.isNaN(e)) e = 0;
        // 800 m up at 1:1; at coarse scales that would be a few blocks, so never closer than 128 blocks above the ground.
        double ground = vertical.toY(Math.max(e, 0), 0, 0);
        // Never above the ceiling: a fitted world is only a few hundred blocks higher than its terrain.
        return clampY((int) Math.min(cfg.maxY() - 64, Math.round(Math.max(vertical.toY(Math.max(e, 0) + 800.0, 0, 0), ground + 128))));
    }

    /** Stops the background threads of this model (called when a different world's model replaces it). */
    public void shutdown() {
        if (regions != null) regions.shutdown();
    }

    /** OSM raster for the region containing this column; null if OSM is disabled/unavailable and waiting is off. May block -- not for worldgen threads. */
    public RegionRaster rasterForBlock(int x, int z) {
        return regions == null ? null : regions.getForBlock(x, z);
    }

    private volatile com.berg.orbis.landcover.WorldCoverProvider worldCover;

    /** The worldwide land cover (ESA WorldCover), for the far view where there is no map data; null when it is off. */
    public void setWorldCover(com.berg.orbis.landcover.WorldCoverProvider worldCover) {
        this.worldCover = worldCover;
    }

    /**
     * The land cover at a block from ESA WorldCover alone (as the rasteriser fills gaps with it), or null when it is off,
     * says water, or cannot be read. Blocks while a tile downloads (a few hundred kB per 10 km square).
     */
    public com.berg.orbis.feature.LandCover worldCoverAt(int x, int z) {
        return worldCoverLand(worldCoverCodeAt(x, z), x, z);
    }

    /** The ESA WorldCover class at a block (WorldCoverProvider.TREE_COVER ...), or -1 when it is off or cannot be read. */
    public int worldCoverCodeAt(int x, int z) {
        com.berg.orbis.landcover.WorldCoverProvider w = worldCover;
        if (w == null) return -1;
        try {
            double[] ll = mapper.toLatLon(x, z);
            return w.classAt(ll[0], ll[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The land cover for an ESA WorldCover class at a block, or null for water and unknown classes. */
    public com.berg.orbis.feature.LandCover worldCoverLand(int code, int x, int z) {
        if (code < 0) return null;
        try {
            double[] ll = code == com.berg.orbis.landcover.WorldCoverProvider.BARE ? mapper.toLatLon(x, z) : new double[]{90, 0};
            return switch (code) {
                case com.berg.orbis.landcover.WorldCoverProvider.TREE_COVER -> com.berg.orbis.feature.LandCover.FOREST;
                case com.berg.orbis.landcover.WorldCoverProvider.SHRUBLAND -> com.berg.orbis.feature.LandCover.SCRUB;
                case com.berg.orbis.landcover.WorldCoverProvider.GRASSLAND -> com.berg.orbis.feature.LandCover.MEADOW;
                case com.berg.orbis.landcover.WorldCoverProvider.CROPLAND -> com.berg.orbis.feature.LandCover.FARMLAND;
                case com.berg.orbis.landcover.WorldCoverProvider.BUILT_UP -> com.berg.orbis.feature.LandCover.RESIDENTIAL;
                case com.berg.orbis.landcover.WorldCoverProvider.BARE -> Math.abs(ll[0]) < 35 ? com.berg.orbis.feature.LandCover.SAND : com.berg.orbis.feature.LandCover.BARE_ROCK;
                case com.berg.orbis.landcover.WorldCoverProvider.SNOW_ICE -> com.berg.orbis.feature.LandCover.GLACIER;
                case com.berg.orbis.landcover.WorldCoverProvider.WETLAND, com.berg.orbis.landcover.WorldCoverProvider.MANGROVES -> com.berg.orbis.feature.LandCover.WETLAND;
                case com.berg.orbis.landcover.WorldCoverProvider.MOSS_LICHEN -> com.berg.orbis.feature.LandCover.HEATH;
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** OSM raster if it is already decoded, else null. Never blocks. */
    public RegionRaster rasterIfLoaded(int x, int z) {
        return regions == null ? null : regions.getIfLoadedForBlock(x, z);
    }
}
