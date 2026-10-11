package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shops' display frames (locked item frames in an Orbis world) are sent to players only within {@code displayFrameRange} blocks.
 * Vanilla sends item frames 160 blocks out (10 chunks), and every one sent is drawn with its item: a city's shops held some 17,000, thousands
 * of them in view from a street through the shop windows, each costing the frame rate, and each another entity the server checks
 * against every player that moves. A frame a player hangs is not locked and keeps vanilla's reach. On the server, so players without
 * the mod gain too.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class DisplayFrameRangeMixin {
    @Shadow @Final Entity entity;

    @Inject(method = "getEffectiveRange", at = @At("RETURN"), cancellable = true)
    private void orbis$displayFrameRange(CallbackInfoReturnable<Integer> cir) {
        if (!(this.entity instanceof ItemFrame frame) || !((ItemFrameAccessor) frame).orbis$isFixed()) return;
        int range = OrbisMod.config().displayFrameRange;
        if (range > 0 && range < cir.getReturnValueI() && OrbisMod.isOrbisLevel(frame.level())) cir.setReturnValue(range);
    }
}
