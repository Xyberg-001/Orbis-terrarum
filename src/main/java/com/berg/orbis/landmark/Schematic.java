package com.berg.orbis.landmark;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A decoded schematic: a dense block-state volume. -1 in the index array
 * means "leave the world as it is" (structure void / unset).
 */
public final class Schematic {

    public final String name;
    public final int width, height, length;
    public final BlockState[] palette;
    /** Palette index per block, ordered (y * length + z) * width + x. */
    public final int[] blocks;
    /** Sponge "Offset" (position of the schematic origin relative to the paste point), applied by the placer. */
    public final int offsetX, offsetY, offsetZ;

    public Schematic(String name, int width, int height, int length, BlockState[] palette, int[] blocks,
                     int offsetX, int offsetY, int offsetZ) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blocks = blocks;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    /** Block state at local coordinates, or null for "not set". */
    public BlockState get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) return null;
        int idx = blocks[(y * length + z) * width + x];
        return idx < 0 ? null : palette[idx];
    }

    public int blockCount() {
        return width * height * length;
    }
}
