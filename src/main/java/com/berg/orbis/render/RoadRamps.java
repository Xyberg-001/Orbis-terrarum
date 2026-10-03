package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ramps where a paved road climbs: a road or pavement column whose neighbour on the road is one block higher gets
 * a half slab of its own surface on top, so a street on a hillside rises in half steps you can walk up instead of
 * one-block terraces. Outdoor stairways (highway=steps) get stair blocks facing uphill instead. The slab or stairs
 * match the surface: its own family where Minecraft has one (26.3's concrete slabs for asphalt and pavements), the
 * closest colour otherwise (RoofBlocks). Dirt and gravel tracks, bridges, tunnels, railways and runways stay as
 * they are.
 */
public final class RoadRamps {

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private RoadRamps() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.roadRamps || !cfg.generateRoads) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0 || r.building[idx] != 0 || r.water[idx] != 0) continue;
                RoadFeature rf = r.roadAt(idx);
                if (rf == null || !rampable(rf)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y <= cfg.minY || y + 1 >= cfg.maxY()) continue;
                pos.set(x, y, z);
                BlockState surface = level.getBlockState(pos);
                if (!paved(surface, rf)) continue;
                pos.set(x, y + 1, z);
                if (!level.getBlockState(pos).isAir()) continue;
                Direction up = null;
                for (Direction d : SIDES) {
                    int nx = x + d.getStepX(), nz = z + d.getStepZ();
                    int nidx = r.index(nx, nz);
                    if (nidx < 0 || r.road[nidx] == 0 || r.building[nidx] != 0) continue;
                    if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1 != y + 1) continue;
                    pos.set(nx, y + 1, nz);
                    if (!level.getBlockState(pos).isSolidRender()) continue; // a lamp or sign, not the road
                    up = d;
                    break;
                }
                if (up == null) continue;
                BlockState ramp;
                if (rf.kind == RoadFeature.Kind.STEPS) {
                    BlockState stairs = RoofBlocks.stairsFor(surface);
                    if (stairs == null) continue;
                    ramp = stairs.setValue(StairBlock.FACING, up).setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
                } else {
                    // A lane marking on a ramp takes the road's own slab (the marking colour has no slab before 26.3).
                    ramp = RoofBlocks.slabFor(surface == rf.markingBlock && rf.surface != null ? rf.surface : surface);
                    if (ramp == null) continue;
                }
                pos.set(x, y + 1, z);
                level.setBlock(pos, ramp, Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean rampable(RoadFeature rf) {
        if (rf.bridge || rf.tunnel || rf.isRail() || rf.elevatedDeck()) return false;
        return switch (rf.kind) {
            case ROAD, PATH, STEPS -> true;
            default -> false; // runways, taxiways, piers, farm tracks
        };
    }

    /** Whether the column's top block is this road's paved surface (not dirt or gravel, not furniture on it). */
    private static boolean paved(BlockState s, RoadFeature rf) {
        if (s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.SAND) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.MOSS_BLOCK)) return false;
        return s == rf.surface || s == rf.sidewalkBlock || s == rf.markingBlock || s.isSolidRender();
    }
}
