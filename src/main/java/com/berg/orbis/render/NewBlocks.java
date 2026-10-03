package com.berg.orbis.render;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks that only newer Minecraft versions have, looked up by name when the game runs so one build serves every
 * version: on a version without the block the field is null and callers keep what they used before (see
 * {@link #or}). Added in 26.3: the poplar wood set with yellow, orange and red leaves, the red shrub and the shelf
 * mushroom (the concrete stairs and slabs are found by {@link RoofBlocks}).
 */
public final class NewBlocks {

    public static final BlockState POPLAR_LOG = find("poplar_log");
    public static final BlockState POPLAR_PLANKS = find("poplar_planks");
    public static final BlockState YELLOW_POPLAR_LEAVES = find("yellow_poplar_leaves");
    public static final BlockState ORANGE_POPLAR_LEAVES = find("orange_poplar_leaves");
    public static final BlockState RED_POPLAR_LEAVES = find("red_poplar_leaves");
    public static final BlockState RED_SHRUB = find("red_shrub");
    public static final BlockState SHELF_MUSHROOM = find("shelf_mushroom");

    private NewBlocks() {
    }

    /** The newer block when this Minecraft has it, otherwise the fallback. */
    public static BlockState or(BlockState newer, BlockState fallback) {
        return newer != null ? newer : fallback;
    }

    /** Whether this Minecraft has the autumn leaves (26.3 and later). */
    public static boolean hasAutumnLeaves() {
        return YELLOW_POPLAR_LEAVES != null && ORANGE_POPLAR_LEAVES != null && RED_POPLAR_LEAVES != null;
    }

    private static BlockState find(String name) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).map(Block::defaultBlockState).orElse(null);
    }
}
