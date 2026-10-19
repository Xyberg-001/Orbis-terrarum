package com.berg.orbis.mixin.client;

import com.berg.orbis.client.LocalSeasons;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grass coloured by its own place's season ({@link LocalSeasons}): the game's grass resolver is Biome::getGrassColor. */
@Mixin(Biome.class)
public abstract class BiomeGrassColourMixin {

    @Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
    private void orbisterrarum$localSeason(double x, double z, CallbackInfoReturnable<Integer> cir) {
        int game = cir.getReturnValueI();
        int c = LocalSeasons.grass((Biome) (Object) this, x, z, game);
        if (c != game) cir.setReturnValue(c);
    }
}
