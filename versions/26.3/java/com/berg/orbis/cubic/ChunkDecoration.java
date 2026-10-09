package com.berg.orbis.cubic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.opencubicchunks.cubicchunks.api.CubeTerrain;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * One chunk's decoration in a cubic world, made once ({@link DecorationLevel}) and shared out: each cube it reaches takes the blocks, block
 * entities, entities and scheduled ticks that fall in it. Kept by cube Y, so a cube finds its share without looking at the rest.
 */
final class ChunkDecoration {
    static final ChunkDecoration NONE = new ChunkDecoration();

    private final Int2ObjectOpenHashMap<Part> parts = new Int2ObjectOpenHashMap<>();
    private int blocks;

    /** What falls in one cube layer. */
    private static final class Part {
        final LongArrayList positions = new LongArrayList();
        final List<BlockState> states = new ArrayList<>();
        final List<BlockEntity> blockEntities = new ArrayList<>();
        final List<Entity> entities = new ArrayList<>();
        final LongArrayList postProcessing = new LongArrayList();
        final List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
        final List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
    }

    private ChunkDecoration() {}

    /** The writes of a finished decoration; those that leave the painted block as it was are dropped. */
    static ChunkDecoration of(DecorationLevel level) {
        ChunkDecoration decoration = new ChunkDecoration();
        for (Map.Entry<Long, TreeMap<Integer, BlockState>> column : level.writes().entrySet()) {
            int x = BlockPos.getX(column.getKey());
            int z = BlockPos.getZ(column.getKey());
            PaintedChunk.Column painted = level.paintedColumn(x, z);
            for (Map.Entry<Integer, BlockState> write : column.getValue().entrySet()) {
                int y = write.getKey();
                if (painted.get(y) == write.getValue()) continue;
                Part part = decoration.part(y);
                part.positions.add(BlockPos.asLong(x, y, z));
                part.states.add(write.getValue());
                decoration.blocks++;
            }
        }
        for (BlockEntity blockEntity : level.blockEntities().values()) {
            decoration.part(blockEntity.getBlockPos().getY()).blockEntities.add(blockEntity);
        }
        for (Entity entity : level.entities()) {
            decoration.part(entity.getBlockY()).entities.add(entity);
        }
        for (BlockPos pos : level.postProcessing()) {
            decoration.part(pos.getY()).postProcessing.add(pos.asLong());
        }
        for (ScheduledTick<Block> tick : level.blockTicks()) {
            decoration.part(tick.pos().getY()).blockTicks.add(tick);
        }
        for (ScheduledTick<Fluid> tick : level.fluidTicks()) {
            decoration.part(tick.pos().getY()).fluidTicks.add(tick);
        }
        return decoration;
    }

    private Part part(int y) {
        return this.parts.computeIfAbsent(Math.floorDiv(y, CubeTerrain.SIZE), k -> new Part());
    }

    int blocks() {
        return this.blocks;
    }

    /**
     * Puts the blocks that fall in the cube into it (the cube ignores the rest of its layer). The decorations around a cube can write the
     * same block, so every decoration's blocks go in first ({@link #applyBlocks}) and the rest after ({@link #applyRest}).
     */
    void applyBlocks(CubeTerrain cube) {
        Part part = this.parts.get(Math.floorDiv(cube.minY(), CubeTerrain.SIZE));
        if (part == null) return;
        for (int i = 0; i < part.positions.size(); i++) {
            long pos = part.positions.getLong(i);
            int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
            if (inside(cube, x, z)) cube.setBlock(x, BlockPos.getY(pos), z, part.states.get(i));
        }
    }

    /**
     * The block entities, entities, post-processing marks and ticks that fall in the cube, once every decoration's blocks are in: a block
     * entity only where the block it was made for is still there (another chunk's decoration may have written over it, which made the game
     * drop the block entity with a warning as the cube loaded).
     */
    void applyRest(CubeTerrain cube) {
        Part part = this.parts.get(Math.floorDiv(cube.minY(), CubeTerrain.SIZE));
        if (part == null) return;
        for (BlockEntity blockEntity : part.blockEntities) {
            BlockPos at = blockEntity.getBlockPos();
            if (inside(cube, at.getX(), at.getZ()) && blockEntity.getType().isValid(cube.getBlock(at.getX(), at.getY(), at.getZ()))) {
                cube.setBlockEntity(blockEntity);
            }
        }
        for (Entity entity : part.entities) {
            if (inside(cube, entity.getBlockX(), entity.getBlockZ())) cube.addEntity(entity);
        }
        for (int i = 0; i < part.postProcessing.size(); i++) {
            long pos = part.postProcessing.getLong(i);
            cube.markForPostProcessing(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        }
        for (ScheduledTick<Block> tick : part.blockTicks) {
            cube.scheduleBlockTick(tick.pos().getX(), tick.pos().getY(), tick.pos().getZ(), tick.type());
        }
        for (ScheduledTick<Fluid> tick : part.fluidTicks) {
            cube.scheduleFluidTick(tick.pos().getX(), tick.pos().getY(), tick.pos().getZ(), tick.type());
        }
    }

    private static boolean inside(CubeTerrain cube, int x, int z) {
        return x >= cube.minX() && x < cube.minX() + CubeTerrain.SIZE && z >= cube.minZ() && z < cube.minZ() + CubeTerrain.SIZE;
    }
}
