package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * No height-based cooling in Orbis Terrarum worlds.
 *
 * Vanilla lowers every biome's temperature by 0.05 per 40 blocks above
 * "sea level + 17", which with real sea level at Y -1700 turns a town at
 * 1300 m into an arctic one: it snowed in Kathmandu, and Everest was no
 * colder than Denver. Orbis biomes are already chosen from the real climate
 * at the real elevation (latitude and a lapse rate), so the biome's own
 * temperature is the right one for rain, snow and ice at any height.
 *
 * The method compares the block's Y with a threshold local; the threshold is
 * pushed out of reach for Orbis worlds, which are recognised by their sea
 * level (no vanilla dimension has one anywhere near it).
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    @ModifyVariable(method = "getHeightAdjustedTemperature", at = @At("STORE"), index = 4)
    private int orbisterrarum$noHeightCooling(int threshold, BlockPos pos, int seaLevel) {
        return OrbisMod.isOrbisSeaLevel(seaLevel) ? Integer.MAX_VALUE : threshold;
    }
}
