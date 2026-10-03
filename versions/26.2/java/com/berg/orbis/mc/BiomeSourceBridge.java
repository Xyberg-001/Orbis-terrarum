package com.berg.orbis.mc;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Minecraft 26.2 side of the Orbis biome source: 26.2 asks the biome source itself for each biome. */
public abstract class BiomeSourceBridge extends BiomeSource {

    /** The biome at quart (4-block) coordinates. */
    public abstract Holder<Biome> biomeAt(int qx, int qy, int qz);

    @Override
    public final Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        return biomeAt(qx, qy, qz);
    }
}
