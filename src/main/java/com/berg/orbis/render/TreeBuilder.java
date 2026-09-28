package com.berg.orbis.render;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Procedural trees written through a simple block sink, so the same code
 * works during chunk generation (ChunkAccess) and decoration (WorldGenLevel).
 * Leaves are placed PERSISTENT so they never decay, and only air/leaves are
 * overwritten so canopies never eat into buildings or roads.
 */
public final class TreeBuilder {

    private TreeBuilder() {}

    public enum Species { OAK, BIRCH, SPRUCE, PINE, JUNGLE, ACACIA, CHERRY, DARK_OAK, PALM, BUSH }

    @FunctionalInterface
    public interface Sink {
        /** @return false if the position may not be written (outside write zone). */
        boolean place(int x, int y, int z, BlockState state, boolean onlyIfAirOrLeaves);
    }

    public static Species fromCode(int code) {
        return switch (code & 0x0F) {
            case 1 -> Species.OAK;
            case 2 -> Species.BIRCH;
            case 3 -> Species.SPRUCE;
            case 4 -> Species.PINE;
            case 5 -> Species.JUNGLE;
            case 6 -> Species.ACACIA;
            case 7 -> Species.CHERRY;
            case 8 -> Species.DARK_OAK;
            case 9 -> Species.PALM;
            default -> null;
        };
    }

