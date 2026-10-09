package com.berg.orbis.cubic;

import java.util.Iterator;
import java.util.LinkedHashSet;

import com.berg.orbis.map.BlockMapService;
import com.berg.orbis.map.BlockMapStore;
import io.github.opencubicchunks.cubicchunks.api.CubeTerrain;
import io.github.opencubicchunks.cubicchunks.api.CubicApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world map's Minecraft layer in a cubic world: its chunks hold no blocks, so the map is drawn from the cubes. As cubes load and
 * unload, their cube column is redrawn (a few columns a tick) from the cubes loaded there: each block column's top block is the first one
 * under the highest loaded cubes that are empty, so a column is only drawn while what lies over it is known (a player deep underground,
 * with the cubes overhead not loaded, does not put cave ceilings on the map; that part waits until someone is near the surface).
 */
public final class CubeMapDrawer implements CubicApi.CubeListener {
    private static final CubeMapDrawer INSTANCE = new CubeMapDrawer();
    private static final int COLUMNS_PER_TICK = 6;

    /** Cube columns (cube x, z packed) waiting to be drawn; server thread only. */
    private final LinkedHashSet<Long> waiting = new LinkedHashSet<>();
    private ServerLevel overworld;

    private CubeMapDrawer() {
    }

    public static void register() {
        CubicApi.addCubeListener(INSTANCE);
        ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.tick());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            INSTANCE.waiting.clear();
            INSTANCE.overworld = null;
        });
    }

    @Override
    public void onCubeLoaded(ServerLevel level, int cubeX, int cubeY, int cubeZ) {
        this.wait(level, cubeX, cubeZ);
    }

    @Override
    public void onCubeUnloaded(ServerLevel level, int cubeX, int cubeY, int cubeZ) {
        this.wait(level, cubeX, cubeZ); // whatever was built there, as the chunk generator's map does on unload
    }

    private void wait(ServerLevel level, int cubeX, int cubeZ) {
        if (level.dimension() != Level.OVERWORLD || BlockMapService.store() == null) return;
        this.overworld = level;
        this.waiting.add(ChunkPos.pack(cubeX, cubeZ));
    }

    private void tick() {
        BlockMapStore store = BlockMapService.store();
        ServerLevel level = this.overworld;
        if (store == null || level == null) {
            this.waiting.clear();
            return;
        }
        Iterator<Long> it = this.waiting.iterator();
        for (int n = 0; n < COLUMNS_PER_TICK && it.hasNext(); n++) {
            long key = it.next();
            it.remove();
            try {
                draw(store, level, ChunkPos.getX(key), ChunkPos.getZ(key));
            } catch (RuntimeException e) {
                System.err.println("[orbis] Map of cube column " + ChunkPos.getX(key) + "," + ChunkPos.getZ(key) + ": " + e);
            }
        }
    }

    /** Draws the chunks of a cube column whose top blocks the loaded cubes show. */
    private static void draw(BlockMapStore store, ServerLevel level, int cubeX, int cubeZ) {
        int size = CubeTerrain.SIZE;
        int minCube = Math.floorDiv(CubicApi.minY(level), size), maxCube = Math.floorDiv(CubicApi.maxY(level), size);
        int top = Integer.MIN_VALUE;
        for (int cy = maxCube; cy >= minCube; cy--) {
            if (CubicApi.isCubeLoaded(level, cubeX, cy, cubeZ)) {
                top = cy;
                break;
            }
        }
        if (top == Integer.MIN_VALUE) return;
        int bottom = top;
        while (bottom - 1 >= minCube && CubicApi.isCubeLoaded(level, cubeX, bottom - 1, cubeZ)) bottom--;
        int first = top;
        while (first >= bottom && CubicApi.isCubeEmpty(level, cubeX, first, cubeZ)) first--;
        // The highest loaded cube holding anything must have an empty loaded cube over it: else what lies above is not known.
        if (first < bottom || first == top) return;
        int scanFrom = first * size + size - 1, scanTo = bottom * size;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int chunks = size / 16;
        for (int i = 0; i < chunks; i++) {
            for (int j = 0; j < chunks; j++) {
                int chunkX = cubeX * chunks + i, chunkZ = cubeZ * chunks + j;
                int minX = chunkX << 4, minZ = chunkZ << 4;
                int[] tops = new int[256];
                boolean known = true;
                for (int x = 0; x < 16 && known; x++) {
                    for (int z = 0; z < 16; z++) {
                        int y = scanFrom;
                        while (y >= scanTo && level.getBlockState(p.set(minX + x, y, minZ + z)).isAir()) y--;
                        if (y < scanTo) { // air all the way down the loaded cubes: its ground is not loaded
                            known = false;
                            break;
                        }
                        tops[z * 16 + x] = y;
                    }
                }
                if (!known) continue;
                store.draw(chunkX, chunkZ, new BlockMapStore.Column() {
                    @Override
                    public BlockState at(int x, int y, int z) {
                        return level.getBlockState(p.set(minX + x, y, minZ + z));
                    }

                    @Override
                    public int top(int x, int z) {
                        return tops[z * 16 + x];
                    }

                    @Override
                    public Holder<Biome> biome(int x, int y, int z) {
                        return level.getUncachedNoiseBiome((minX + x) >> 2, y >> 2, (minZ + z) >> 2);
                    }
                });
            }
        }
    }
}
