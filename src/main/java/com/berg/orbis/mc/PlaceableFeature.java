package com.berg.orbis.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * A vanilla world-generation feature ready to place. Minecraft versions store these differently (26.2: configured
 * features, 26.3: features); the bridge class {@code Mc} in {@code versions/<mc>/java} looks them up.
 *
 * <p>The package {@code com.berg.orbis.mc} holds the version bridges: every Minecraft version has its own copy of
 * {@code Mc}, {@code McClient}, {@code ChunkGeneratorBridge} and {@code BiomeSourceBridge} with the same names and
 * signatures, and the shared code only calls those. This interface is the one shared piece of the package.
 */
@FunctionalInterface
public interface PlaceableFeature {
    boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos);
}
