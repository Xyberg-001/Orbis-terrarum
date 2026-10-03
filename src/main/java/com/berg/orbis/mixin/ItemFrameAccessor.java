package com.berg.orbis.mixin;

import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the furnishing lock its display frames the way vanilla's structures do (no setter for "fixed"). */
@Mixin(ItemFrame.class)
public interface ItemFrameAccessor {

    @Accessor("fixed")
    boolean orbis$isFixed();

    @Accessor("fixed")
    void orbis$setFixed(boolean fixed);
}
