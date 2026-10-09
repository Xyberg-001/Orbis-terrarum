package com.berg.orbis.cubic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.render.ColumnPainter;
import com.berg.orbis.worldgen.CaveCarver;
import com.berg.orbis.worldgen.HardLimit;
import com.berg.orbis.worldgen.OreGenerator;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import io.github.opencubicchunks.cubicchunks.api.CubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.CubeTerrain;
import io.github.opencubicchunks.cubicchunks.api.CubicApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Orbis Terrarum in a cubic world (the Cubic Chunks mod): the cubes of a level made by Orbis's generator are painted by the same column
 * painter as chunks, through a sink that keeps what falls in the cube. The world holds the real heights (a block a metre up and down from
 * sea level at Y 0, see {@link OrbisMod#useCubicHeights}), so nothing is squeezed.
 * <p>
 * A cube spans 2 x 2 chunks; each chunk's columns are worked out as for a chunk (the same elevation smoothing over its 16 x 16 columns), so
 * a column is the same in every cube it passes through, and the cube waits for those chunks' map regions as a chunk would. The decoration
 * (trees, interiors, signs, street life) is made per chunk over the painted columns and shared out to the cubes ({@link CubeDecorations}).
 * Ores and caves are made as in a chunk, each cube keeping its part; the underground features come with the decoration. Vanilla structures
 * are not made in cubes yet.
 */
public final class OrbisCubeGenerator implements CubeGenerator {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    /** Deepest an ore vein reaches under the ground (OreGenerator's deepest vein, and its spread). */
    private static final int ORE_DEPTH = 330;

    private final ServerLevel level;
    private final RealWorldChunkGenerator generator;
    private final CubeDecorations decorations;

    private OrbisCubeGenerator(ServerLevel level, RealWorldChunkGenerator generator) {
        this.level = level;
        this.generator = generator;
        this.decorations = new CubeDecorations(level, generator);
    }

    /** Called at startup when Cubic Chunks is installed. */
    public static void register() {
        CubicApi.registerCubeGenerator(RealWorldChunkGenerator.class, (level, chunkGenerator) -> {
            if (!CubicApi.isCubic(level)) return null;
            RealWorldChunkGenerator generator = (RealWorldChunkGenerator) chunkGenerator;
            OrbisMod.useCubicHeights(generator.settings().orElse(null), CubicApi.minY(level), CubicApi.maxY(level));
            return new OrbisCubeGenerator(level, generator);
        });
    }

    @Override
    public CompletableFuture<?> generate(CubeTerrain cube) {
        WorldModel model = this.generator.model();
        int size = CubeTerrain.SIZE;
        int chunks = Math.max(1, size / 16);
        @SuppressWarnings("unchecked")
        CompletableFuture<RegionRaster>[] rasters = new CompletableFuture[chunks * chunks];
        for (int i = 0; i < chunks; i++) {
            for (int j = 0; j < chunks; j++) {
                rasters[i * chunks + j] = regionFuture(model, cube.minX() + i * 16, cube.minZ() + j * 16);
            }
        }
        return CompletableFuture.allOf(rasters)
                .thenCompose(v -> terrainReady(model, cube.minX(), cube.minZ(), size, System.currentTimeMillis()))
                .thenRunAsync(() -> {
                    for (int i = 0; i < chunks; i++) {
                        for (int j = 0; j < chunks; j++) {
                            buildChunk(model, this.level.getSeed(), cube, cube.minX() + i * 16, cube.minZ() + j * 16, rasters[i * chunks + j].join());
                        }
                    }
                    this.fillBiomes(cube);
                }, Util.backgroundExecutor());
    }

    /**
     * The cube's share of the decoration of the chunks it spans and of the chunks around them, which can reach into it. Applied in the same
     * order in every cube, so where two decorations write the same block the same one wins everywhere.
     */
    @Override
    public CompletableFuture<?> decorate(CubeTerrain cube) {
        int fromX = (cube.minX() >> 4) - 1;
        int fromZ = (cube.minZ() >> 4) - 1;
        int across = CubeTerrain.SIZE / 16 + 2;
        @SuppressWarnings("unchecked")
        CompletableFuture<ChunkDecoration>[] around = new CompletableFuture[across * across];
        for (int i = 0; i < across; i++) {
            for (int j = 0; j < across; j++) {
                around[i * across + j] = this.decorations.chunk(fromX + i, fromZ + j);
            }
        }
        return CompletableFuture.allOf(around).thenRunAsync(() -> {
            for (CompletableFuture<ChunkDecoration> decoration : around) decoration.join().applyTo(cube);
        }, Util.backgroundExecutor());
    }

    /** The cube's biomes from Orbis's biome source, with a climate sampler made as vanilla's biome step makes one. */
    private void fillBiomes(CubeTerrain cube) {
        net.minecraft.world.level.levelgen.RandomState random = this.level.getChunkSource().randomState();
        net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool pool = random.acquireDensityBufferPool();
        try {
            net.minecraft.world.level.biome.Climate.Sampler sampler = random.createClimateSampler(
                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().useBufferArena(pool).build());
            int quarter = net.minecraft.core.QuartPos.fromBlock(CubeTerrain.SIZE);
            cube.fillBiomes(this.generator.getBiomeSource().createResolverForChunk(sampler, net.minecraft.core.QuartPos.fromBlock(cube.minX()),
                    net.minecraft.core.QuartPos.fromBlock(cube.minY()), net.minecraft.core.QuartPos.fromBlock(cube.minZ()), quarter, quarter, quarter),
                    sampler);
        } finally {
            random.releaseDensityBufferPool(pool);
        }
    }

    /** The chunk's map region, as the chunk generator waits for it. */
    static CompletableFuture<RegionRaster> regionFuture(WorldModel model, int chunkMinX, int chunkMinZ) {
        if (model.regions() == null || HardLimit.blocks(chunkMinX >> 4, chunkMinZ >> 4)) return CompletableFuture.completedFuture(null);
        if (!model.cfg().waitForOsm) return CompletableFuture.completedFuture(model.rasterIfLoaded(chunkMinX, chunkMinZ));
        return model.regions().futureForBlock(chunkMinX, chunkMinZ);
    }

    /**
     * Completes once the terrain tiles of the cube's columns can be read, retrying every 15 s while they cannot (as the chunk generator does:
     * a failed download would otherwise save the ground flat at sea level), for two minutes outside a pre-generation.
     */
    static CompletableFuture<Void> terrainReady(WorldModel model, int minX, int minZ, int size, long since) {
        boolean ready = true;
        for (int x = minX; x < minX + size && ready; x += 16) {
            for (int z = minZ; z < minZ + size && ready; z += 16) {
                ready = HardLimit.blocks(x >> 4, z >> 4) || model.terrainReady(x, z);
            }
        }
        long waited = System.currentTimeMillis() - since;
        if (ready || OrbisMod.stopping() || (!com.berg.orbis.worldgen.PregenTask.isRunning() && waited > 120_000)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS, Util.backgroundExecutor()))
                .thenCompose(v -> terrainReady(model, minX, minZ, size, since));
    }

    /** One chunk's 16 x 16 columns of the cube, as the chunk generator makes a chunk's (painted, then ores and caves). */
    private static void buildChunk(WorldModel model, long seed, CubeTerrain cube, int minX, int minZ, RegionRaster raster) {
        buildColumns(model, seed, minX, minZ, raster, new ColumnPainter.Sink() {
            @Override
            public void set(int x, int y, int z, BlockState state) {
                cube.setBlock(x, y, z, state);
                if (RealWorldChunkGenerator.needsPostProcessing(state)) cube.markForPostProcessing(x, y, z);
            }

            @Override
            public void fill(int x, int y0, int y1, int z, BlockState state) {
                int from = Math.max(y0, cube.minY());
                int to = Math.min(y1, cube.maxY());
                if (from > to) return;
                if (RealWorldChunkGenerator.needsPostProcessing(state)) {
                    for (int y = from; y <= to; y++) set(x, y, z, state);
                } else {
                    cube.fill(x, from, to, z, state);
                }
            }
        }, new CaveCarver.Grid() {
            @Override
            public BlockState get(int x, int y, int z) {
                return cube.getBlock(x, y, z); // air outside the cube, which ores and caves leave alone
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                cube.setBlock(x, y, z, state);
            }
        }, cube.minY(), cube.maxY());
    }

    /**
     * A chunk's columns from minX, minZ: painted whole into the sink, then the ores and caves the chunk generator adds, through the grid
     * (which reads what the sink wrote) between clipMinY and clipMaxY. Ores and caves read only blocks they write, so any part of the
     * columns comes out as in the whole chunk.
     */
    static void buildColumns(WorldModel model, long seed, int minX, int minZ, RegionRaster raster, ColumnPainter.Sink sink,
                             CaveCarver.Grid grid, int clipMinY, int clipMaxY) {
        int[][] terrain = paintColumns(model, minX, minZ, raster, sink);
        if (terrain == null) return;
        OrbisConfig cfg = model.cfg();
        if (cfg.generateOres) {
            int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
            for (int[] column : terrain) {
                for (int t : column) {
                    lowest = Math.min(lowest, t);
                    highest = Math.max(highest, t);
                }
            }
            // veins lie 5 to 320 blocks under the ground (and spread 3 blocks)
            if (clipMaxY >= lowest - ORE_DEPTH && clipMinY <= highest) {
                long oreSeed = Double.doubleToLongBits(cfg.originLat) * 31 + Double.doubleToLongBits(cfg.originLon);
                int deepslateTop = cfg.undergroundVersion >= 2 ? Integer.MIN_VALUE : cfg.seaLevelY - 60;
                OreGenerator.place(grid, minX, minZ, terrain, deepslateTop, cfg.minY, oreSeed);
            }
        }
        if (cfg.vanillaCaves) {
            int chunkX = minX >> 4, chunkZ = minZ >> 4;
            int top = model.band().top(chunkX, chunkZ);
            int[] ceiling = new int[256];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    ceiling[lx * 16 + lz] = Math.min(top, model.terrainHeight(minX + lx, minZ + lz) - 10);
                }
            }
            try {
                CaveCarver.carve(seed, cfg.minY + 5, chunkX, chunkZ, (cx, cz) -> model.band().top(cx, cz), ceiling, grid, clipMinY, clipMaxY);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Cave carving failed for chunk " + chunkX + "," + chunkZ + " (cubic): " + e);
            }
        }
    }

    /**
     * Paints a chunk's 16 x 16 columns whole into the sink (the elevation smoothed over the chunk, the painter, the landmarks): the same
     * for the cubes ({@link #paintChunk}, which keep what falls in them) and for the decoration ({@link PaintedChunk}).
     */
    static int[][] paintColumns(WorldModel model, int minX, int minZ, RegionRaster raster, ColumnPainter.Sink sink) {
        if (HardLimit.blocks(minX >> 4, minZ >> 4)) return null; // outside the allowed area: left empty
        OrbisConfig cfg = model.cfg();
        double[][] raw = new double[16][16];
        boolean[][] ok = new boolean[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double e = model.elevation(minX + lx, minZ + lz);
                raw[lx][lz] = e;
                ok[lx][lz] = !Double.isNaN(e);
            }
        }
        ColumnPainter painter = model.painter();
        boolean layout2 = cfg.undergroundVersion >= 2;
        int deepslateBase = layout2 ? model.band().deepslateTop(minX >> 4, minZ >> 4) : cfg.seaLevelY - 60;
        int[][] deepslateLine = layout2 ? model.band().deepslateLine(minX >> 4, minZ >> 4) : null;
        int[][] terrains = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int terrain = model.blockY(RealWorldChunkGenerator.smoothed(raw, ok, lx, lz, cfg.elevationOutlierMeters, 0.0), x, z);
                terrains[lx][lz] = terrain;
                int idx = raster == null ? -1 : raster.index(x, z);
                BiomeClassifier.Climate climate = model.climateWithSnow(x, z, ok[lx][lz] ? raw[lx][lz] : Double.NaN);
                try {
                    painter.paint(sink, x, z, terrain, climate, raster, idx, layout2 ? deepslateLine[lx][lz] : deepslateBase);
                } catch (RuntimeException e) {
                    // One bad column must not lose the chunk; fall back to bare terrain.
                    System.err.println("[orbis] Column " + x + "," + z + " failed (cubic): " + e);
                    sink.fill(x, cfg.minY, terrain, z, STONE);
                }
            }
        }
        if (cfg.generateSchematics && model.landmarks() != null) RealWorldChunkGenerator.pasteLandmarks(model, minX, minZ, sink);
        return terrains;
    }

    /**
     * A column from afar, for Distant Horizons: the painter run into a sink that keeps the highest block and the highest fluid, with the map
     * region only if it is already loaded (distant terrain must not start map downloads), so roads and buildings show where it is.
     */
    @Override
    public Surface surface(int x, int z) {
        WorldModel model = this.generator.model();
        if (HardLimit.blocks(x >> 4, z >> 4)) return null;
        int terrain = model.terrainHeight(x, z);
        RegionRaster raster = model.rasterIfLoaded(x, z);
        int idx = raster == null ? -1 : raster.index(x, z);
        SurfaceProbe probe = new SurfaceProbe();
        try {
            model.painter().paint(probe, x, z, terrain, model.climate(x, z, model.elevation(x, z)), raster, idx, model.cfg().seaLevelY - 60);
        } catch (RuntimeException e) {
            return new Surface(terrain, STONE, STONE, Integer.MIN_VALUE, null);
        }
        if (probe.topBlock == null) return new Surface(terrain, STONE, STONE, Integer.MIN_VALUE, null);
        return new Surface(probe.topY, probe.topBlock, probe.below == null ? STONE : probe.below, probe.fluidTopY, probe.fluid);
    }

    /** Keeps the highest solid block (and the one under it) and the highest fluid of a painted column. */
    private static final class SurfaceProbe implements ColumnPainter.Sink {
        int topY = Integer.MIN_VALUE;
        BlockState topBlock;
        BlockState below;
        int fluidTopY = Integer.MIN_VALUE;
        BlockState fluid;

        @Override
        public void set(int x, int y, int z, BlockState state) {
            if (state.isAir()) return;
            if (!state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                if (y > this.fluidTopY) {
                    this.fluidTopY = y;
                    this.fluid = state;
                }
                return;
            }
            if (y > this.topY) {
                this.below = y == this.topY + 1 ? this.topBlock : this.below;
                this.topY = y;
                this.topBlock = state;
            } else if (y == this.topY - 1) {
                this.below = state;
            }
        }

        @Override
        public void fill(int x, int y0, int y1, int z, BlockState state) {
            if (y0 > y1) return;
            if (y1 - 1 >= y0) set(x, y1 - 1, z, state);
            set(x, y1, z, state);
        }
    }
}
