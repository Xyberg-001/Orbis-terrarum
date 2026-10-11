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
import com.berg.orbis.mc.ChunkGeneratorBridge;
import com.berg.orbis.mc.Mc;
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
public class RealWorldChunkGenerator extends ChunkGeneratorBridge {

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
    protected void carve(long seed, ChunkAccess chunk) {
        WorldModel model = model();
        if (!model.cfg().vanillaCaves || HardLimit.blocks(chunk.getPos().x(), chunk.getPos().z())) return;
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
    public void spawnOriginalMobs(WorldGenRegion level) {
        // Animals at generation, exactly as vanilla does it (they only appear where the
        // biome's spawn rules allow: grass, sky light), plus natural spawning during play.
        if (!model().cfg().spawnAnimals) return;
        ChunkPos center = level.getCenter();
        if (HardLimit.blocks(center.x(), center.z())) return;
        BlockPos biomePos = center.getWorldPosition().atY(level.getMaxY());
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
        Mc.spawnMobsForChunkGeneration(level, biomePos, center, random);
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
     *   <li>Surface ones (villages, temples, outposts, ruined portals, ...) only
     *   where the map has nothing: a start whose footprint touches a real
     *   building, road or water body is dropped. Trail ruins count as surface
     *   ones: vanilla buries them a few blocks under the ground for brushing,
     *   which is where they stay (they used to be moved down with the caves).</li>
     *   <li>Sea ones (shipwrecks, ocean ruins, monuments) only in open water deep
     *   enough to cover them. Vanilla builds a monument at a fixed Y 39..61 (for
     *   sea level 63); it is moved to the same place under this world's sea
     *   level.</li>
     * </ul>
     */
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templateManager,
                                 ResourceKey<Level> dimension) {
        WorldModel model = model();
        if (!model.cfg().vanillaStructures || HardLimit.blocks(chunk.getPos().x(), chunk.getPos().z())) return;
        ChunkPos chunkPos = chunk.getPos();
        // The band first: the cave biomes vanilla checks a start against (an Ancient City's Deep Dark) are found by
        // it, in the band's vanilla frame (RealWorldBiomeSource.VANILLA_FRAME).
        int bandTop = model.band().top(chunkPos.getMinBlockX() >> 4, chunkPos.getMinBlockZ() >> 4);
        com.berg.orbis.biome.RealWorldBiomeSource.VANILLA_FRAME.set(Boolean.TRUE);
        try {
            super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager, dimension);
        } finally {
            com.berg.orbis.biome.RealWorldBiomeSource.VANILLA_FRAME.set(Boolean.FALSE);
        }
        java.util.Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) return;
        java.util.Map<Structure, StructureStart> kept = new java.util.HashMap<>();
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
            String path = registry.getKey(e.getKey()).getPath();
            // Buried treasure finds its own place when it is built (the first block over sandstone or stone under the
            // beach), wherever its box is: it stays out of the band and its log lines.
            boolean underground = !path.equals("trail_ruins") && !path.equals("buried_treasure")
                    && (isUnderground(e.getKey()) || start.getBoundingBox().maxY() < bandTop);
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
                    ? footprintIsOpenWater(model, start.getBoundingBox(), depthNeeded(registry.getKey(e.getKey()).getPath(), start.getBoundingBox()))
                    : footprintIsFree(model, start.getBoundingBox())) {
                if (registry.getKey(e.getKey()).getPath().equals("monument")) moveMonumentToSeaLevel(start, model().cfg().waterLevelY());
                if (path.equals("trail_ruins")) buryJustBelowGround(model, start);
                if (path.equals("buried_treasure")) System.out.println("[orbis] buried treasure at chunk " + chunkPos);
                kept.put(e.getKey(), start);
            } else {
                kept.put(e.getKey(), StructureStart.INVALID_START);
            }
        }
        chunk.setAllStarts(kept);
    }

    /**
     * Trail ruins under the ground, as shallow as they can be: the ruin goes up or down until its top is two blocks
     * under the lowest ground over its footprint. Vanilla starts them 15 blocks under the surface and relies on its
     * "bury" terrain adjustment, which Orbis's own terrain does not apply; left there, their tops lay 14 to 41
     * blocks down (Bergen 1:2, 2 Oct 2026), out of reach of anyone looking for them with a brush.
     */
    private static void buryJustBelowGround(WorldModel model, StructureStart start) {
        // The ruin's own blocks: the start's box is 12 blocks bigger on every side for the bury adjustment, which put
        // the first version of this 14 to 41 blocks down again (top 12 too high, ground taken over a wider area).
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            minX = Math.min(minX, b.minX()); minY = Math.min(minY, b.minY()); minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX()); maxY = Math.max(maxY, b.maxY()); maxZ = Math.max(maxZ, b.maxZ());
        }
        if (minX > maxX) return;
        int ground = Integer.MAX_VALUE;
        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) ground = Math.min(ground, model.terrainHeight(x, z));
        }
        if (ground == Integer.MAX_VALUE) return;
        int dy = (ground - 2) - maxY;
        int floor = model.cfg().minY + 6;
        if (minY + dy < floor) dy = floor - minY;
        if (dy == 0) return;
        for (StructurePiece piece : start.getPieces()) piece.move(0, dy, 0);
        ((com.berg.orbis.mixin.StructureStartAccessor) (Object) start).orbis$setCachedBoundingBox(null);
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
        return (path.startsWith("shipwreck") && !path.equals("shipwreck_beached")) || path.startsWith("ocean_ruin") || path.equals("monument");
    }

    /**
     * Blocks of water a sea structure needs over the bed so it is not left sticking out: a monument its vanilla
     * 24 (sea level 63, building 39..61), a wreck its 8 or so, a ruin its height up to 12 (big ruins sink into the
     * bed a little).
     */
    private static int depthNeeded(String path, BoundingBox box) {
        if (path.equals("monument")) return 24;
        if (path.startsWith("shipwreck")) return 8;
        return Math.max(5, Math.min(12, box.getYSpan()));
    }

    /** Vanilla's monument frame (fixed Y 39..61 under sea level 63) moved to this world's sea level. */
    private static void moveMonumentToSeaLevel(StructureStart start, int seaLevel) {
        int dy = seaLevel - 63;
        if (dy == 0 || start.getBoundingBox().maxY() < seaLevel) return;
        for (StructurePiece piece : start.getPieces()) piece.move(0, dy, 0);
        ((com.berg.orbis.mixin.StructureStartAccessor) (Object) start).orbis$setCachedBoundingBox(null);
    }

    /**
     * Water depth over the bed at a column, in blocks, as the painter will make it: the sea's from the elevation
     * (0 where the elevation has no depth near the shore, which is too shallow for any of these anyway), a lake's
     * or river's from its shaped bed or its own depth. 0 on land.
     */
    private static int waterDepth(WorldModel model, RegionRaster r, int idx, int x, int z) {
        com.berg.orbis.feature.WaterFeature wf = r.waterAt(idx);
        int sea = model.cfg().waterLevelY();
        if ((r.hasCoastline && r.isSea(idx)) || (wf != null && wf.atSeaLevel)
                || (wf == null && !r.hasCoastline && model.cfg().seaFromElevation && model.terrainHeight(x, z) < sea)) {
            return Math.max(0, sea - model.terrainHeight(x, z));
        }
        if (wf == null) return 0;
        int bed = r.bedDepthAt(idx);
        return bed > 0 ? bed : wf.depth;
    }

    /** True when the footprint is real sea or lake water, at least {@code minDepth} blocks deep, with no building, road or bridge in it. */
    private static boolean footprintIsOpenWater(WorldModel model, BoundingBox box, int minDepth) {
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
                if (waterDepth(model, r, idx, x, z) >= minDepth) water++;
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
        placeStructures(level, chunk.getPos(), structureManager);
    }

    /** The same for the chunk at chunkPos, through any level (a cubic world's decoration, see versions/26.3 cubic). */
    public void placeStructures(WorldGenLevel level, ChunkPos chunkPos, StructureManager structureManager) {
        placeStructures(level, chunkPos, structureManager, java.util.function.UnaryOperator.identity());
    }

    /**
     * As {@link #placeStructures(WorldGenLevel, ChunkPos, StructureManager)}, each start placed as {@code toPlace} gives it: a cubic world's
     * decoration places copies (a chunk's decoration can be worked out more than once there, and placing changes a start's pieces: a
     * mineshaft corridor remembers it placed its spawner, so a second time came out differently).
     */
    public void placeStructures(WorldGenLevel level, ChunkPos chunkPos, StructureManager structureManager,
                                java.util.function.UnaryOperator<StructureStart> toPlace) {
        if (!structureManager.shouldGenerateStructures()) return;
        SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        java.util.Map<Integer, List<Structure>> byStep = registry.stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s.step().ordinal()));
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        BoundingBox writable = new BoundingBox(chunkPos.getMinBlockX(), level.getMinY(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), level.getMaxY(), chunkPos.getMaxBlockZ());
        hollowForCities(level, structureManager, sectionPos, registry, writable);
        int steps = GenerationStep.Decoration.values().length;
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.getOrDefault(step, List.of())) {
                random.setFeatureSeed(seed, index++, step);
                try {
                    for (StructureStart start : Mc.startsForStructure(structureManager, sectionPos.x(), sectionPos.z(), structure)) {
                        toPlace.apply(start).placeInChunk(level, structureManager, this, random, writable, chunkPos);
                    }
                } catch (RuntimeException e) {
                    StringBuilder at = new StringBuilder();
                    StackTraceElement[] trace = e.getStackTrace();
                    for (int i = 0; i < Math.min(8, trace.length); i++) at.append(System.lineSeparator()).append("    at ").append(trace[i]);
                    System.err.println("[orbis] Structure " + registry.getKey(structure) + " failed in chunk " + chunkPos + ": " + e + at);
                }
            }
        }
    }

    /**
     * The hollow an Ancient City stands in. Vanilla digs it with its terrain noise around the pieces of structures
     * with the "beard_box" terrain adaptation (the Beardifier), and the city's templates hold no air of their own:
     * without it the first Ancient Cities (4 Oct 2026) were sealed in rock, only the bits that met a cave showing.
     * Here each piece's box, from its floor up, is emptied in this chunk before the structure is placed; never within
     * ten blocks of the real ground.
     */
    private void hollowForCities(WorldGenLevel level, StructureManager manager, SectionPos sectionPos, Registry<Structure> registry,
                                 BoundingBox writable) {
        BlockState air = Blocks.CAVE_AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        WorldModel model = model();
        for (Structure structure : (Iterable<Structure>) registry.stream()::iterator) {
            if (structure.terrainAdaptation() != TerrainAdjustment.BEARD_BOX) continue;
            for (StructureStart start : Mc.startsForStructure(manager, sectionPos.x(), sectionPos.z(), structure)) {
                if (start == null || !start.isValid()) continue;
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox b = piece.getBoundingBox();
                    if (!b.intersects(writable)) continue;
                    int floor = b.minY() + (piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe
                            ? pe.getGroundLevelDelta() : 0);
                    floor = Math.max(floor, level.getMinY() + 6);
                    for (int x = Math.max(b.minX(), writable.minX()); x <= Math.min(b.maxX(), writable.maxX()); x++) {
                        for (int z = Math.max(b.minZ(), writable.minZ()); z <= Math.min(b.maxZ(), writable.maxZ()); z++) {
                            int top = Math.min(b.maxY(), model.terrainHeight(x, z) - 10);
                            for (int y = floor; y <= top; y++) {
                                p.set(x, y, z);
                                if (!level.getBlockState(p).isAir()) level.setBlock(p, air, 2);
                            }
                        }
                    }
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
        if (!model.cfg().waitForOsm) {
            return CompletableFuture.completedFuture(model.rasterIfLoaded(pos.getMinBlockX(), pos.getMinBlockZ()));
        }
        return model.regions().futureForBlock(pos.getMinBlockX(), pos.getMinBlockZ());
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        WorldModel model = model();
        // Outside the hard limit: nothing to wait for (the biome source answers without the map there).
        if (HardLimit.blocks(chunk.getPos().x(), chunk.getPos().z())) return super.createBiomes(randomState, blender, structureManager, chunk);
        // Biomes depend on water / land cover, so wait (asynchronously) for the region first.
        return regionFuture(model, chunk).thenComposeAsync(raster -> {
            // Layout 2's cave biomes sit in the underground band: know its top before the biomes are filled in.
            if (model.cfg().undergroundVersion >= 2) model.band().top(chunk.getPos().x(), chunk.getPos().z());
            return super.createBiomes(randomState, blender, structureManager, chunk);
        }, Util.backgroundExecutor());
    }

    @Override
    protected CompletableFuture<ChunkAccess> fillTerrain(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        WorldModel model = model();
        if (HardLimit.blocks(chunk.getPos().x(), chunk.getPos().z())) {
            // Outside the hard limit: an empty chunk (a barrier wall where it meets the allowed area), no downloads.
            HardLimit.fillBlocked(chunk);
            return CompletableFuture.completedFuture(chunk);
        }
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

    /**
     * The whole terrain step for a chunk outside Minecraft's chunk system (fast pre-generation, see FastPregen): what
     * {@link #fillTerrain} and the cave step do, with the map region already at hand.
     */
    public void buildDirect(ChunkAccess chunk, RegionRaster raster, long seed) {
        fillChunk(model(), chunk, raster);
        carve(seed, chunk);
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
        // Layout 2: deepslate from the underground band's vanilla Y 0, so caves show vanilla's stone over deepslate.
        boolean layout2 = cfg.undergroundVersion >= 2;
        int deepslateBase = layout2 ? model.band().deepslateTop(minX >> 4, minZ >> 4) : cfg.seaLevelY - 60;
        int[][] deepslateLine = layout2 ? model.band().deepslateLine(minX >> 4, minZ >> 4) : null;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = raster == null ? -1 : raster.index(x, z);
                BiomeClassifier.Climate climate = model.climateWithSnow(x, z, ok[lx][lz] ? raw[lx][lz] : Double.NaN);
                try {
                    int deepslate = layout2 ? deepslateLine[lx][lz] : deepslateBase;
                    painter.paint(sink, x, z, terrain[lx][lz], climate, raster, idx, deepslate);
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
            // Layout 2: the rock decides (stone takes plain ore, deepslate deepslate ore): the blended line can put
            // stone a little below this chunk's own deepslate Y.
            OreGenerator.place(chunk, terrain, layout2 ? Integer.MIN_VALUE : deepslateBase, cfg.minY, seed);
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

    /** Whether a block must be fitted to its neighbours once they exist (stairs, walls, fences, rails, doors). */
    public static boolean needsPostProcessing(BlockState state) {
        Block b = state.getBlock();
        return b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof BaseRailBlock
                || b instanceof StairBlock || b instanceof DoorBlock;
    }

    private static void pasteLandmarks(WorldModel model, ChunkAccess chunk, int minX, int minZ, ColumnPainter.Sink sink) {
        pasteLandmarks(model, minX, minZ, sink);
    }

    /** The landmark schematics over the 16 x 16 columns from minX, minZ, through the sink. */
    public static void pasteLandmarks(WorldModel model, int minX, int minZ, ColumnPainter.Sink sink) {
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
    public static double smoothed(double[][] raw, boolean[][] ok, int lx, int lz, double threshold, double fallback) {
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
        if (HardLimit.blocks(chunk.getPos().x(), chunk.getPos().z())) return;
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
                // Moss, azaleas, cave vines and spore blossoms, dripstone, sculk: the cave biomes' own decoration.
                if (model.cfg().undergroundVersion >= 2) UndergroundBiomes.place(model, level, this, chunk.getPos());
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
        try {
            // Kelp, seagrass, sea pickles and coral by water biome, after the decoration so piers and buoys come first.
            SeaVegetation.place(level, this, chunk.getPos());
        } catch (RuntimeException e) {
            System.err.println("[orbis] Sea vegetation failed for chunk " + chunk.getPos() + ": " + e);
        }
    }

    // ---- queries -----------------------------------------------------------------

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        WorldModel model = model();
        int terrain = model.terrainHeight(x, z);
        if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
            terrain = Math.max(terrain, model().cfg().waterLevelY());
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
                    : y <= model().cfg().waterLevelY() ? Blocks.WATER.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(level.getMinY(), states);
    }

    @Override
    protected void debugInfo(List<String> info, BlockPos pos) {
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
