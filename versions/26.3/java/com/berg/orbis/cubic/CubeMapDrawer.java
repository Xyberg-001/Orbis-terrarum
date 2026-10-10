package com.berg.orbis.cubic;

import java.util.Iterator;
import java.util.LinkedHashSet;

import com.berg.orbis.map.BlockMapService;
import com.berg.orbis.map.BlockMapStore;
import io.github.opencubicchunks.cubicchunks.api.CubeTerrain;
import io.github.opencubicchunks.cubicchunks.api.CubicApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world map's Minecraft layer in a cubic world: its chunks hold no blocks, so the map is drawn from the cubes. As cubes load and
 * unload, their cube column is redrawn from its cubes (loaded first, else saved: {@link #fillCubeColumn}), on a background thread of its
 * own: drawn on the server thread, a column waited for the map's region file (a second and more while flying). A column is only drawn
 * where the cube over its ground is known to be empty, so a player deep underground does not put cave ceilings on the map.
 */
public final class CubeMapDrawer implements CubicApi.CubeListener {
    private static final CubeMapDrawer INSTANCE = new CubeMapDrawer();
    /** Cube columns handed to the drawing thread at most at once (the rest wait in {@link #waiting}). */
    private static final int MAX_IN_FLIGHT = 32;
    private static final java.util.concurrent.ExecutorService DRAWING = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-cube-map");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();

    /** Cube columns (cube x, z packed) waiting to be drawn; server thread only. */
    private final LinkedHashSet<Long> waiting = new LinkedHashSet<>();
    private ServerLevel overworld;

    private CubeMapDrawer() {
    }

    public static void register() {
        CubicApi.addCubeListener(INSTANCE);
        BlockMapStore.cubicFiller = CubeMapDrawer::fillFromSavedCubes;
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
        int size = CubeTerrain.SIZE;
        int minCube = Math.floorDiv(CubicApi.minY(level), size), maxCube = Math.floorDiv(CubicApi.maxY(level), size);
        Iterator<Long> it = this.waiting.iterator();
        while (it.hasNext() && this.inFlight.get() < MAX_IN_FLIGHT) {
            long key = it.next();
            it.remove();
            int cubeX = ChunkPos.getX(key), cubeZ = ChunkPos.getZ(key);
            this.inFlight.incrementAndGet();
            DRAWING.execute(() -> {
                try {
                    fillCubeColumn(store, level, cubeX, cubeZ, minCube, maxCube);
                } catch (RuntimeException e) {
                    System.err.println("[orbis] Map of cube column " + cubeX + "," + cubeZ + ": " + e);
                } finally {
                    this.inFlight.decrementAndGet();
                }
            });
        }
    }

    /**
     * Draws a region (32 x 32 chunks) from its cubes, loaded or saved, the first time it is looked at on the map (the map's worker thread):
     * the places visited before the map drew cubic worlds, or out of reach of the server's loaded cubes now. Each cube column from the top:
     * the first cube that holds anything, under one that was generated empty, holds the top blocks (lower cubes for columns it leaves empty).
     */
    private static void fillFromSavedCubes(BlockMapStore store, int rx, int rz) {
        ServerLevel level = INSTANCE.overworld != null ? INSTANCE.overworld : null;
        if (level == null) {
            var server = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
            if (server instanceof net.minecraft.server.MinecraftServer s) level = s.overworld();
        }
        if (level == null || !CubicApi.isCubic(level)) return;
        int size = CubeTerrain.SIZE, perRegion = 512 / size;
        int minCube = Math.floorDiv(CubicApi.minY(level), size), maxCube = Math.floorDiv(CubicApi.maxY(level), size);
        // Nothing saved there (Cubic Chunks keeps 16 x 16 x 16 cubes a file, one map region across): skip it without asking the
        // generator for ground heights (which could fetch elevation data for places no one went).
        java.nio.file.Path files = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("dimensions/minecraft/overworld/region3d");
        boolean any = false;
        for (int ry = Math.floorDiv(minCube, 16); ry <= Math.floorDiv(maxCube, 16) && !any; ry++) {
            any = java.nio.file.Files.exists(files.resolve(rx + "." + ry + "." + rz + ".3dr"));
        }
        if (!any) return;
        for (int i = 0; i < perRegion; i++) {
            for (int j = 0; j < perRegion; j++) {
                fillCubeColumn(store, level, rx * perRegion + i, rz * perRegion + j, minCube, maxCube);
            }
        }
    }

    private static void fillCubeColumn(BlockMapStore store, ServerLevel level, int cubeX, int cubeZ, int minCube, int maxCube) {
        int size = CubeTerrain.SIZE;
        java.util.Map<Integer, CubicApi.CubeBlocks> cubes = new java.util.HashMap<>();
        int surfaceCube = surfaceCube(level, cubes, cubeX, cubeZ, minCube, maxCube);
        if (surfaceCube == Integer.MIN_VALUE) return;
        int chunks = size / 16;
        for (int i = 0; i < chunks; i++) {
            for (int j = 0; j < chunks; j++) {
                int chunkX = cubeX * chunks + i, chunkZ = cubeZ * chunks + j;
                int minX = chunkX << 4, minZ = chunkZ << 4;
                int[] tops = new int[256];
                boolean known = true;
                for (int x = 0; x < 16 && known; x++) {
                    for (int z = 0; z < 16; z++) {
                        int found = Integer.MIN_VALUE;
                        for (int cy = surfaceCube; cy >= minCube && found == Integer.MIN_VALUE; cy--) {
                            CubicApi.CubeBlocks cube = cubes.get(cy);
                            if (cube == null) {
                                var read = CubicApi.readCube(level, cubeX, cy, cubeZ);
                                if (read.isEmpty()) break;
                                cube = read.get();
                                cubes.put(cy, cube);
                            }
                            for (int y = cy * size + size - 1; y >= cy * size; y--) {
                                if (!cube.getBlock(minX + x, y, minZ + z).isAir()) {
                                    found = y;
                                    break;
                                }
                            }
                        }
                        if (found == Integer.MIN_VALUE) {
                            known = false;
                            break;
                        }
                        tops[z * 16 + x] = found;
                    }
                }
                if (!known) continue;
                ServerLevel l = level;
                store.draw(chunkX, chunkZ, new BlockMapStore.Column() {
                    @Override
                    public BlockState at(int x, int y, int z) {
                        CubicApi.CubeBlocks cube = cubes.get(Math.floorDiv(y, size));
                        return cube == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : cube.getBlock(minX + x, y, minZ + z);
                    }

                    @Override
                    public int top(int x, int z) {
                        return tops[z * 16 + x];
                    }

                    @Override
                    public Holder<Biome> biome(int x, int y, int z) {
                        return l.getUncachedNoiseBiome((minX + x) >> 2, y >> 2, (minZ + z) >> 2);
                    }
                });
            }
        }
    }

    /**
     * The cube holding a cube column's top blocks: the highest that holds anything, with the cube over it generated empty (higher up, sky
     * cubes generated where someone flew leave never-generated gaps: they do not matter); MIN_VALUE when not known. The search starts at the
     * cube of the generator's ground height, so a column takes a few cube reads rather than one for every cube up to the sky.
     */
    private static int surfaceCube(ServerLevel level, java.util.Map<Integer, CubicApi.CubeBlocks> cubes, int cubeX, int cubeZ, int minCube, int maxCube) {
        int size = CubeTerrain.SIZE;
        var generator = CubicApi.cubeGenerator(level);
        var ground = generator == null ? null : generator.surface(cubeX * size + size / 2, cubeZ * size + size / 2);
        if (ground == null) {
            boolean emptyAbove = false;
            for (int cy = maxCube; cy >= minCube; cy--) {
                var blocks = read(level, cubes, cubeX, cy, cubeZ);
                if (blocks == null) {
                    emptyAbove = false;
                } else if (blocks.isEmpty()) {
                    emptyAbove = true;
                } else {
                    return emptyAbove ? cy : Integer.MIN_VALUE;
                }
            }
            return Integer.MIN_VALUE;
        }
        int start = Math.max(minCube, Math.min(maxCube, Math.floorDiv(ground.surfaceY(), size)));
        var first = read(level, cubes, cubeX, start, cubeZ);
        if (first == null) return Integer.MIN_VALUE;
        if (first.isEmpty()) { // down to the ground
            for (int cy = start - 1; cy >= minCube; cy--) {
                var blocks = read(level, cubes, cubeX, cy, cubeZ);
                if (blocks == null) return Integer.MIN_VALUE;
                if (!blocks.isEmpty()) return cy;
            }
            return Integer.MIN_VALUE;
        }
        for (int cy = start + 1; cy <= maxCube; cy++) { // up through what stands on the ground
            var blocks = read(level, cubes, cubeX, cy, cubeZ);
            if (blocks == null) return Integer.MIN_VALUE;
            if (blocks.isEmpty()) return cy - 1;
        }
        return Integer.MIN_VALUE;
    }

    private static CubicApi.CubeBlocks read(ServerLevel level, java.util.Map<Integer, CubicApi.CubeBlocks> cubes, int cubeX, int cy, int cubeZ) {
        CubicApi.CubeBlocks cube = cubes.get(cy);
        if (cube == null) {
            cube = CubicApi.readCube(level, cubeX, cy, cubeZ).orElse(null);
            if (cube != null) cubes.put(cy, cube);
        }
        return cube;
    }
}
