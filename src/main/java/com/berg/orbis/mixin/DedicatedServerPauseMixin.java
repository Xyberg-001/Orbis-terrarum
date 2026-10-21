package com.berg.orbis.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dedicated server pauses once nobody has been on for {@code pause-when-empty-seconds} (60 by default since 1.21.2):
 * it stops ticking, and a pre-generation running for an empty server stood still after a minute (found 17 Oct 2026 on
 * a test server: 440 chunks, then "Server empty for 60 seconds, pausing"). While an Orbis pre-generation runs or waits
 * to run, the server does not pause; it pauses as usual a minute after it ends.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerPauseMixin {
    @Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void orbis$noPauseWhilePregenerating(CallbackInfoReturnable<Integer> cir) {
        if (com.berg.orbis.worldgen.PregenTask.isRunning() || com.berg.orbis.worldgen.PregenTasks.running() != null) cir.setReturnValue(0);
    }
}
