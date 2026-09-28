package com.berg.orbis.feature;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Attributes shared by every column of one building (or building:part).
 * Per-column data (edge/door flags, roof height above the wall top) is in
 * the raster so the painter never has to do geometry at generation time.
 */
public final class BuildingFeature {

    public final long id;
    public final Map<String, String> tags;
    public final String type;
    /** Wall height in blocks above the base, roof excluded. */
    public final int heightBlocks;
    /** For building:part elements that start above the ground (bridges between towers, cantilevers). */
    public final int minHeightBlocks;
    public final int levels;
    public final RoofShape roofShape;
    public final int roofHeightBlocks;
    public final BlockState wall;
    public final BlockState wallAccent;
    /** Roof block; replaced by the imagery-sampled colour when OSM has no roof tags. */
    public BlockState roof;
    /** Where the height came from: "tags", "surface-model" or "default". */
    public String heightSource = "default";
    public final BlockState window;
    public final BlockState floor;
    public final boolean part;
    public final boolean domed;
    public final boolean minarets;
    public final boolean glassCurtain;
    /** Storey height in blocks used for floors and window rows. */
    public final int storeyBlocks;
    /** Footprint centroid in block coordinates. */
    public final double cx, cz;
    /** Unit vector of the footprint's main axis (direction of the longest edge). */
    public final double axisX, axisZ;
    /** Half extents along / across the main axis. */
    public final double halfA, halfB;
    /** Ground level (terrain Y at the lowest footprint vertex); walls start at baseY + 1. */
    public int baseY;
    /** Whether this footprint is fully or partially covered by a landmark schematic (then skipped). */
    public boolean suppressed;
    public final String name;
    /**
     * Tags of the points of interest mapped inside the footprint (a shop, a museum, a bar: in OpenStreetMap the
     * business is usually a node in the building, not a tag on it). Furniture and door signs read these too.
     */
    public final java.util.List<Map<String, String>> pois = new java.util.ArrayList<>();

    public BuildingFeature(long id, Map<String, String> tags, String type, int heightBlocks, int minHeightBlocks, int levels,
                           RoofShape roofShape, int roofHeightBlocks, BlockState wall, BlockState wallAccent, BlockState roof,
                           BlockState window, BlockState floor, boolean part, boolean domed, boolean minarets,
                           boolean glassCurtain, int storeyBlocks, double cx, double cz, double axisX, double axisZ,
                           double halfA, double halfB, String name) {
        this.id = id;
        this.tags = tags;
        this.type = type;
        this.heightBlocks = heightBlocks;
        this.minHeightBlocks = minHeightBlocks;
        this.levels = levels;
        this.roofShape = roofShape;
        this.roofHeightBlocks = roofHeightBlocks;
        this.wall = wall;
        this.wallAccent = wallAccent;
        this.roof = roof;
        this.window = window;
        this.floor = floor;
        this.part = part;
        this.domed = domed;
        this.minarets = minarets;
        this.glassCurtain = glassCurtain;
        this.storeyBlocks = storeyBlocks;
        this.cx = cx;
        this.cz = cz;
        this.axisX = axisX;
        this.axisZ = axisZ;
        this.halfA = halfA;
        this.halfB = halfB;
        this.name = name;
    }

    public int wallTopY() {
        return baseY + heightBlocks;
    }
}
