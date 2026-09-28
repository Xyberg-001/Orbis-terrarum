package com.berg.orbis.feature;

/** A body of water. Geometry is in the raster; this carries type and depth. */
public final class WaterFeature {

    public enum Kind { SEA, LAKE, POND, RESERVOIR, RIVER, STREAM, CANAL, DITCH, BASIN, WETLAND_WATER, SWIMMING_POOL, FOUNTAIN_BASIN }

    /** Marker for "surface follows the local terrain" (linear waterways). */
    public static final int FOLLOW_TERRAIN = Integer.MIN_VALUE;

    public final long id;
    public final Kind kind;
    /** Depth of the bed below the water surface, in blocks. */
    public final int depth;
    /** Whether the water surface is pinned to real sea level (tidal / sea) rather than local terrain. */
    public final boolean atSeaLevel;
    public final boolean intermittent;
    public final String name;
    /** Flat surface Y for lakes/ponds (median shoreline elevation), or FOLLOW_TERRAIN. */
    public int surfaceY = FOLLOW_TERRAIN;

    public WaterFeature(long id, Kind kind, int depth, boolean atSeaLevel, boolean intermittent, String name) {
        this.id = id;
        this.kind = kind;
        this.depth = Math.max(1, depth);
        this.atSeaLevel = atSeaLevel;
        this.intermittent = intermittent;
        this.name = name;
    }

    public boolean isLinear() {
        return kind == Kind.RIVER || kind == Kind.STREAM || kind == Kind.CANAL || kind == Kind.DITCH;
    }
}
