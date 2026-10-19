package com.berg.orbis.mixin.client;

import com.berg.orbis.client.LocalWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's own weather on their screen ({@link LocalWeather}): the client level's rain and thunder levels, which the
 * rain and snow drawn, the sky's darkening, the fog, the rain sounds and shaders all read. The server's levels (a
 * singleplayer game's server lives in the same game) are left alone, and so is all of the world's gameplay weather.
 */
@Mixin(Level.class)
public abstract class LevelLocalWeatherMixin {

    @Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
    private void orbisterrarum$localRain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel) || !LocalWeather.active()) return;
        cir.setReturnValue(LocalWeather.rainLevel(partialTick));
    }

    @Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
    private void orbisterrarum$localThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel) || !LocalWeather.active()) return;
        // as vanilla: the thunder level counts only as far as it rains
        cir.setReturnValue(LocalWeather.thunderLevel(partialTick) * LocalWeather.rainLevel(partialTick));
    }
}
