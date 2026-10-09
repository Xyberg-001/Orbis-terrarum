package com.berg.orbis.worldgen;

import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.BitSet;
import java.util.function.IntBinaryOperator;

/**
 * Vanilla's cave tunnels (the "worm" carver: rooms, branching tunnels,
 * lava at the bottom), re-implemented so that they can run inside the
 * {@link UndergroundBand} instead of at absolute vanilla Y.
 *
 * The algorithm is vanilla's {@code CaveWorldCarver}: every chunk within
 * eight chunks of the one being carved is a potential tunnel origin, seeded
 * from the world seed and its own position, so a tunnel is reproduced
 * identically by every chunk it passes through. Origins are drawn in the
 * vanilla frame (Y -56..40) and shifted by the origin chunk's band offset;
 * nothing is ever carved above the band top or within ten blocks of a
 * column's real surface, so no tunnel can open in a street or under a
 * building. Vanilla's aquifers do not exist here; below vanilla Y -56 the
 * carved space is lava, as in vanilla.
 */
public final class CaveCarver {

    /** Block access for the chunk being carved (world coordinates). */
    public interface Grid {
        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);
    }

    private static final int ORIGIN_RANGE_CHUNKS = 8;      // vanilla: tunnels reach up to 112 blocks from their origin
    private static final int TUNNEL_REACH = 7 * 16;         // vanilla: sectionToBlockCoord(range * 2 - 1)
    private static final int CAVE_BOUND = 15;               // vanilla getCaveBound()
    private static final int LAVA_LEVEL = -56;              // vanilla: above_bottom 8
    private static final int ORIGIN_MIN_Y = -56;            // vanilla: above_bottom 8
    private static final int ORIGIN_MAX_Y = 40;
    /**
     * How far below its band top a tunnel or room can carve: from the lowest origin, a tunnel sinks at most a block a step for its whole
     * length (branches continue the same count), plus its widest radius.
     */
    private static final int DEEPEST_BELOW_TOP = UndergroundBand.VANILLA_SURFACE - ORIGIN_MIN_Y + TUNNEL_REACH + 32;             // vanilla: 180 (cave) / 47 (extra); cut so caves stay in the band
    /** Vanilla: 0.15 for "cave" spread over 236 blocks of Y, 0.07 for "cave_extra_underground" over 103. */
    private static final float[] PROBABILITY = {0.10f, 0.07f};

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private CaveCarver() {
    }

    /**
     * Carves chunk (chunkX, chunkZ).
     *
     * @param seed      world seed
     * @param floorY    lowest Y that may be carved (just above bedrock)
     * @param bandTop   (chunkX, chunkZ) -> world Y of vanilla Y 64 in that chunk
     * @param ceiling   per column (lx * 16 + lz) the highest Y that may be carved
     * @param grid      block access
     */
    public static void carve(long seed, int floorY, int chunkX, int chunkZ, IntBinaryOperator bandTop, int[] ceiling, Grid grid) {
        carve(seed, floorY, chunkX, chunkZ, bandTop, ceiling, grid, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Carves only the blocks of chunk (chunkX, chunkZ) from clipMinY to clipMaxY (a cubic world's cube): the same blocks as carving the
     * whole chunk, since a tunnel reads only the blocks it carves.
     */
    public static void carve(long seed, int floorY, int chunkX, int chunkZ, IntBinaryOperator bandTop, int[] ceiling, Grid grid,
                             int clipMinY, int clipMaxY) {
        int top = bandTop.applyAsInt(chunkX, chunkZ);
        if (clipMaxY < floorY || clipMinY > top) return;
        if (clipMaxY < top - DEEPEST_BELOW_TOP) {
            // Deep under this chunk's band: only a tunnel from a chunk whose band lies deeper could reach here.
            int lowestTop = top;
            for (int ox = chunkX - ORIGIN_RANGE_CHUNKS; ox <= chunkX + ORIGIN_RANGE_CHUNKS; ox++) {
                for (int oz = chunkZ - ORIGIN_RANGE_CHUNKS; oz <= chunkZ + ORIGIN_RANGE_CHUNKS; oz++) {
                    lowestTop = Math.min(lowestTop, bandTop.applyAsInt(ox, oz));
                }
            }
            if (clipMaxY < lowestTop - DEEPEST_BELOW_TOP) return;
        }
        Chunk chunk = new Chunk(grid, chunkX, chunkZ, floorY, top, ceiling, LAVA_LEVEL + (top - UndergroundBand.VANILLA_SURFACE),
                clipMinY, clipMaxY);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        for (int ox = chunkX - ORIGIN_RANGE_CHUNKS; ox <= chunkX + ORIGIN_RANGE_CHUNKS; ox++) {
            for (int oz = chunkZ - ORIGIN_RANGE_CHUNKS; oz <= chunkZ + ORIGIN_RANGE_CHUNKS; oz++) {
                for (int carver = 0; carver < PROBABILITY.length; carver++) {
                    random.setLargeFeatureSeed(seed + carver, ox, oz);
                    if (random.nextFloat() > PROBABILITY[carver]) continue;
                    int offset = bandTop.applyAsInt(ox, oz) - UndergroundBand.VANILLA_SURFACE;
                    carveFromOrigin(chunk, random, ox, oz, offset);
                }
            }
        }
    }

    // ---- vanilla CaveWorldCarver.carve ----------------------------------------------

    private static void carveFromOrigin(Chunk chunk, RandomSource random, int ox, int oz, int offset) {
        int tunnels = random.nextInt(random.nextInt(random.nextInt(CAVE_BOUND) + 1) + 1);
        for (int k = 0; k < tunnels; k++) {
            double x = (ox << 4) + random.nextInt(16);
            double y = Mth.randomBetweenInclusive(random, ORIGIN_MIN_Y, ORIGIN_MAX_Y) + offset;
            double z = (oz << 4) + random.nextInt(16);
            double hMul = uniform(random, 0.7f, 1.4f);
            double vMul = uniform(random, 0.8f, 1.3f);
            double floorLevel = uniform(random, -1.0f, -0.4f);
            int count = 1;
            if (random.nextInt(4) == 0) {
                double yScale = uniform(random, 0.1f, 0.9f);
                float radius = 1.0f + random.nextFloat() * 6.0f;
                room(chunk, x, y, z, radius, yScale, floorLevel);
                count += random.nextInt(4);
            }
            for (int p = 0; p < count; p++) {
                float yaw = random.nextFloat() * (float) (Math.PI * 2);
                float pitch = (random.nextFloat() - 0.5f) / 4.0f;
                float thickness = thickness(random);
                int length = TUNNEL_REACH - random.nextInt(TUNNEL_REACH / 4);
                tunnel(chunk, random.nextLong(), x, y, z, hMul, vMul, thickness, yaw, pitch, 0, length, 1.0, floorLevel);
            }
        }
    }

    private static float uniform(RandomSource random, float min, float max) {
        return random.nextFloat() * (max - min) + min;
    }

    private static float thickness(RandomSource random) {
        float f = random.nextFloat() * 2.0f + random.nextFloat();
        if (random.nextInt(10) == 0) f *= random.nextFloat() * random.nextFloat() * 3.0f + 1.0f;
        return f;
    }

    private static void room(Chunk chunk, double x, double y, double z, float radius, double yScale, double floorLevel) {
        double d = 1.5 + radius;
        ellipsoid(chunk, x + 1.0, y, z, d, d * yScale, floorLevel);
    }

    private static void tunnel(Chunk chunk, long seed, double x, double y, double z, double hMul, double vMul, float thickness,
                               float yaw, float pitch, int branchIndex, int branchCount, double yScale, double floorLevel) {
        RandomSource random = new LegacyRandomSource(seed);
        int split = random.nextInt(branchCount / 2) + branchCount / 4;
        boolean steep = random.nextInt(6) == 0;
        float yawDelta = 0.0f;
        float pitchDelta = 0.0f;
        for (int j = branchIndex; j < branchCount; j++) {
            double radius = 1.5 + Mth.sin((float) Math.PI * j / branchCount) * thickness;
            double vRadius = radius * yScale;
            float cosPitch = Mth.cos(pitch);
            x += Mth.cos(yaw) * cosPitch;
            y += Mth.sin(pitch);
            z += Mth.sin(yaw) * cosPitch;
            pitch *= steep ? 0.92f : 0.7f;
            pitch += pitchDelta * 0.1f;
            yaw += yawDelta * 0.1f;
            pitchDelta *= 0.9f;
            yawDelta *= 0.75f;
            pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;
            if (j == split && thickness > 1.0f) {
                tunnel(chunk, random.nextLong(), x, y, z, hMul, vMul, random.nextFloat() * 0.5f + 0.5f,
                        yaw - (float) (Math.PI / 2), pitch / 3.0f, j, branchCount, 1.0, floorLevel);
                tunnel(chunk, random.nextLong(), x, y, z, hMul, vMul, random.nextFloat() * 0.5f + 0.5f,
                        yaw + (float) (Math.PI / 2), pitch / 3.0f, j, branchCount, 1.0, floorLevel);
                return;
            }
            if (random.nextInt(4) != 0) {
                if (!canReach(chunk, x, z, j, branchCount, thickness)) return;
                ellipsoid(chunk, x, y, z, radius * hMul, vRadius * vMul, floorLevel);
            }
        }
    }

    private static boolean canReach(Chunk chunk, double x, double z, int branchIndex, int branchCount, float thickness) {
        double dx = x - chunk.midX;
        double dz = z - chunk.midZ;
        double remaining = branchCount - branchIndex;
        double reach = thickness + 2.0f + 16.0f;
        return dx * dx + dz * dz - remaining * remaining <= reach * reach;
    }

    private static void ellipsoid(Chunk chunk, double x, double y, double z, double hRadius, double vRadius, double floorLevel) {
        double limit = 16.0 + hRadius * 2.0;
        if (Math.abs(x - chunk.midX) > limit || Math.abs(z - chunk.midZ) > limit) return;
        int minLx = Math.max(Mth.floor(x - hRadius) - chunk.minX - 1, 0);
        int maxLx = Math.min(Mth.floor(x + hRadius) - chunk.minX, 15);
        int minY = Math.max(Mth.floor(y - vRadius) - 1, chunk.floorY);
        int maxY = Math.min(Mth.floor(y + vRadius) + 1, chunk.clipMaxY);
        int lowest = Math.max(minY + 1, chunk.clipMinY);
        if (lowest > maxY) return;
        int minLz = Math.max(Mth.floor(z - hRadius) - chunk.minZ - 1, 0);
        int maxLz = Math.min(Mth.floor(z + hRadius) - chunk.minZ, 15);
        for (int lx = minLx; lx <= maxLx; lx++) {
            int bx = chunk.minX + lx;
            double nx = (bx + 0.5 - x) / hRadius;
            for (int lz = minLz; lz <= maxLz; lz++) {
                int bz = chunk.minZ + lz;
                double nz = (bz + 0.5 - z) / hRadius;
                if (nx * nx + nz * nz >= 1.0) continue;
                int columnCeiling = Math.min(maxY, chunk.ceiling[lx * 16 + lz]);
                for (int by = columnCeiling; by >= lowest; by--) {
                    double ny = (by - 0.5 - y) / vRadius;
                    if (ny <= floorLevel || nx * nx + ny * ny + nz * nz >= 1.0) continue;
                    chunk.carve(lx, by, lz, bx, bz);
                }
            }
        }
    }

    /**
     * Blocks the carver may remove: vanilla's overworld carver list as of 26.2 (26.3 dropped the tag), plus the plain
     * rock the mod lays down, for offline tests.
     */
    static boolean replaceable(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.SNOW) || state.is(Blocks.WATER) || state.is(Blocks.SUSPICIOUS_GRAVEL)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.PACKED_ICE) || state.is(Blocks.RAW_IRON_BLOCK) || state.is(Blocks.RAW_COPPER_BLOCK)
                || state.is(Blocks.CINNABAR) || state.is(Blocks.SULFUR) || state.is(Blocks.POTENT_SULFUR);
    }

    private static final class Chunk {
        final Grid grid;
        final int minX, minZ, midX, midZ, floorY, top, lavaY, clipMinY, clipMaxY;
        final int[] ceiling;
        final int height;
        final BitSet mask;

        Chunk(Grid grid, int chunkX, int chunkZ, int floorY, int top, int[] ceiling, int lavaY, int clipMinY, int clipMaxY) {
            this.grid = grid;
            this.minX = chunkX << 4;
            this.minZ = chunkZ << 4;
            this.midX = minX + 8;
            this.midZ = minZ + 8;
            this.floorY = floorY;
            this.top = top;
            this.ceiling = ceiling;
            this.lavaY = lavaY;
            // the blocks that may be carved: the band from the floor to its top, within the clip
            this.clipMinY = Math.max(floorY, clipMinY);
            this.clipMaxY = Math.min(top, clipMaxY);
            this.height = Math.max(1, this.clipMaxY - this.clipMinY + 1);
            this.mask = new BitSet(256 * height);
        }

        void carve(int lx, int y, int lz, int bx, int bz) {
            if (y < clipMinY || y > clipMaxY) return;
            int bit = (lx * 16 + lz) * height + (y - clipMinY);
            if (mask.get(bit)) return;
            mask.set(bit);
            BlockState state = grid.get(bx, y, bz);
            if (!replaceable(state)) return;
            grid.set(bx, y, bz, y <= lavaY ? LAVA : AIR);
        }
    }
}
