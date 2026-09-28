package com.berg.orbis.mixin.client;

import com.berg.orbis.worldgen.PregenTask;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The integrated server pauses whenever the client says it is paused: the Escape menu, or the window losing
 * focus (options "pauseOnLostFocus", on by default). A paused server keeps generating the chunks already
 * requested but never ticks, so it never unloads or saves them and the sweep freezes; a sweep of Bergen once
 * lost four hours that way because the window was switched away from. While a pre-generation runs the game
 * therefore reports itself as not paused, exactly like a world opened to LAN.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPauseMixin {

    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$noPauseWhilePregenerating(CallbackInfoReturnable<Boolean> cir) {
        if (PregenTask.isRunning()) cir.setReturnValue(false);
    }
}
