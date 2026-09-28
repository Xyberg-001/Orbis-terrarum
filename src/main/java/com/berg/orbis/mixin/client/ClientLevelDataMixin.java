package com.berg.orbis.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla draws a black "void" disc under the horizon whenever the camera is
 * below Y 63 (the sea level of a normal world). Orbis Terrarum puts real sea
 * level at Y -1700 so that Everest fits under the ceiling, which would paint
 * the lower half of the sky black everywhere. Flat worlds use the dimension
 * floor as the horizon instead; do the same here.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataMixin {

    @Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$horizonAtWorldFloor(LevelHeightAccessor level, CallbackInfoReturnable<Double> cir) {
        if (level.getMinY() != com.berg.orbis.config.OrbisConfig.DIMENSION_MIN_Y) return; // a vanilla world
        cir.setReturnValue((double) level.getMinY());
    }
}