    /**
     * @param x,y,z   position of the lowest trunk block (y = ground + 1)
     * @param heightHint desired total height in blocks, or 0 for a natural size
     * @param seed    deterministic randomness source
     */
    public static void place(Sink sink, int x, int y, int z, Species species, int heightHint, long seed) {
        long h = Materials.mix(seed);
        int r1 = (int) (h % 1000);
        int r2 = (int) ((h >>> 20) % 1000);
        switch (species) {
            case OAK -> blobTree(sink, x, y, z, Blocks.OAK_LOG.defaultBlockState(), leaves(Blocks.OAK_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 5 + r1 % 3, 2 + (r2 % 3 == 0 ? 1 : 0), h);
            case BIRCH -> blobTree(sink, x, y, z, Blocks.BIRCH_LOG.defaultBlockState(), leaves(Blocks.BIRCH_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 6 + r1 % 3, 2, h);
            case DARK_OAK -> blobTree(sink, x, y, z, Blocks.DARK_OAK_LOG.defaultBlockState(), leaves(Blocks.DARK_OAK_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 6 + r1 % 3, 3 + (r2 % 2), h);
            case CHERRY -> blobTree(sink, x, y, z, Blocks.CHERRY_LOG.defaultBlockState(), leaves(Blocks.CHERRY_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 5 + r1 % 2, 3, h);
            case SPRUCE -> coneTree(sink, x, y, z, Blocks.SPRUCE_LOG.defaultBlockState(), leaves(Blocks.SPRUCE_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 8 + r1 % 5, false, h);
            case PINE -> coneTree(sink, x, y, z, Blocks.SPRUCE_LOG.defaultBlockState(), leaves(Blocks.SPRUCE_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 12 + r1 % 5, true, h);
            case JUNGLE -> flatTopTree(sink, x, y, z, Blocks.JUNGLE_LOG.defaultBlockState(), leaves(Blocks.JUNGLE_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 10 + r1 % 6, 3 + (r2 % 2), h);
            case ACACIA -> flatTopTree(sink, x, y, z, Blocks.ACACIA_LOG.defaultBlockState(), leaves(Blocks.ACACIA_LEAVES.defaultBlockState()),
                    heightHint > 0 ? heightHint : 6 + r1 % 3, 3, h);
            case PALM -> palmTree(sink, x, y, z, heightHint > 0 ? heightHint : 8 + r1 % 4, h);
            case BUSH -> bush(sink, x, y, z, h);
        }
    }

    private static BlockState leaves(BlockState s) {
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
    }

    /** Oak/birch style: straight trunk, roughly spherical canopy around the top. */
    private static void blobTree(Sink sink, int x, int y, int z, BlockState log, BlockState leaf, int height, int radius, long h) {
        int trunk = Math.max(3, height - 2);
        for (int i = 0; i < trunk; i++) sink.place(x, y + i, z, log, true);
        int top = y + trunk - 1;
        int canopyBottom = top - radius;
        for (int dy = -radius; dy <= radius; dy++) {
            int yy = top + dy;
            if (yy < canopyBottom) continue;
            double vr = dy <= 0 ? radius : radius - dy * 0.75;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz + (dy * dy) * 1.1);
                    if (d > vr + 0.35) continue;
                    if (d > vr - 0.4 && ((hash(h, dx, dy, dz) & 3) == 0)) continue; // ragged outline
                    if (dx == 0 && dz == 0 && dy <= 0) continue; // trunk
                    sink.place(x + dx, yy, z + dz, leaf, true);
                }
            }
        }
        sink.place(x, top + 1, z, leaf, true);
    }

    /** Spruce/pine: layered cone of decreasing radius. Pine keeps a bare trunk with a short crown. */
    private static void coneTree(Sink sink, int x, int y, int z, BlockState log, BlockState leaf, int height, boolean pine, long h) {
        for (int i = 0; i < height - 1; i++) sink.place(x, y + i, z, log, true);
        int top = y + height - 1;
        int canopyHeight = pine ? Math.max(4, height / 3) : Math.max(4, (int) (height * 0.7));
        int maxRadius = pine ? 2 : 3;
        for (int i = 0; i < canopyHeight; i++) {
            int yy = top - i;
            double frac = (double) i / canopyHeight;
            int radius = (int) Math.round(maxRadius * frac);
            if (!pine && i % 2 == 1 && radius > 0) radius -= 1; // classic spruce "tiered" look
            if (i == 0) radius = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 1) continue;
                    if (dx == 0 && dz == 0 && i > 0) continue;
                    sink.place(x + dx, yy, z + dz, leaf, true);
                }
            }
        }
        sink.place(x, top + 1, z, leaf, true);
    }

    /** Jungle/acacia: tall trunk with a wide, flat 2-layer canopy. */
    private static void flatTopTree(Sink sink, int x, int y, int z, BlockState log, BlockState leaf, int height, int radius, long h) {
        int trunk = Math.max(4, height - 2);
        for (int i = 0; i < trunk; i++) sink.place(x, y + i, z, log, true);
        int top = y + trunk - 1;
        for (int layer = 0; layer < 2; layer++) {
            int r = layer == 0 ? radius : radius - 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    if (dx == 0 && dz == 0 && layer == 0) continue;
                    sink.place(x + dx, top + layer, z + dz, leaf, true);
                }
            }
        }
        sink.place(x, top + 2, z, leaf, true);
    }

    private static void palmTree(Sink sink, int x, int y, int z, int height, long h) {
        BlockState log = Blocks.JUNGLE_LOG.defaultBlockState();
        BlockState leaf = leaves(Blocks.JUNGLE_LEAVES.defaultBlockState());
        int lean = (int) (h % 4); // 0..3 direction of a slight lean
        int cx = x, cz = z;
        for (int i = 0; i < height; i++) {
            if (i > height / 2 && i % 3 == 0) {
                if (lean == 0) cx++;
                else if (lean == 1) cx--;
                else if (lean == 2) cz++;
                else cz--;
            }
            sink.place(cx, y + i, cz, log, true);
        }
        int top = y + height;
        sink.place(cx, top, cz, leaf, true);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : dirs) {
            for (int k = 1; k <= 3; k++) {
                int drop = k == 3 ? 1 : 0;
                sink.place(cx + d[0] * k, top - drop, cz + d[1] * k, leaf, true);
            }
        }
    }

    private static void bush(Sink sink, int x, int y, int z, long h) {
        BlockState leaf = leaves(Blocks.OAK_LEAVES.defaultBlockState());
        sink.place(x, y, z, Blocks.OAK_LOG.defaultBlockState(), true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) == 2 && (hash(h, dx, 0, dz) & 1) == 0) continue;
                if (dx == 0 && dz == 0) continue;
                sink.place(x + dx, y, z + dz, leaf, true);
            }
        }
        sink.place(x, y + 1, z, leaf, true);
    }

    private static long hash(long h, int a, int b, int c) {
        return Materials.mix(h ^ (a * 0x9E3779B1L) ^ (b * 0x85EBCA77L) ^ (c * 0xC2B2AE3DL));
    }
}
