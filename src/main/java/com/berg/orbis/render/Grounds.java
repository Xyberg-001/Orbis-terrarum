package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Cemeteries and playgrounds. Cemeteries get rows of gravestones and no light at all, so vanilla's night
 * spawns make them a place to avoid after dark; playgrounds get swings and a slide.
 */
public final class Grounds {

    private Grounds() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ, boolean[] built) {
        if (r == null || !cfg.streetLife) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (built != null && built[lx * 16 + lz]) continue;
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0 || r.road[idx] != 0 || r.building[idx] != 0 || r.water[idx] != 0 || r.decor[idx] != 0) continue;
                if (ColumnPainter.inFrontOfDoor(r, x, z)) continue;
                LandCover lc = r.landCoverAt(idx);
                if (lc != LandCover.CEMETERY && lc != LandCover.PLAYGROUND) continue;
                int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                if (surface <= cfg.minY || surface + 5 >= cfg.maxY()) continue;
                if (!level.getBlockState(pos.set(x, surface, z)).isSolid()) continue;
                if (!level.getBlockState(pos.set(x, surface + 1, z)).getFluidState().isEmpty()) continue;
                long h = ColumnPainter.hash(x, z, 0x6A4E);
                if (lc == LandCover.CEMETERY) {
                    // Rows: every third column, every fourth row, most of the slots filled.
                    if (Math.floorMod(x, 3) != 1 || Math.floorMod(z, 4) != 1 || h % 100 < 15) continue;
                    if (r.landCoverAt(idx) != LandCover.CEMETERY) continue;
                    BlockState stone = switch ((int) ((h >>> 8) % 6)) {
                        case 0 -> Blocks.STONE_BRICK_WALL.defaultBlockState();
                        case 1 -> Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState();
                        case 2 -> Blocks.COBBLESTONE_WALL.defaultBlockState();
                        case 3 -> Blocks.ANDESITE_WALL.defaultBlockState();
                        case 4 -> Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
                        default -> Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                    };
                    set(level, pos, x, surface + 1, z, stone);
                    if ((h >>> 16) % 5 == 0) set(level, pos, x, surface + 2, z, Blocks.STONE_BRICK_WALL.defaultBlockState()); // a taller cross
                    if ((h >>> 20) % 4 == 0 && level.getBlockState(pos.set(x, surface + 1, z + 1)).isAir()
                            && level.getBlockState(pos.set(x, surface, z + 1)).is(Blocks.GRASS_BLOCK)) {
                        set(level, pos, x, surface + 1, z + 1, ((h >>> 24) & 1) == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.DEAD_BUSH.defaultBlockState());
                    }
                } else {
                    // One swing frame and one slide per playground chunk, on cells whose hash wins.
                    if (h % 130 != 0) continue;
                    if (((h >>> 8) & 1) == 0) swing(level, pos, x, surface + 1, z, cfg);
                    else slide(level, pos, x, surface + 1, z, cfg);
                }
            }
        }
    }

    /** Two log posts, a beam across, two chains with plank seats. */
    private static void swing(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, OrbisConfig cfg) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int i = 0; i < 4; i++) {
                if (!level.getBlockState(pos.set(x + dx, y + i, z)).isAir()) return;
            }
        }
        for (int i = 0; i < 3; i++) {
            set(level, pos, x - 2, y + i, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
            set(level, pos, x + 2, y + i, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
        }
        BlockState beam = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int dx = -2; dx <= 2; dx++) set(level, pos, x + dx, y + 3, z, beam);
        for (int dx = -1; dx <= 1; dx += 2) {
            set(level, pos, x + dx, y + 2, z, Blocks.IRON_CHAIN.defaultBlockState());
            set(level, pos, x + dx, y + 1, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
    }

    /** Two steps up to a platform and a smooth stone slide down the other side. */
    private static void slide(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, OrbisConfig cfg) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int i = 0; i < 4; i++) {
                if (!level.getBlockState(pos.set(x, y + i, z + dz)).isAir()) return;
            }
        }
        set(level, pos, x, y, z - 2, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        set(level, pos, x, y, z - 1, Blocks.SPRUCE_PLANKS.defaultBlockState());
        set(level, pos, x, y + 1, z - 1, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        set(level, pos, x, y, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
        set(level, pos, x, y + 1, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
        set(level, pos, x, y + 2, z, Blocks.SPRUCE_SLAB.defaultBlockState());
        set(level, pos, x, y + 1, z + 1, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        set(level, pos, x, y, z + 1, Blocks.SMOOTH_STONE.defaultBlockState());
        set(level, pos, x, y, z + 2, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
    }

    private static void set(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
        level.setBlock(pos.set(x, y, z), s, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
    }
}
