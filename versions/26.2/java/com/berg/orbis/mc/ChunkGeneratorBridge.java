package com.berg.orbis.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft 26.2 side of the Orbis chunk generator. 26.2 builds terrain in three chunk steps (noise, surface,
 * carvers); Orbis fills the whole terrain in the first one and carves caves in the last.
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
    public final CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                              StructureManager structureManager, ChunkAccess chunk) {
        return fillTerrain(blender, randomState, structureManager, chunk);
    }

    @Override
    public final void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        // The surface is part of fillTerrain.
    }

    @Override
    public final void applyCarvers(WorldGenRegion level, long seed, RandomState randomState, BiomeManager biomeManager,
                                   StructureManager structureManager, ChunkAccess chunk) {
        carve(seed, chunk);
    }

    @Override
    public final void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        debugInfo(info, pos);
    }
}
