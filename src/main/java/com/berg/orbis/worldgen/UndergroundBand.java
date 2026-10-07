package com.berg.orbis.worldgen;

import com.berg.orbis.feature.RegionRaster;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Where vanilla's underground lives in a real-world column.
 *
 * Vanilla assumes the surface is around Y 64 and bedrock at Y -64. Here the
 * ground is wherever the real terrain puts it (Y -1700 at the coast, +900 on
 * Everest), so everything vanilla places by absolute Y (mineshafts, caves,
 * dungeons, geodes, strongholds) is moved as a whole: per chunk, vanilla Y 64
 * is mapped to the lowest ground within about 100 m, minus 100 blocks. The
 * band keeps vanilla's depth relationships, stays under every surface nearby
 * (no cave mouths in the middle of a city, no mineshaft in the sky) and is
 * reachable by digging straight down from anywhere.
 */
public final class UndergroundBand {

    /** Vanilla's surface reference. */
    public static final int VANILLA_SURFACE = 64;
    /** Vanilla's bedrock floor. */
    public static final int VANILLA_BOTTOM = -64;
    /** Blocks between the lowest nearby ground and the band top (under towns, roads and tunnels from layout 2 on). */
    public static final int DEPTH_BELOW_GROUND = 100;
    /**
     * Layout 2: the roof over the band where only nature lies within {@link #REACH} (no building or road). 100 blocks
     * of rock (200 m at 1:2) put every cave out of reach of anyone digging in the wild; 30 keeps a real slope or
     * field intact (nothing is carved within ten blocks of the surface) and the caves within reach.
     */
    public static final int DEPTH_IN_THE_WILD = 30;

    private static final int REACH = 96;
    private static final int STEP = 32;
    private static final int CACHE_LIMIT = 65536;

    private final WorldModel model;
    private final ConcurrentHashMap<Long, Integer> tops = new ConcurrentHashMap<>();

    UndergroundBand(WorldModel model) {
        this.model = model;
    }

    /** World Y that vanilla Y 64 maps to in this chunk. */
    public int top(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        Integer cached = tops.get(key);
        if (cached != null) return cached;
        int cx = (chunkX << 4) + 8, cz = (chunkZ << 4) + 8;
        int lowest = Integer.MAX_VALUE;
        for (int dx = -REACH; dx <= REACH; dx += STEP) {
            for (int dz = -REACH; dz <= REACH; dz += STEP) {
                lowest = Math.min(lowest, model.terrainHeight(cx + dx, cz + dz));
            }
        }
        int top = lowest - (model.cfg().undergroundVersion >= 2 ? roofDepth(cx, cz) : DEPTH_BELOW_GROUND);
        // Road tunnels are part of the ground the band must stay under: caves, mineshafts, geodes and monster
        // rooms would otherwise cut through them (the Fløyfjell tunnel runs 300 m below the mountain above it).
        int tunnel = lowestTunnel(cx, cz);
        if (tunnel != Integer.MAX_VALUE) top = Math.min(top, tunnel - TUNNEL_MARGIN);
        // The whole vanilla frame (-64..64) must stay above the bedrock floor.
        int floor = model.cfg().minY + 6 + (VANILLA_SURFACE - VANILLA_BOTTOM);
        if (top < floor) top = floor;
        if (tops.size() > CACHE_LIMIT) tops.clear();
        tops.put(key, top);
        return top;
    }

