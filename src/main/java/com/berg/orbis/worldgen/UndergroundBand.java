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
    /** Blocks between the lowest nearby ground and the band top. */
    public static final int DEPTH_BELOW_GROUND = 100;

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
        int top = lowest - DEPTH_BELOW_GROUND;
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

    /** Add this to a vanilla Y to get the world Y in this chunk. */
    public int offset(int chunkX, int chunkZ) {
        return top(chunkX, chunkZ) - VANILLA_SURFACE;
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
