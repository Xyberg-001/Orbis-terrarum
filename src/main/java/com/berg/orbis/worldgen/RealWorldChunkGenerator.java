package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.landmark.LandmarkRegistry;
import com.berg.orbis.render.ColumnPainter;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.core.Holder;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Real-world chunk generator. Every chunk is built from:
 *  - elevation (bicubic 30 m global DEM, 10 m USGS in the USA),
 *  - OSM vector data rasterised per region (roads, buildings, water, land
 *    cover, street furniture), streamed in on demand,
 *  - a climate model choosing snow, ice and biome tints,
 *  - optional hand-authored schematics for famous landmarks.
 *
 * Vanilla's surface features are disabled (there are no villages in Bergen and
 * no ravines through a real city); its underground (caves, dungeons, geodes,
 * mineshafts, strongholds) is kept, moved into the {@link UndergroundBand}
 * 100 blocks under the lowest nearby ground.
 */
public class RealWorldChunkGenerator extends ChunkGenerator {

    public static final MapCodec<RealWorldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    WorldSettings.CODEC.optionalFieldOf("settings").forGetter(g -> g.settings)
            ).apply(instance, RealWorldChunkGenerator::new)
    );

    /** The world's own settings (origin, scale, features); empty = the installation defaults at the time it is generated. */
    private final Optional<WorldSettings> settings;
    private volatile WorldModel cachedModel;

    public RealWorldChunkGenerator(BiomeSource biomeSource) {
        this(biomeSource, Optional.empty());
    }

    public RealWorldChunkGenerator(BiomeSource biomeSource, Optional<WorldSettings> settings) {
        super(biomeSource);
        this.settings = settings;
    }

    public Optional<WorldSettings> settings() {
        return settings;
    }

    /** The world model for this generator's settings (rebuilt by the mod if another world's model is active). */
    public WorldModel model() {
        WorldModel m = cachedModel;
        if (m != null && m == OrbisMod.model()) return m;
        m = OrbisMod.modelFor(settings.orElse(null));
        cachedModel = m;
        return m;
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("orbisterrarum", "real_world"), CODEC);
        // Worlds created while the mod was still called TellusPlus keep loading.
        // (A registry refuses the same object under two ids, so the alias is a distinct wrapper; such worlds
        // re-save under the new id because codec() always answers with CODEC.)
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("tellusplus", "real_world"),
                CODEC.xmap(g -> g, g -> g));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ---- disabled vanilla stages -------------------------------------------------

    /**
     * Vanilla-style cave tunnels, but inside the underground band: never above
     * vanilla Y 64 of the band, never within ten blocks of a column's real
     * surface, so no tunnel opens in a street or under a building.
     */
    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk) {
        WorldModel model = model();
        if (!model.cfg().vanillaCaves) return;
        ChunkPos pos = chunk.getPos();
        int chunkX = pos.getMinBlockX() >> 4, chunkZ = pos.getMinBlockZ() >> 4;
        int top = model.band().top(chunkX, chunkZ);
        int[] ceiling = new int[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                ceiling[lx * 16 + lz] = Math.min(top, model.terrainHeight(pos.getMinBlockX() + lx, pos.getMinBlockZ() + lz) - 10);
            }
        }
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        CaveCarver.Grid grid = new CaveCarver.Grid() {
            @Override
            public BlockState get(int x, int y, int z) {
                return chunk.getBlockState(mpos.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                chunk.setBlockState(mpos.set(x, y, z), state, 0);
            }
        };
        try {
            CaveCarver.carve(seed, model.cfg().minY + 5, chunkX, chunkZ, (cx, cz) -> model.band().top(cx, cz), ceiling, grid);
        } catch (RuntimeException e) {
            System.err.println("[orbis] Cave carving failed for chunk " + pos + ": " + e);
        }
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        // The painter writes final surface blocks directly in fillFromNoise.
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // Animals at generation, exactly as vanilla does it (they only appear where the
        // biome's spawn rules allow: grass, sky light), plus natural spawning during play.
        if (!model().cfg().spawnAnimals) return;
        ChunkPos center = level.getCenter();
        Holder<Biome> biome = level.getBiome(center.getWorldPosition().atY(level.getMaxY()));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(level, biome, center, random);
    }

    /**
     * Vanilla structures, adapted to a real world:
     * <ul>
     *   <li>Underground ones (mineshafts, strongholds, ancient cities, trial
     *   chambers, buried treasure) are generated exactly as vanilla lays them
     *   out, then moved so that vanilla's "sea level" sits 100 blocks below the
     *   lowest ground within ~100 m of the start. They keep their vanilla depth
     *   relationships, stay reachable from any surface, and never float in the
     *   sky the way their absolute vanilla Y ranges would put them here.</li>
     *   <li>Surface ones (villages, temples, outposts, ruined portals,
     *   shipwrecks, monuments, ...) only where the map has nothing: a start whose
     *   footprint touches a real building, road or water body is dropped.</li>
     * </ul>
     */
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templateManager,
                                 ResourceKey<Level> dimension) {
        WorldModel model = model();
        if (!model.cfg().vanillaStructures) return;
        super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager, dimension);
        java.util.Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) return;
        java.util.Map<Structure, StructureStart> kept = new java.util.HashMap<>();
        ChunkPos chunkPos = chunk.getPos();
        int bandTop = model.band().top(chunkPos.getMinBlockX() >> 4, chunkPos.getMinBlockZ() >> 4);
        Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        for (java.util.Map.Entry<Structure, StructureStart> e : starts.entrySet()) {
            StructureStart start = e.getValue();
            if (start == null || !start.isValid()) {
                kept.put(e.getKey(), start);
                continue;
            }
            // Underground by step or by terrain adaptation (the stronghold is a "surface_structures" that vanilla
            // buries below the generator's sea level, i.e. below Y -1700 here), or anything vanilla already put
            // under the band top: all of it is moved into the band.
            boolean underground = isUnderground(e.getKey()) || start.getBoundingBox().maxY() < bandTop;
            if (underground) {
                BoundingBox before = start.getBoundingBox();
                sinkBelowGround(model, start, bandTop);
                if (!registry.getKey(e.getKey()).getPath().startsWith("mineshaft")) {
                    BoundingBox after = start.getBoundingBox();
                    System.out.println("[orbis] " + registry.getKey(e.getKey()) + " at chunk " + chunkPos + ": vanilla Y "
                            + before.minY() + ".." + before.maxY() + " -> Y " + after.minY() + ".." + after.maxY()
                            + " (band top " + bandTop + ")");
                }
                kept.put(e.getKey(), start);
            } else if (isWaterStructure(registry.getKey(e.getKey()).getPath())
                    ? footprintIsOpenWater(model, start.getBoundingBox())
                    : footprintIsFree(model, start.getBoundingBox())) {
                kept.put(e.getKey(), start);
            } else {
                kept.put(e.getKey(), StructureStart.INVALID_START);
            }
        }
        chunk.setAllStarts(kept);
    }

    private static boolean isUnderground(Structure structure) {
        GenerationStep.Decoration step = structure.step();
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES || step == GenerationStep.Decoration.STRONGHOLDS
                || step == GenerationStep.Decoration.UNDERGROUND_DECORATION
                || structure.terrainAdaptation() == TerrainAdjustment.BURY;
    }

    /** Lowest ground within about 100 m of the start, minus 100: vanilla Y 64 is mapped there (the same band as caves and dungeons). */
    private static void sinkBelowGround(WorldModel model, StructureStart start, int bandTop) {
        BoundingBox box = start.getBoundingBox();
        int dy;
        if (box.minY() >= -80 && box.maxY() <= 120) {
            dy = bandTop - 64;                       // vanilla frame: keep vanilla depths
        } else {
            dy = bandTop - 8 - box.maxY();           // unknown frame: tuck the whole thing under the band top
        }
        int floor = model.cfg().minY + 6;
        if (box.minY() + dy < floor) dy = floor - box.minY();
        if (dy == 0) return;
        for (StructurePiece piece : start.getPieces()) piece.move(0, dy, 0);
        ((com.berg.orbis.mixin.StructureStartAccessor) (Object) start).orbis$setCachedBoundingBox(null);
    }

    /** Shipwrecks, ocean ruins and monuments belong in the sea: they need open water instead of empty land. */
    private static boolean isWaterStructure(String path) {
        return path.startsWith("shipwreck") || path.startsWith("ocean_ruin") || path.equals("monument");
    }

    /** True when the footprint is real sea or lake water with no building, road or bridge in it. */
    private static boolean footprintIsOpenWater(WorldModel model, BoundingBox box) {
        if (model.regions() == null) return false;
        int water = 0, cells = 0;
        for (int x = box.minX(); x <= box.maxX(); x += 2) {
            for (int z = box.minZ(); z <= box.maxZ(); z += 2) {
                RegionRaster r = model.cfg().waitForOsm
                        ? model.regions().futureForBlock(x, z).join()
                        : model.rasterIfLoaded(x, z);
                if (r == null) return false;
                int idx = r.index(x, z);
                if (idx < 0) continue;
                cells++;
                if (r.buildingAt(idx) != null || r.roadAt(idx) != null) return false;
                if (r.waterAt(idx) != null || (r.hasCoastline && r.isSea(idx))
                        || (model.cfg().seaFromElevation && model.terrainHeight(x, z) < model.cfg().seaLevelY - 1)) water++;
            }
        }
        return cells > 0 && water >= cells * 0.9;
    }

    /** True when no building, road, rail or water of the real map lies inside the footprint. */
    private static boolean footprintIsFree(WorldModel model, BoundingBox box) {
        if (model.regions() == null) return true;
        for (int x = box.minX(); x <= box.maxX(); x += 2) {
            for (int z = box.minZ(); z <= box.maxZ(); z += 2) {
                RegionRaster r = model.cfg().waitForOsm
                        ? model.regions().futureForBlock(x, z).join()
                        : model.rasterIfLoaded(x, z);
                if (r == null) continue;
                int idx = r.index(x, z);
                if (idx < 0) continue;
                if (r.buildingAt(idx) != null || r.roadAt(idx) != null || r.waterAt(idx) != null || (r.hasCoastline && r.isSea(idx))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
        if (model().cfg().vanillaStructures) super.createReferences(level, structureManager, chunk);
    }

    /**
     * The structure-placing half of vanilla's biome decoration, without the
     * feature half (vanilla trees, absolute-Y ores and lakes would be wrong
     * here; the mod does its own).
     */
    private void placeStructures(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        if (!structureManager.shouldGenerateStructures()) return;
        ChunkPos chunkPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        java.util.Map<Integer, List<Structure>> byStep = registry.stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s.step().ordinal()));
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        BoundingBox writable = new BoundingBox(chunkPos.getMinBlockX(), level.getMinY(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), level.getMaxY(), chunkPos.getMaxBlockZ());
        int steps = GenerationStep.Decoration.values().length;
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.getOrDefault(step, List.of())) {
                random.setFeatureSeed(seed, index++, step);
                try {
                    for (StructureStart start : structureManager.startsForStructure(sectionPos, structure)) {
                        start.placeInChunk(level, structureManager, this, random, writable, chunkPos);
                    }
                } catch (RuntimeException e) {
                    System.err.println("[orbis] Structure " + registry.getKey(structure) + " failed in chunk " + chunkPos + ": " + e);
                }
            }
        }
    }

    // ---- terrain -----------------------------------------------------------------

    /**
     * Future for the chunk's OSM region. Chaining onto it instead of joining
     * keeps Minecraft's world-generation threads free: while a new region is
     * downloading, chunks in regions that are already decoded keep generating.
     */
    private static CompletableFuture<RegionRaster> regionFuture(WorldModel model, ChunkAccess chunk) {
        if (model.regions() == null) return CompletableFuture.completedFuture(null);
        ChunkPos pos = chunk.getPos();
        if (!model.cfg().waitForOsm || model.farFromPlayers(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8)) {
            // Far from everyone (distant-horizon LOD generation): terrain only, right now.
            return CompletableFuture.completedFuture(model.rasterIfLoaded(pos.getMinBlockX(), pos.getMinBlockZ()));
        }
        return model.regions().futureForBlock(pos.getMinBlockX(), pos.getMinBlockZ());
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        WorldModel model = model();
        // Biomes depend on water / land cover, so wait (asynchronously) for the region first.
        return regionFuture(model, chunk).thenComposeAsync(
                raster -> super.createBiomes(randomState, blender, structureManager, chunk), Util.backgroundExecutor());
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        WorldModel model = model();
        return regionFuture(model, chunk)
                .thenCompose(raster -> terrainReady(model, chunk, System.currentTimeMillis()).thenApply(v -> raster))
                .thenApplyAsync(raster -> {
                    fillChunk(model, chunk, raster);
                    return chunk;
                }, Util.backgroundExecutor());
    }

    private static final java.util.concurrent.atomic.AtomicInteger TERRAIN_WAITING = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastTerrainWarning;

    /** Chunks currently waiting for terrain tiles that could not be downloaded. */
    public static int terrainWaiting() {
        return TERRAIN_WAITING.get();
    }

    /**
     * Completes once the chunk's terrain tiles can be read. A tile whose download failed (no internet, the tile
     * server down) would make the terrain fall back to sea level, and the chunk would be saved flat for good; so
     * the chunk waits and retries every 15 s instead. During a pre-generation it waits as long as it takes (the
     * sweep pauses and says so); in normal play it gives up after two minutes rather than keep chunks from
     * appearing; while the server stops it never waits.
     */
    private static CompletableFuture<Void> terrainReady(WorldModel model, ChunkAccess chunk, long since) {
        ChunkPos pos = chunk.getPos();
        if (model.terrainReady(pos.getMinBlockX(), pos.getMinBlockZ())) return CompletableFuture.completedFuture(null);
        long waited = System.currentTimeMillis() - since;
        if (OrbisMod.stopping() || (!com.berg.orbis.worldgen.PregenTask.isRunning() && waited > 120_000)) {
            System.err.println("[orbis] Terrain for chunk " + pos + " still unavailable after " + waited / 1000
                    + " s; generating it with sea-level ground there. Regenerate that chunk once the internet is back.");
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        if (now - lastTerrainWarning > 60_000) {
            lastTerrainWarning = now;
            System.err.println("[orbis] Terrain tiles cannot be downloaded (internet down?): " + (TERRAIN_WAITING.get() + 1)
                    + " chunk(s) waiting instead of being generated flat; retrying every 15 s");
        }
        TERRAIN_WAITING.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(15, java.util.concurrent.TimeUnit.SECONDS, Util.backgroundExecutor()))
                .thenCompose(v -> {
                    TERRAIN_WAITING.decrementAndGet();
                    return terrainReady(model, chunk, since);
                });
    }

    private static void fillChunk(WorldModel model, ChunkAccess chunk, RegionRaster raster) {
        OrbisConfig cfg = model.cfg();
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();

        // Elevation: sample, then smooth obvious outliers against the local median.
        double[][] raw = new double[16][16];
        boolean[][] ok = new boolean[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double e = model.elevation(minX + lx, minZ + lz);
                raw[lx][lz] = e;
                ok[lx][lz] = !Double.isNaN(e);
            }
        }
        int[][] terrain = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double e = smoothed(raw, ok, lx, lz, cfg.elevationOutlierMeters, 0.0);
                terrain[lx][lz] = model.blockY(e, minX + lx, minZ + lz);
            }
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        List<BlockPos> postProcess = new ArrayList<>();
        int chunkMinY = chunk.getMinY();
        int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

        // Sections the terrain reaches start with a palette of the common blocks (no resizes while painting), and
        // blocks go straight into the palettes; each touched section is counted once after painting.
        int maxTerrain = chunkMinY;
        for (int[] col : terrain) for (int t : col) maxTerrain = Math.max(maxTerrain, t);
        seedSections(chunk, Math.min(chunkMaxY, maxTerrain + 1));
        boolean[] touched = new boolean[chunk.getSections().length];

        ColumnPainter.Sink sink = new ColumnPainter.Sink() {
            @Override
            public void set(int x, int y, int z, BlockState state) {
                if (y < chunkMinY || y > chunkMaxY) return;
                // What ProtoChunk.setBlockState does before the light stage, minus the per-block bookkeeping of the
                // section's counts (the fluid checks alone were a tenth of all generation time): the section write
                // and the two world-generation heightmaps, which are updated below.
                int si = chunk.getSectionIndex(y);
                chunk.getSection(si).getStates().getAndSetUnchecked(x & 15, y & 15, z & 15, state);
                touched[si] = true;
                mpos.set(x, y, z);
                int lx = x & 15, lz = z & 15;
                oceanFloor.update(lx, y, lz, state);
                worldSurface.update(lx, y, lz, state);
                if (needsPostProcessing(state)) postProcess.add(mpos.immutable());
            }

            @Override
            public void fill(int x, int y0, int y1, int z, BlockState state) {
                y0 = Math.max(y0, chunkMinY);
                y1 = Math.min(y1, chunkMaxY);
                if (y0 > y1) return;
                if (needsPostProcessing(state) || state.getLightEmission() > 0) {
                    for (int y = y0; y <= y1; y++) set(x, y, z, state);
                    return;
                }
                // Straight into the sections: no per-block bounds, light or post-processing checks (none apply
                // to rock, subsoil and water), and the heightmaps see only the top of the run, which is all
                // they record. The chunk is still a proto chunk on a worker thread, so no locks either.
                int lx = x & 15, lz = z & 15;
                int y = y0;
                while (y <= y1) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = chunk.getSection(sectionIndex).getStates();
                    int end = Math.min(y1, (chunk.getSectionYFromSectionIndex(sectionIndex) << 4) + 15);
                    for (int yy = y; yy <= end; yy++) states.getAndSetUnchecked(lx, yy & 15, lz, state);
                    touched[sectionIndex] = true;
                    y = end + 1;
                }
                oceanFloor.update(lx, y1, lz, state);
                worldSurface.update(lx, y1, lz, state);
                // The sky-light sources (lowest sky-lit Y per column) are what the light stage starts from;
                // like the heightmaps they only need the top of an opaque run.
                chunk.getSkyLightSources().update(chunk, lx, y1, lz);
            }
        };

        ColumnPainter painter = model.painter();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = raster == null ? -1 : raster.index(x, z);
                BiomeClassifier.Climate climate = model.climate(x, z, ok[lx][lz] ? raw[lx][lz] : Double.NaN);
                try {
                    painter.paint(sink, x, z, terrain[lx][lz], climate, raster, idx);
                } catch (RuntimeException e) {
                    // One bad column must not lose the chunk; fall back to bare terrain.
                    System.err.println("[orbis] Column " + x + "," + z + " failed: " + e);
                    for (int y = chunkMinY; y <= terrain[lx][lz]; y++) sink.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }

        if (cfg.generateSchematics && model.landmarks() != null) {
            pasteLandmarks(model, chunk, minX, minZ, sink);
        }
        // The counts (blocks, fluids, ticking) that the direct writes skipped; everything after this uses the
        // normal setters, which rely on them.
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < touched.length; i++) if (touched[i]) sections[i].recalcBlockCounts();
        if (cfg.generateOres) {
            long seed = Double.doubleToLongBits(cfg.originLat) * 31 + Double.doubleToLongBits(cfg.originLon);
            OreGenerator.place(chunk, terrain, cfg.seaLevelY - 60, cfg.minY, seed);
        }
        for (BlockPos p : postProcess) chunk.markPosForPostProcessing(p);
    }

    private static final net.minecraft.world.level.chunk.Strategy<BlockState> BLOCK_STRATEGY =
            net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
    /**
     * Air first (an all-zero storage is all air), then the blocks nearly every section under the surface gets.
     * Eight of the 4-bit palette's sixteen places: a full palette made the first ore or cave-air block of every
     * section resize it to the next size, which cost more than the resize the seeding saves.
     */
    private static final List<BlockState> SEED_PALETTE = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(), Blocks.BEDROCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.GRAVEL.defaultBlockState());
    private static final int SEED_LONGS = 4096 * 4 / 64;
    private static volatile boolean seedingFailed;

    /**
     * Gives every still-empty section from the bottom up to {@code topY} a 4-bit palette that already holds the
     * common blocks. A fresh section holds a single value (air); the first other block made it copy all 4096
     * entries into a bigger palette, and that resize was 8% of all generation time. Sections above the terrain
     * stay single-valued, so the sky costs no memory. Saving packs palettes down to what is used.
     */
    private static void seedSections(ChunkAccess chunk, int topY) {
        if (seedingFailed) return;
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int top = Math.min(sections.length - 1, chunk.getSectionIndex(topY));
        for (int i = 0; i <= top; i++) {
            net.minecraft.world.level.chunk.LevelChunkSection s = sections[i];
            if (!s.hasOnlyAir() || s.getStates().bitsPerEntry() != 0) continue;
            var packed = new net.minecraft.world.level.chunk.PalettedContainerRO.PackedData<>(SEED_PALETTE,
                    java.util.Optional.of(java.util.stream.LongStream.of(new long[SEED_LONGS])));
            var result = net.minecraft.world.level.chunk.PalettedContainer.unpack(BLOCK_STRATEGY, packed);
            if (result.result().isEmpty()) {
                seedingFailed = true;
                System.err.println("[orbis] Could not prepare section palettes (" + result.error().map(Object::toString).orElse("?") + "); painting without");
                return;
            }
            sections[i] = new net.minecraft.world.level.chunk.LevelChunkSection(result.result().get(), s.getBiomes());
        }
    }

    private static boolean needsPostProcessing(BlockState state) {
        Block b = state.getBlock();
        return b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof BaseRailBlock
                || b instanceof StairBlock || b instanceof DoorBlock;
    }

    private static void pasteLandmarks(WorldModel model, ChunkAccess chunk, int minX, int minZ, ColumnPainter.Sink sink) {
        for (LandmarkRegistry.Placement pl : model.landmarks().placements()) {
            if (!pl.intersectsChunk(minX, minZ)) continue;
            int base = pl.baseY(model::terrainHeight);
            int x0 = Math.max(minX, pl.minX), x1 = Math.min(minX + 15, pl.maxX());
            int z0 = Math.max(minZ, pl.minZ), z1 = Math.min(minZ + 15, pl.maxZ());
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int ly = 0; ly < pl.schematic.height; ly++) {
                        int y = base + ly;
                        BlockState s = pl.stateAt(x, y, z, base);
                        if (s != null) sink.set(x, y, z, s);
                    }
                }
            }
        }
    }

    /** Replaces a reading that differs wildly from its neighbours' median (bad DEM pixel) with that median. */
    private static double smoothed(double[][] raw, boolean[][] ok, int lx, int lz, double threshold, double fallback) {
        double[] neighbours = new double[8];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = lx + dx, nz = lz + dz;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                if (ok[nx][nz]) neighbours[n++] = raw[nx][nz];
            }
        }
        if (!ok[lx][lz]) return n == 0 ? fallback : median(neighbours, n);
        if (n < 3) return raw[lx][lz];
        double med = median(neighbours, n);
        return Math.abs(raw[lx][lz] - med) > threshold ? med : raw[lx][lz];
    }

    private static double median(double[] v, int n) {
        double[] s = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(s);
        return n % 2 == 0 ? (s[n / 2 - 1] + s[n / 2]) / 2.0 : s[n / 2];
    }

    // ---- decoration --------------------------------------------------------------

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        WorldModel model = model();
        if (model.cfg().vanillaStructures) {
            try {
                placeStructures(level, chunk, structureManager);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Structure placement failed for chunk " + chunk.getPos() + ": " + e);
            }
        }
        if (model.cfg().vanillaCaves) {
            try {
                // Dungeons, geodes, fossils, springs, lichen: vanilla's own features, in the underground band.
                UndergroundFeatures.place(model, level, this, chunk.getPos());
            } catch (RuntimeException e) {
                System.err.println("[orbis] Underground features failed for chunk " + chunk.getPos() + ": " + e);
            }
        }
        try {
            // The region was awaited in fillFromNoise, so this lookup is instant.
            model.decorator().decorateChunk(level, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        } catch (RuntimeException e) {
            System.err.println("[orbis] Decoration failed for chunk " + chunk.getPos() + ": " + e);
            e.printStackTrace();
        }
    }

    // ---- queries -----------------------------------------------------------------

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        WorldModel model = model();
        int terrain = model.terrainHeight(x, z);
        if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
            terrain = Math.max(terrain, getSeaLevel());
        }
        return Math.max(level.getMinY() + 1, Math.min(level.getMaxY(), terrain + 1));
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        WorldModel model = model();
        int terrain = model.terrainHeight(x, z);
        BlockState[] states = new BlockState[level.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = level.getMinY() + i;
            states[i] = y <= terrain ? Blocks.STONE.defaultBlockState()
                    : y <= getSeaLevel() ? Blocks.WATER.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(level.getMinY(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        WorldModel model = model();
        double[] ll = model.mapper().toLatLon(pos.getX(), pos.getZ());
        double elev = model.elevation(pos.getX(), pos.getZ());
        double shift = model.vertical().shiftBlocks(pos.getX(), pos.getZ());
        info.add(String.format(java.util.Locale.ROOT, "Orbis Terrarum: %.5f, %.5f  elev %.1f m%s", ll[0], ll[1], elev,
                shift > 0 ? String.format(java.util.Locale.ROOT, "  (relief shift -%.0f)", shift) : ""));
    }

    @Override
    public int getGenDepth() {
        return model().cfg().worldHeight;
    }

    @Override
    public int getSeaLevel() {
        return model().cfg().seaLevelY;
    }

    @Override
    public int getMinY() {
        return model().cfg().minY;
    }
}
