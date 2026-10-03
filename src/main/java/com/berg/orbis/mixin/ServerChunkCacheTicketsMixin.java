package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.PregenTask;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A frozen world (/tick freeze, and the pause during a pre-generation) also stops expiring the temporary chunk
 * tickets, the short holds that keep chunks loaded around those being generated: they never ran out, the chunks
 * never unloaded, and a sweep of Stord sat at 4,482 loaded chunks "waiting for unloads" for five and a half hours.
 * While a pre-generation runs on this level, the tickets keep expiring as in a running world.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheTicketsMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/TickRateManager;runsNormally()Z"))
    private boolean orbisterrarum$expireTicketsDuringPregen(TickRateManager ticks) {
        return ticks.runsNormally() || PregenTask.unloadsPerTick(level) > 0;
    }
}
