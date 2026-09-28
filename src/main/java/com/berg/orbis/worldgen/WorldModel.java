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

    /**
     * True when terrainOnlyBeyondBlocks is set and this column is farther than
     * that from every player: such chunks skip the OSM wait and generate from
     * elevation alone (for distant-horizon mods generating LOD radii).
     */
    public boolean farFromPlayers(int x, int z) {
        return cfg.terrainOnlyBeyondBlocks > 0 && regions != null
                && regions.nearestPlayerDistance(x, z) > cfg.terrainOnlyBeyondBlocks;
    }

    /** Block Y of the cloud layer: about 800 m above the ground at the origin, so clouds are neither in the streets nor out of sight. */
    public int cloudHeightY() {
        double e = elevation(0, 0);
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

    /** OSM raster if it is already decoded, else null. Never blocks. */
    public RegionRaster rasterIfLoaded(int x, int z) {
        return regions == null ? null : regions.getIfLoadedForBlock(x, z);
    }
}
