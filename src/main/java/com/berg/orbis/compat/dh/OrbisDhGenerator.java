package com.berg.orbis.compat.dh;

import com.berg.orbis.map.RegionReader;
import com.berg.orbis.worldgen.PregenMap;
import com.mojang.serialization.Codec;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Distant Horizons generator for Orbis Terrarum levels (phase 1: saved chunks).
 *
 * <p>Distant Horizons asks for square areas of 64 x 64 data columns at a detail level d, one column standing for
 * 2^d x 2^d blocks. Every column is taken from the chunk saved on disk at that spot, read straight from the region
 * file ({@link RegionReader}): at the finest level each block column is used, at coarser levels the column in the
 * middle of the area it stands for, so a distant view of a whole pre-generated area only reads a sample of its
 * chunks. Spots without a finished saved chunk are left empty.
 *
 * <p>A column is written bottom to top as runs of equal blocks (Distant Horizons' format: no gaps, heights relative
 * to the level's floor, the top exclusive). Near the surface every block is kept (buildings, trees, water); deeper
 * than {@link #DETAIL_DEPTH} blocks below the surface the rest is one run, since it is never seen from afar.
 */
final class OrbisDhGenerator implements IDhApiWorldGenerator {

    /** Blocks below a column's top that are kept block by block. */
    private static final int DETAIL_DEPTH = 48;
    private static final int LARGEST_DETAIL = 12;

    private final ServerLevel level;
    private final IDhApiLevelWrapper wrapper;
    private final RegionReader reader;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final int minY, height, minSection;
    private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    // -Dorbis.dh.validate=true makes DH check every column we hand it (for development; it costs time).
    private final boolean validate = Boolean.getBoolean("orbis.dh.validate");
    private final AtomicLong chunksRead = new AtomicLong(), columnsWritten = new AtomicLong();
    private volatile long startedAt;
    private final AtomicLong calls = new AtomicLong();
    private volatile boolean askedType, taskStartSeen;
    private volatile boolean failureLogged;

    OrbisDhGenerator(ServerLevel level, IDhApiLevelWrapper wrapper) {
        this.level = level;
        this.wrapper = wrapper;
        this.reader = new RegionReader(PregenMap.regionDir(level));
        PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        this.blockCodec = factory.blockStatesContainerCodec();
        this.biomeCodec = factory.biomeContainerCodec();
        this.minY = level.getMinY();
        this.height = level.getHeight();
        this.minSection = minY >> 4;
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return LARGEST_DETAIL;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        if (!askedType) {
            askedType = true;
            System.out.println("[orbis] Distant Horizons asked the Orbis LOD generator for its return type");
        }
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return validate;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                              IDhApiFullDataSource dataSource, EDhApiDistantGeneratorMode generatorMode,
                                              ExecutorService pool, Consumer<IDhApiFullDataSource> resultConsumer) {
        if (startedAt == 0) startedAt = System.nanoTime();
        long call = calls.incrementAndGet();
        if (call <= 5 || call % 1000 == 0) {
            System.out.println("[orbis] Distant Horizons request #" + call + ": chunk " + chunkPosMinX + ", " + chunkPosMinZ
                    + ", detail " + detailLevel + ", width " + dataSource.getWidthInDataColumns() + ", mode " + generatorMode);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                fill(chunkPosMinX, chunkPosMinZ, detailLevel, dataSource);
            } catch (RuntimeException e) {
                if (!failureLogged) {
                    failureLogged = true;
                    System.err.println("[orbis] Distant Horizons LOD generation failed at chunk " + chunkPosMinX + ", " + chunkPosMinZ
                            + " (detail " + detailLevel + "): " + e);
                    e.printStackTrace();
                }
                throw e;
            }
            resultConsumer.accept(dataSource);
        }, pool);
    }

    /** One decoded saved chunk. */
    private record SavedChunk(PalettedContainer<BlockState>[] sections, PalettedContainerRO<Holder<Biome>>[] biomes, long[] heights, int bits) {}

    private void fill(int chunkMinX, int chunkMinZ, byte detail, IDhApiFullDataSource ds) {
        int width = ds.getWidthInDataColumns();
        int step = 1 << detail;
        int baseX = chunkMinX << 4, baseZ = chunkMinZ << 4;
        int half = step >> 1;
        Map<Long, SavedChunk> chunks = new HashMap<>();
        List<DhApiTerrainDataPoint> column = new ArrayList<>();
        for (int rz = 0; rz < width; rz++) {
            for (int rx = 0; rx < width; rx++) {
                int bx = baseX + rx * step + half, bz = baseZ + rz * step + half;
                int cx = bx >> 4, cz = bz >> 4;
                long key = ((long) cx << 32) | (cz & 0xffffffffL);
                SavedChunk chunk = chunks.computeIfAbsent(key, k -> load(cx, cz));
                if (chunk == NONE) continue;
                column.clear();
                if (!buildColumn(chunk, bx & 15, bz & 15, column)) continue;
                ds.setApiDataPointColumn(rx, rz, EDhApiWorldGenerationStep.LIGHT, column);
                columnsWritten.incrementAndGet();
            }
        }
        logProgress();
    }

    private volatile long lastLogMillis;

    /** A progress line every 15 s while Distant Horizons is asking for terrain (for comparing speeds). */
    private void logProgress() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastLogMillis < 15_000) return;
        synchronized (this) {
            if (nowMillis - lastLogMillis < 15_000) return;
            lastLogMillis = nowMillis;
        }
        double s = Math.max(1e-3, (System.nanoTime() - startedAt) / 1e9);
        // println, not printf: Minecraft copies only println output into latest.log.
        System.out.println(String.format("[orbis] Distant Horizons LOD generator: %,d saved chunks read (%.0f/s), %,d columns written, %.0f s",
                chunksRead.get(), chunksRead.get() / s, columnsWritten.get(), s));
    }

    private static final SavedChunk NONE = new SavedChunk(null, null, null, 0);

    private SavedChunk load(int cx, int cz) {
        CompoundTag tag;
        try {
            tag = reader.read(cx, cz);
        } catch (Exception e) {
            return NONE;
        }
        if (tag == null || !"minecraft:full".equals(tag.getStringOr("Status", ""))) return NONE;
        chunksRead.incrementAndGet();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sections = new PalettedContainer[height >> 4];
        @SuppressWarnings("unchecked")
        PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[height >> 4];
        long[] hm = tag.getCompoundOrEmpty("Heightmaps").getLongArray("WORLD_SURFACE").orElse(null);
        int bits = Mth.ceillog2(height + 1);
        // Only the sections a column reads are decoded: from DETAIL_DEPTH below the lowest top to the highest top.
        // Decoding is most of the cost, and an Orbis chunk has 88 sections of which the surface needs a handful.
        int lowIdx = 0, highIdx = sections.length - 1;
        if (hm != null) {
            int per = 64 / bits, lowTop = Integer.MAX_VALUE, highTop = Integer.MIN_VALUE;
            for (int i = 0; i < 256; i++) {
                int t = minY + (int) ((hm[i / per] >>> ((i % per) * bits)) & ((1L << bits) - 1)) - 1;
                lowTop = Math.min(lowTop, t);
                highTop = Math.max(highTop, t);
            }
            lowIdx = Math.max(0, ((lowTop - DETAIL_DEPTH) >> 4) - minSection);
            highIdx = Math.min(sections.length - 1, (Math.max(highTop, minY) >> 4) - minSection);
        }
        ListTag list = tag.getListOrEmpty("sections");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompoundOrEmpty(i);
            int idx = s.getByteOr("Y", (byte) 0) - minSection;
            if (idx < lowIdx || idx > highIdx) continue;
            if (s.get("block_states") != null) {
                sections[idx] = blockCodec.parse(NbtOps.INSTANCE, s.get("block_states")).result().orElse(null);
            }
            if (s.get("biomes") != null) {
                biomes[idx] = biomeCodec.parse(NbtOps.INSTANCE, s.get("biomes")).result().orElse(null);
            }
        }
        return new SavedChunk(sections, biomes, hm, bits);
    }

    private BlockState at(SavedChunk c, int x, int y, int z) {
        int idx = (y >> 4) - minSection;
        if (idx < 0 || idx >= c.sections.length || c.sections[idx] == null) return Blocks.AIR.defaultBlockState();
        return c.sections[idx].get(x, y & 15, z);
    }

    /** Y of the column's top non-air block, or minY - 1 when the column is empty. */
    private int top(SavedChunk c, int x, int z) {
        if (c.heights == null) {
            for (int y = minY + height - 1; y >= minY; y--) if (!at(c, x, y, z).isAir()) return y;
            return minY - 1;
        }
        int per = 64 / c.bits, i = z * 16 + x;
        int v = (int) ((c.heights[i / per] >>> ((i % per) * c.bits)) & ((1L << c.bits) - 1));
        return minY + v - 1;
    }

    /** Writes the column bottom to top; false when there is nothing to write. */
    private boolean buildColumn(SavedChunk c, int x, int z, List<DhApiTerrainDataPoint> out) {
        int top = top(c, x, z);
        int maxY = minY + height - 1;
        IDhApiBiomeWrapper biome = biome(c, x, Math.max(top, minY), z);
        IDhApiBlockStateWrapper air = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
        if (top < minY) {
            out.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, 0, height, air, biome));
            return true;
        }
        // Runs from the top down, collected in reverse and flipped at the end.
        List<DhApiTerrainDataPoint> down = new ArrayList<>();
        if (top < maxY) down.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top + 1 - minY, height, air, biome));
        int floor = Math.max(minY, top - DETAIL_DEPTH);
        BlockState run = at(c, x, top, z);
        int runTop = top;
        for (int y = top - 1; y >= floor; y--) {
            BlockState s = at(c, x, y, z);
            if (s == run) continue;
            down.add(point(run, y + 1, runTop, biome));
            run = s;
            runTop = y;
        }
        // The last run reaches down to the world's floor: the deep underground is never seen from afar.
        down.add(point(run, minY, runTop, biome));
        for (int i = down.size() - 1; i >= 0; i--) out.add(down.get(i));
        return true;
    }

    /** A run of one block from y0 to y1 (both inclusive, absolute Y). */
    private DhApiTerrainDataPoint point(BlockState state, int y0, int y1, IDhApiBiomeWrapper biome) {
        int sky = state.isAir() ? 0 : 15;
        return DhApiTerrainDataPoint.create((byte) 0, state.getLightEmission(), sky, y0 - minY, y1 + 1 - minY, block(state), biome);
    }

    private IDhApiBlockStateWrapper block(BlockState state) {
        return blockWrappers.computeIfAbsent(state, s -> DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {s}, wrapper));
    }

    private IDhApiBiomeWrapper biome(SavedChunk c, int x, int y, int z) {
        int idx = (y >> 4) - minSection;
        Holder<Biome> holder = null;
        if (idx >= 0 && idx < c.biomes.length && c.biomes[idx] != null) holder = c.biomes[idx].get(x >> 2, (y & 15) >> 2, z >> 2);
        if (holder == null) holder = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        return biomeWrappers.computeIfAbsent(holder, h -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] {h}, wrapper));
    }

    @Override
    public void preGeneratorTaskStart() {
        if (!taskStartSeen) {
            taskStartSeen = true;
            System.out.println("[orbis] Distant Horizons is starting generator tasks");
        }
    }

    @Override
    public void close() {
        reader.close();
        if (startedAt != 0) {
            double s = (System.nanoTime() - startedAt) / 1e9;
            System.out.println(String.format("[orbis] Distant Horizons LOD generator: %,d saved chunks read, %,d columns written in %.0f s",
                    chunksRead.get(), columnsWritten.get(), s));
        }
    }
}
