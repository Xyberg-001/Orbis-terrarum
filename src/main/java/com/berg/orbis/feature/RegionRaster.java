package com.berg.orbis.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-column lookup tables for one square region of the world, produced by
 * the rasteriser from OSM vector data and read by the chunk generator.
 *
 * Everything is a flat array indexed by (localZ * stride + localX) where the
 * local coordinates include a margin around the region so that features
 * which straddle the region boundary (and neighbour lookups for building
 * edges) work without touching the adjacent region.
 *
 * Layers are independent: a column can be forest AND road AND under a
 * bridge deck. The painter decides how they combine.
 */
public final class RegionRaster {

    public static final int FLAG_EDGE = 1;
    public static final int FLAG_DOOR = 2;
    public static final int FLAG_MINARET = 4;
    public static final int FLAG_GABLE_END = 8;
    public static final int FLAG_ROOF_ONLY = 16;
    public static final int FLAG_CORNER = 32;
    /** A door's outward side, in the top two bits of its flags: 0 east, 1 west, 2 south, 3 north. */
    public static final int DOOR_SIDE_SHIFT = 6;

    public static final short NO_Y = Short.MIN_VALUE;

    public final int regionX, regionZ;
    public final int size, margin, stride;
    /** World block coordinate of local index 0. */
    public final int originX, originZ;

    public final byte[] landCover;
    public final short[] road;
    public final byte[] roadDist;
    public final byte[] roadT;
    public final byte[] roadDir;
    public final short[] roadY;
    public final short[] water;
    public final short[] building;
    public final byte[] roofExtra;
    public final byte[] buildingFlags;
    public final byte[] decor;
    public final byte[] decorData;
    /** 1 = sea side of a coastline (computed by flood fill), 0 = unknown/land. */
    public final byte[] sea;
    /** Aerial-imagery ground class (GroundClass ordinal) for columns OSM leaves unmapped; 0 = unknown. */
    public final byte[] groundClass;
    /**
     * The road that runs <i>under</i> a bridge deck at this column (index into
     * roads, 0 = none), with its own centreline distance and deck Y, so the
     * street below a flyover keeps its asphalt. Allocated on first use.
     */
    public short[] roadUnder;
    public byte[] roadUnderDist;
    public short[] roadUnderY;
    /** Columns this many blocks (1..SHOULDER) outside a graded road blend from the road height back to the ground. */
    public byte[] shoulderDist;
    /** The road height a shoulder column blends from. */
    public short[] shoulderY;
    /** Width of a graded road's side slopes, in blocks. */
    public static final int SHOULDER = 3;
    /** The bedrock under each column (Rocks.Rock ordinal, 0 = unknown: plain stone); null where no map covers the region. */
    public byte[] rock;
    /** Distance of a sea column from the nearest shore, in blocks (1..127, 0 = not sea); null where the region has no sea. */
    public byte[] seaShore;
    /** Depth of the lake or river bed below the water surface per column, in blocks (0 = the water body's own depth); null when not shaped. */
    public short[] bedDepth;
    /** The streets' colour in this region's aerial photo (0xRRGGBB), the neutral reference for roof colours; -1 = none. */
    public int imageryStreetColour = -2;

    public final List<RoadFeature> roads = new ArrayList<>();
    public final List<WaterFeature> waters = new ArrayList<>();
    public final List<BuildingFeature> buildings = new ArrayList<>();

    /** Names attached to point decor (station names for TRAIN_STOP cells), keyed by column index. */
    public final Map<Integer, String> labels = new HashMap<>();

    /** True when trees were placed from a canopy height model (lidar or the global canopy map): no random scattering then. */
    public boolean canopyTrees;

    /** Lowest floor Y of any real (below-ground) road tunnel in this raster, or MAX_VALUE: the underground band stays under it. */
    public int minTunnelY = Integer.MAX_VALUE;

    /** True once at least one OSM element was rasterised (false for empty ocean regions). */
    public boolean hasOsmData;
    public boolean hasCoastline;

