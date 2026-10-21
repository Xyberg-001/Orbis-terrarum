package com.berg.orbis.mixin.client;

import com.berg.orbis.client.LocalSeasons;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves coloured by their own place's season ({@link LocalSeasons}): the game's foliage resolver is the lambda
 * (biome, x, z) -> biome.getFoliageColor(), the first lambda of BiomeColors (26.2 and 26.3), the only one given the place.
 */
@Mixin(BiomeColors.class)
public abstract class BiomeColorsFoliageMixin {

    @Inject(method = "lambda$static$0", at = @At("RETURN"), cancellable = true)
    private static void orbisterrarum$localSeason(Biome biome, double x, double z, CallbackInfoReturnable<Integer> cir) {
        int game = cir.getReturnValueI();
        int c = LocalSeasons.foliage(biome, x, z, game);
        if (c != game) cir.setReturnValue(c);
    }
}
