package com.berg.orbis.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Ore veins and stone patches placed by depth below the real surface.
 *
 * Vanilla's ore features live at absolute Y ranges tuned for a world whose
 * ground sits around Y 64; here the ground is anywhere between Y -1700 and
 * Y 2000, so vanilla placement would leave most of the world empty. This
 * mirrors vanilla's densities and vein sizes, but measured downward from the
 * surface of each chunk, so a mine under any city finds coal first, iron and
 * copper a little deeper, gold, redstone and lapis further down, and diamonds
 * deepest. Deepslate variants are used below the painter's deepslate line.
 */
public final class OreGenerator {

    private record Vein(Block ore, Block deepOre, int veinsPerChunk, double extraChance, int minDepth, int maxDepth, int size) {}

    private static final Vein[] VEINS = {
            new Vein(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 9, 0.0, 5, 90, 12),
            new Vein(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 7, 0.0, 6, 130, 8),
            new Vein(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 6, 0.0, 6, 80, 9),
            new Vein(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 2, 0.5, 40, 220, 7),
            new Vein(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 4, 0.0, 90, 280, 8),
            new Vein(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 1, 0.6, 40, 170, 6),
            new Vein(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 1, 0.7, 130, 320, 5),
            new Vein(Blocks.EMERALD_ORE, Blocks.EMERALD_ORE, 0, 0.12, 20, 120, 3),
    };

    /** Larger patches of granite/diorite/andesite/tuff/gravel for the vanilla look of a mine. */
    private static final Vein[] PATCHES = {
            new Vein(Blocks.GRANITE, Blocks.TUFF, 1, 0.5, 8, 200, 28),
            new Vein(Blocks.DIORITE, Blocks.TUFF, 1, 0.5, 8, 200, 28),
            new Vein(Blocks.ANDESITE, Blocks.TUFF, 1, 0.5, 8, 200, 28),
            new Vein(Blocks.GRAVEL, Blocks.GRAVEL, 1, 0.3, 8, 160, 20),
            new Vein(Blocks.DIRT, Blocks.TUFF, 1, 0.3, 6, 60, 18),
    };

    private OreGenerator() {}

    /**
     * @param terrain  ground Y per local column (16x16)
     * @param deepslateTop Y below which deepslate variants are used
     */
    public static void place(ChunkAccess chunk, int[][] terrain, int deepslateTop, int minY, long seed) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        place(new CaveCarver.Grid() {
            @Override
            public BlockState get(int x, int y, int z) {
                return chunk.getBlockState(pos.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                chunk.setBlockState(pos.set(x, y, z), state, 0);
            }
        }, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), terrain, deepslateTop, minY, seed);
    }

    /**
     * The same for the chunk from minX, minZ through any block access (a cubic world's cube, which keeps what falls in it: every vein
     * reads only blocks it writes, so the part in a cube is the same as in the whole chunk).
     */
    public static void place(CaveCarver.Grid grid, int minX, int minZ, int[][] terrain, int deepslateTop, int minY, long seed) {
        RandomSource random = RandomSource.create(seed ^ ((long) minX * 0x5DEECE66DL) ^ ((long) minZ * 0x9E3779B97F4A7C15L));
        placeAll(grid, terrain, deepslateTop, minY, random, minX, minZ, PATCHES);
        placeAll(grid, terrain, deepslateTop, minY, random, minX, minZ, VEINS);
    }

    private static void placeAll(CaveCarver.Grid grid, int[][] terrain, int deepslateTop, int minY, RandomSource random,
                                 int minX, int minZ, Vein[] veins) {
        for (Vein v : veins) {
            int count = v.veinsPerChunk + (random.nextDouble() < v.extraChance ? 1 : 0);
            for (int i = 0; i < count; i++) {
                int lx = random.nextInt(16), lz = random.nextInt(16);
                int surface = terrain[lx][lz];
                int depth = v.minDepth + random.nextInt(Math.max(1, v.maxDepth - v.minDepth));
                int y = surface - depth;
                if (y <= minY + 4) continue;
                placeBlob(grid, random, minX, minZ, minX + lx, y, minZ + lz, v, deepslateTop, minY);
            }
        }
    }

    /** A compact blob grown by random walk from the centre, replacing only stone and deepslate. */
    private static void placeBlob(CaveCarver.Grid grid, RandomSource random, int minLx, int minLz, int cx, int cy, int cz,
                                  Vein v, int deepslateTop, int minY) {
        int x = cx, y = cy, z = cz;
        int radius = v.size > 12 ? 3 : 2;
        for (int i = 0; i < v.size; i++) {
            // stay inside this chunk and near the centre
            if (x >= minLx && x < minLx + 16 && z >= minLz && z < minLz + 16 && y > minY + 1) {
                BlockState here = grid.get(x, y, z);
                // Ore in any of the rocks the bedrock map lays down (granite, diorite, andesite, calcite, sandstone as
                // stone; tuff and basalt as deepslate), not only in plain stone.
                if (here.is(Blocks.STONE) || here.is(Blocks.GRANITE) || here.is(Blocks.DIORITE) || here.is(Blocks.ANDESITE)
                        || here.is(Blocks.CALCITE) || here.is(Blocks.SANDSTONE)) {
                    grid.set(x, y, z, (y < deepslateTop ? v.deepOre : v.ore).defaultBlockState());
                } else if (here.is(Blocks.DEEPSLATE) || here.is(Blocks.TUFF) || here.is(Blocks.SMOOTH_BASALT)) {
                    grid.set(x, y, z, v.deepOre.defaultBlockState());
                }
            }
            int step = random.nextInt(6);
            switch (step) {
                case 0 -> x++;
                case 1 -> x--;
                case 2 -> y++;
                case 3 -> y--;
                case 4 -> z++;
                default -> z--;
            }
            if (Math.abs(x - cx) > radius) x = cx + Integer.signum(x - cx) * radius;
            if (Math.abs(z - cz) > radius) z = cz + Integer.signum(z - cz) * radius;
            if (Math.abs(y - cy) > radius) y = cy + Integer.signum(y - cy) * radius;
        }
    }
}
