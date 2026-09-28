package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintenance niches in road tunnels: every 128 m along a tunnel, alternating sides, a one-deep alcove in the
 * wall with a chest of road-crew supplies (a few iron ingots, emeralds, an iron tool, torches, rails), so a
 * long, dark tunnel is worth walking. The loot table {@code orbisterrarum:chests/tunnel_depot} comes from the world's Orbis
 * datapack (see {@link com.berg.orbis.worldgen.Landmarks}).
 */
public final class Tunnels {

    public static final ResourceKey<LootTable> DEPOT = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath("orbisterrarum", "chests/tunnel_depot"));

    private Tunnels() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.tunnelLoot) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<int[]> placed = new ArrayList<>();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0) continue;
                RoadFeature rf = r.roadAt(idx);
                if (rf == null || !rf.tunnel || rf.layer >= 0 || rf.kind != RoadFeature.Kind.ROAD || rf.halfWidth < 1) continue;
                int y = r.roadY[idx];
                if (y == RegionRaster.NO_Y || r.roadDistAt(idx) != rf.halfWidth) continue;
                int t = r.roadTAt(idx);
                if (t != 64 && t != 192) continue; // every 128 m; 64 on one side of the tunnel, 192 on the other
                if (y + 4 >= cfg.maxY() || y <= cfg.minY + 1) continue;
                if (!level.getBlockState(pos.set(x, y + 1, z)).isAir()) continue; // the tunnel was not carved here (portal approach)
                // The wall: a 4-neighbour that is not part of this tunnel. Side 64 takes the first such neighbour
                // going round, side 192 the last, so consecutive niches alternate sides.
                int[] wall = null;
                for (int[] d : dirs) {
                    int nidx = r.index(x + d[0], z + d[1]);
                    if (nidx < 0 || r.roadAt(nidx) == rf) continue;
                    if (r.roadAt(nidx) != null || r.water[nidx] != 0 || (r.hasCoastline && r.isSea(nidx))) continue;
                    wall = d;
                    if (t == 64) break;
                }
                if (wall == null) continue;
                int wx = x + wall[0], wz = z + wall[1];
                boolean near = false;
                for (int[] p : placed) {
                    if (Math.abs(p[0] - wx) + Math.abs(p[1] - wz) < 8) near = true;
                }
                if (near) continue;
                if (!level.getBlockState(pos.set(wx, y + 1, wz)).isSolid() || !level.getBlockState(pos.set(wx, y + 3, wz)).isSolid()) continue;
                if (!level.getBlockState(pos.set(wx, y + 1, wz)).getFluidState().isEmpty()) continue;
                Direction facing = Direction.getNearest(-wall[0], 0, -wall[1], Direction.EAST);
                level.setBlock(pos.set(wx, y + 2, wz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(pos.set(wx, y + 1, wz), Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), 2);
                if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest) {
                    chest.setLootTable(DEPOT);
                    chest.setLootTableSeed(ColumnPainter.hash(wx, wz, 0x7D07) ^ y);
                    chest.setChanged();
                }
                placed.add(new int[]{wx, wz});
            }
        }
    }
}
