package com.berg.orbis.mixin;

import com.berg.orbis.sky.RealPrecipitation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rain or snow by the real temperature while real weather runs ({@link RealPrecipitation}): what falls, on the server
 * and on a client with Orbis, and so where snow settles (Biome.shouldSnow asks this too). A biome without
 * precipitation (desert) stays dry; water freezing is not touched.
 */
@Mixin(Biome.class)
public abstract class BiomePrecipitationMixin {

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$realPrecipitation(BlockPos pos, int seaLevel, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Boolean rain = RealPrecipitation.rainAt(pos.getY());
        if (rain == null || !((Biome) (Object) this).hasPrecipitation()) return;
        cir.setReturnValue(rain ? Biome.Precipitation.RAIN : Biome.Precipitation.SNOW);
    }
}
