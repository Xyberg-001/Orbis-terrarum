package com.berg.orbis.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft 26.3 side of the Orbis chunk generator. 26.3 builds terrain in one chunk step ({@code buildTerrain},
 * which replaced 26.2's noise, surface and carver steps): Orbis fills the terrain, then carves its caves.
 */
public abstract class ChunkGeneratorBridge extends ChunkGenerator {

    protected ChunkGeneratorBridge(BiomeSource biomeSource) {
        super(biomeSource);
    }

    /** Fills the chunk's terrain: ground, surface blocks, water, buildings. */
    protected abstract CompletableFuture<ChunkAccess> fillTerrain(Blender blender, RandomState randomState,
                                                                  StructureManager structureManager, ChunkAccess chunk);

    /** Carves caves into a chunk whose terrain is already filled. */
    protected abstract void carve(long seed, ChunkAccess chunk);

    /** Lines for the F3 debug screen. */
    protected abstract void debugInfo(List<String> info, BlockPos pos);

    @Override
    public final CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                             StructureManager structureManager, BiomeManager biomeManager,
                                                             WorldGenRegion level, Set<Holder<Biome>> biomes) {
        long seed = level.getSeed();
        return fillTerrain(blender, randomState, structureManager, chunk).thenApply(filled -> {
            carve(seed, filled);
            return filled;
        });
    }

    @Override
    public final void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
        debugInfo(info, pos);
    }
}
