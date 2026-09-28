package com.berg.orbis.feature;

import net.minecraft.world.level.block.state.BlockState;

/**
 * One drawable linear transport feature: a road, path, railway, runway,
 * pier deck or similar. Geometry lives in the raster (per-column index into
 * the region's road list, plus distance from the centreline); this object
 * only carries the shared attributes.
 */
public final class RoadFeature {

    public enum Kind { ROAD, PATH, STEPS, RAIL, RUNWAY, TAXIWAY, PIER, TRACK }

    public final long id;
    public final Kind kind;
    public final String highway;
    public final String railway;
    public final int halfWidth;
    public final boolean bridge;
    public final boolean tunnel;
    public final int layer;
    public final boolean sidewalk;
    public final boolean oneway;
    public final int lanes;
    public final boolean lit;
    /** Drawing order; higher priority overwrites lower at the same layer. */
    public final int priority;
    public final BlockState surface;
    public final BlockState sidewalkBlock;
    /** Block used for the centre line / lane edge markings, or null for none. */
    public final BlockState markingBlock;
    /** Whether a dashed centre line should be painted. */
    public final boolean centreLine;
    /** Whether solid edge lines should be painted. */
    public final boolean edgeLines;
    /** Height of the deck above the "roadY" (piers are 1 block above water). */
    public final String name;

    public RoadFeature(long id, Kind kind, String highway, String railway, int halfWidth, boolean bridge, boolean tunnel,
                       int layer, boolean sidewalk, boolean oneway, int lanes, boolean lit, int priority,
                       BlockState surface, BlockState sidewalkBlock, BlockState markingBlock,
                       boolean centreLine, boolean edgeLines, String name) {
        this.id = id;
        this.kind = kind;
        this.highway = highway;
        this.railway = railway;
        this.halfWidth = halfWidth;
        this.bridge = bridge;
        this.tunnel = tunnel;
        this.layer = layer;
        this.sidewalk = sidewalk;
        this.oneway = oneway;
        this.lanes = lanes;
        this.lit = lit;
        this.priority = priority;
        this.surface = surface;
        this.sidewalkBlock = sidewalkBlock;
        this.markingBlock = markingBlock;
        this.centreLine = centreLine;
        this.edgeLines = edgeLines;
        this.name = name;
    }

    public boolean isRail() {
        return kind == Kind.RAIL;
    }

    public boolean elevatedDeck() {
        return bridge || tunnel || kind == Kind.PIER;
    }
}