    public RegionRaster(int regionX, int regionZ, int size, int margin) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.size = size;
        this.margin = margin;
        this.stride = size + 2 * margin;
        this.originX = regionX * size - margin;
        this.originZ = regionZ * size - margin;
        int n = stride * stride;
        landCover = new byte[n];
        road = new short[n];
        roadDist = new byte[n];
        roadT = new byte[n];
        roadDir = new byte[n];
        roadY = new short[n];
        java.util.Arrays.fill(roadY, NO_Y);
        water = new short[n];
        building = new short[n];
        roofExtra = new byte[n];
        buildingFlags = new byte[n];
        decor = new byte[n];
        decorData = new byte[n];
        sea = new byte[n];
        groundClass = new byte[n];
    }

    public int groundClassAt(int idx) {
        return groundClass[idx] & 0xFF;
    }

    /** Allocates the under-deck road layers (called by the rasteriser when a bridge first crosses a road). */
    public void ensureUnderLayers() {
        if (roadUnder == null) {
            int n = stride * stride;
            roadUnder = new short[n];
            roadUnderDist = new byte[n];
            roadUnderY = new short[n];
            java.util.Arrays.fill(roadUnderY, NO_Y);
        }
    }

    public RoadFeature roadUnderAt(int idx) {
        if (roadUnder == null) return null;
        int r = roadUnder[idx];
        return r <= 0 ? null : roads.get(r - 1);
    }

    public int roadUnderDistAt(int idx) {
        return roadUnderDist == null ? 0 : roadUnderDist[idx] & 0xFF;
    }

    public int roadUnderYAt(int idx) {
        return roadUnderY == null ? NO_Y : roadUnderY[idx];
    }

    /** Index for a world block column, or -1 if outside this raster (including its margin). */
    public int index(int worldX, int worldZ) {
        int lx = worldX - originX;
        int lz = worldZ - originZ;
        if (lx < 0 || lz < 0 || lx >= stride || lz >= stride) return -1;
        return lz * stride + lx;
    }

    public boolean contains(int worldX, int worldZ) {
        return index(worldX, worldZ) >= 0;
    }

    public LandCover landCoverAt(int idx) {
        return LandCover.byCode(landCover[idx]);
    }

    public RoadFeature roadAt(int idx) {
        int r = road[idx];
        return r <= 0 ? null : roads.get(r - 1);
    }

    public WaterFeature waterAt(int idx) {
        int w = water[idx];
        return w <= 0 ? null : waters.get(w - 1);
    }

    public BuildingFeature buildingAt(int idx) {
        int b = building[idx];
        if (b <= 0) return null;
        BuildingFeature f = buildings.get(b - 1);
        return f.suppressed ? null : f;
    }

    public DecorType decorAt(int idx) {
        return DecorType.byCode(decor[idx]);
    }

    public boolean isSea(int idx) {
        return sea[idx] != 0;
    }

    /** Allocates the road shoulder layers (only regions with graded roads need them). */
    public void ensureShoulders() {
        if (shoulderDist != null) return;
        int n = stride * stride;
        shoulderDist = new byte[n];
        shoulderY = new short[n];
    }

    public int roofExtraAt(int idx) {
        return roofExtra[idx] & 0xFF;
    }

    public int roadDistAt(int idx) {
        return roadDist[idx] & 0xFF;
    }

    public int roadTAt(int idx) {
        return roadT[idx] & 0xFF;
    }

    public int bedDepthAt(int idx) {
        return bedDepth == null ? 0 : bedDepth[idx];
    }

    public int rockAt(int idx) {
        return rock == null ? 0 : rock[idx];
    }

    public int roadDirAt(int idx) {
        return roadDir[idx] & 0xFF;
    }

    public long approxBytes() {
        long n = (long) stride * stride;
        return n * (1 + 2 + 1 + 1 + 1 + 2 + 2 + 2 + 1 + 1 + 1 + 1 + 1 + 1 + (roadUnder != null ? 5 : 0) + (rock != null ? 1 : 0) + (bedDepth != null ? 2 : 0));
    }
}