    /** The band top of a chunk if it is already known, else Integer.MIN_VALUE (never computes, never waits). */
    public int peek(int chunkX, int chunkZ) {
        Integer cached = tops.get(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
        return cached == null ? Integer.MIN_VALUE : cached;
    }

    /** Add this to a vanilla Y to get the world Y in this chunk. */
    public int offset(int chunkX, int chunkZ) {
        return top(chunkX, chunkZ) - VANILLA_SURFACE;
    }

    /**
     * Layout 2: the band's roof by how close the nearest building or road is (sampled every 16 blocks within REACH):
     * 100 blocks within 32 blocks of one, 30 from 96 blocks away, in between gradually so caves do not step at the
     * edge of a town. Without map data, the wild depth.
     */
    private int roofDepth(int cx, int cz) {
        if (model.regions() == null) return DEPTH_IN_THE_WILD;
        int size = model.cfg().regionSizeBlocks;
        double nearest = Double.MAX_VALUE;
        java.util.Map<Long, RegionRaster> rasters = new java.util.HashMap<>();
        for (int dx = -REACH; dx <= REACH; dx += 16) {
            for (int dz = -REACH; dz <= REACH; dz += 16) {
                int x = cx + dx, z = cz + dz;
                long key = ((long) Math.floorDiv(x, size) << 32) ^ (Math.floorDiv(z, size) & 0xFFFFFFFFL);
                RegionRaster r = rasters.computeIfAbsent(key, k -> model.cfg().waitForOsm
                        ? model.regions().futureForBlock(x, z).join()
                        : model.rasterIfLoaded(x, z));
                if (r == null) continue;
                int idx = r.index(x, z);
                if (idx < 0) continue;
                if (r.buildingAt(idx) != null || r.roadAt(idx) != null) nearest = Math.min(nearest, Math.hypot(dx, dz));
            }
        }
        if (nearest <= 32) return DEPTH_BELOW_GROUND;
        if (nearest >= REACH) return DEPTH_IN_THE_WILD;
        double t = (nearest - 32) / (REACH - 32);
        return (int) Math.round(DEPTH_BELOW_GROUND + (DEPTH_IN_THE_WILD - DEPTH_BELOW_GROUND) * t);
    }

    /** World Y of the band's vanilla Y 0 in this chunk, where deepslate starts (layout 2). */
    public int deepslateTop(int chunkX, int chunkZ) {
        return top(chunkX, chunkZ) - VANILLA_SURFACE;
    }

    /** Blocks the deepslate line rises and falls above the band's vanilla Y 0, as vanilla's transition (0..8). */
    private static final int EDGE_WAVE = 7;
    /** Size (blocks) of the wave's swells. */
    private static final double EDGE_CELL = 24;

    /**
     * Layout 2's deepslate line for a chunk's 16 x 16 columns (deepslate below it): the band's vanilla Y 0 blended
     * between the four nearest chunk centres, so it does not step at chunk edges, plus a gentle wave of 0..7 blocks.
     * Until 5 Oct 2026 each column took a random height of its own; that noise was some 80 MB of the 1:2 Bergen
     * world (stone and deepslate mixed block by block compress badly) and looked no more natural.
     */
    public int[][] deepslateLine(int chunkX, int chunkZ) {
        int[][] tops = new int[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) tops[i][j] = deepslateTop(chunkX - 1 + i, chunkZ - 1 + j);
        }
        int[][] line = new int[16][16];
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int lx = 0; lx < 16; lx++) {
            // Chunk-centre coordinates of the column: 0 at the centre of the chunk to the west, 1 at this chunk's.
            double fx = (lx + 8.5) / 16.0;
            int ix = (int) Math.floor(fx);
            double tx = fx - ix;
            for (int lz = 0; lz < 16; lz++) {
                double fz = (lz + 8.5) / 16.0;
                int iz = (int) Math.floor(fz);
                double tz = fz - iz;
                double north = tops[ix][iz] + (tops[ix + 1][iz] - tops[ix][iz]) * tx;
                double south = tops[ix][iz + 1] + (tops[ix + 1][iz + 1] - tops[ix][iz + 1]) * tx;
                double base = north + (south - north) * tz;
                double wave = waveNoise((minX + lx) / EDGE_CELL, (minZ + lz) / EDGE_CELL);
                line[lx][lz] = (int) Math.round(base + wave * EDGE_WAVE);
            }
        }
        return line;
    }

    /** Smooth value noise in 0..1 (one octave, smoothstep between hashed lattice points). */
    private static double waveNoise(double fx, double fz) {
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
        double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static double lattice(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ 0xD5D5D5D5L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Blocks between the lowest tunnel floor nearby and the band top. */
    private static final int TUNNEL_MARGIN = 24;

    /** Lowest road-tunnel floor in the regions within REACH of the column, or MAX_VALUE when there is none (or no map data). */
    private int lowestTunnel(int cx, int cz) {
        if (model.regions() == null) return Integer.MAX_VALUE;
        int size = model.cfg().regionSizeBlocks;
        int min = Integer.MAX_VALUE;
        for (int rx = Math.floorDiv(cx - REACH, size); rx <= Math.floorDiv(cx + REACH, size); rx++) {
            for (int rz = Math.floorDiv(cz - REACH, size); rz <= Math.floorDiv(cz + REACH, size); rz++) {
                int x = rx * size + size / 2, z = rz * size + size / 2;
                RegionRaster r = model.cfg().waitForOsm
                        ? model.regions().futureForBlock(x, z).join()
                        : model.rasterIfLoaded(x, z);
                if (r != null) min = Math.min(min, r.minTunnelY);
            }
        }
        return min;
    }
}
