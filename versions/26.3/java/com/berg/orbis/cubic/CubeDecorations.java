package com.berg.orbis.cubic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.worldgen.HardLimit;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.SeaVegetation;
import com.berg.orbis.worldgen.UndergroundBiomes;
import com.berg.orbis.worldgen.UndergroundFeatures;
import com.berg.orbis.worldgen.WorldModel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;

/**
 * The decoration of a cubic world (over the painted columns with their ores and caves): the underground features, trees, street furniture, interiors, signs, street life and sea plants, made chunk by chunk as in a
 * normal world, but in a {@link DecorationLevel} over the painted columns, and shared out to the cubes ({@link ChunkDecoration}). A cube
 * takes its share of the chunks it spans and of the chunks around them (a decoration reaches a chunk beyond its own). The decorations
 * and painted columns are kept for a while, since every cube of a column uses the same ones.
 */
final class CubeDecorations {
    private static final int KEEP_DECORATIONS = 512;
    private static final int KEEP_PAINTED = 512;

    private final ServerLevel level;
    private final RealWorldChunkGenerator generator;
    private final Map<Long, CompletableFuture<ChunkDecoration>> decorations = recent(KEEP_DECORATIONS);
    private final Map<Long, PaintedChunk> painted = recent(KEEP_PAINTED);

    CubeDecorations(ServerLevel level, RealWorldChunkGenerator generator) {
        this.level = level;
        this.generator = generator;
    }

    private static <V> Map<Long, V> recent(int keep) {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return this.size() > keep;
            }
        };
    }

    /** The chunk's decoration, made when first asked for (once its map regions and terrain tiles, and its neighbours', are there). */
    CompletableFuture<ChunkDecoration> chunk(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        synchronized (this.decorations) {
            CompletableFuture<ChunkDecoration> decoration = this.decorations.get(key);
            if (decoration == null) {
                decoration = this.start(chunkX, chunkZ);
                this.decorations.put(key, decoration);
            }
            return decoration;
        }
    }

    private CompletableFuture<ChunkDecoration> start(int chunkX, int chunkZ) {
        if (HardLimit.blocks(chunkX, chunkZ)) return CompletableFuture.completedFuture(ChunkDecoration.NONE);
        WorldModel model = this.generator.model();
        @SuppressWarnings("unchecked")
        CompletableFuture<RegionRaster>[] rasters = new CompletableFuture[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rasters[i * 3 + j] = OrbisCubeGenerator.regionFuture(model, (chunkX - 1 + i) << 4, (chunkZ - 1 + j) << 4);
            }
        }
        return CompletableFuture.allOf(rasters)
                .thenCompose(v -> OrbisCubeGenerator.terrainReady(model, (chunkX - 1) << 4, (chunkZ - 1) << 4, 48, System.currentTimeMillis()))
                .thenApplyAsync(v -> this.decorate(model, chunkX, chunkZ), Util.backgroundExecutor());
    }

    /** What the chunk generator's decoration does, in the same order (vanilla structures aside). */
    private ChunkDecoration decorate(WorldModel model, int chunkX, int chunkZ) {
        DecorationLevel region = new DecorationLevel(this.level, chunkX, chunkZ, this::painted);
        if (model.cfg().vanillaCaves) {
            try {
                // Dungeons, geodes, fossils, springs, lichen; then the cave biomes' moss, dripstone and sculk.
                UndergroundFeatures.place(model, region, this.generator, new ChunkPos(chunkX, chunkZ));
                if (model.cfg().undergroundVersion >= 2) UndergroundBiomes.place(model, region, this.generator, new ChunkPos(chunkX, chunkZ));
            } catch (RuntimeException e) {
                System.err.println("[orbis] Underground features failed for chunk " + chunkX + "," + chunkZ + " (cubic): " + e);
            }
        }
        try {
            model.decorator().decorateChunk(region, chunkX << 4, chunkZ << 4);
        } catch (RuntimeException e) {
            System.err.println("[orbis] Decoration failed for chunk " + chunkX + "," + chunkZ + " (cubic): " + e);
            e.printStackTrace();
        }
        try {
            SeaVegetation.place(region, this.generator, new ChunkPos(chunkX, chunkZ));
        } catch (RuntimeException e) {
            System.err.println("[orbis] Sea vegetation failed for chunk " + chunkX + "," + chunkZ + " (cubic): " + e);
        }
        return ChunkDecoration.of(region);
    }

    /** The chunk's columns as painted (with the map region if it is loaded, as the cubes were painted after waiting for it). */
    PaintedChunk painted(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        synchronized (this.painted) {
            PaintedChunk chunk = this.painted.get(key);
            if (chunk != null) return chunk;
        }
        WorldModel model = this.generator.model();
        PaintedChunk chunk = PaintedChunk.paint(model, this.level.getSeed(), chunkX << 4, chunkZ << 4, model.rasterIfLoaded(chunkX << 4, chunkZ << 4));
        synchronized (this.painted) {
            this.painted.putIfAbsent(key, chunk);
        }
        return chunk;
    }
}
