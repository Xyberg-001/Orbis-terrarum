package com.berg.orbis.mc;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Minecraft 26.3 side of the Orbis biome source: 26.3 asks the biome source for a resolver. */
public abstract class BiomeSourceBridge extends BiomeSource {

    /** The biome at quart (4-block) coordinates. */
    public abstract Holder<Biome> biomeAt(int qx, int qy, int qz);

    @Override
    public final BiomeResolver createResolver(Climate.Sampler sampler) {
        return this::biomeAt;
    }
}
