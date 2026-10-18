package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.SectionPacking;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A section of one block (or biome) only is packed for saving without Minecraft's re-encoding: see worldgen/SectionPacking. */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerPackMixin<T> {
    @Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void orbis$packUniform(Strategy<T> strategy, CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> cir) {
        @SuppressWarnings("unchecked")
        PalettedContainerRO.PackedData<T> p = SectionPacking.uniform((PalettedContainer<T>) (Object) this, strategy);
        if (p != null) cir.setReturnValue(p);
    }
}
