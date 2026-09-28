package com.berg.orbis.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the generator drop a structure's cached bounding box after moving its pieces vertically. */
@Mixin(StructureStart.class)
public interface StructureStartAccessor {

    @Accessor("cachedBoundingBox")
    void orbis$setCachedBoundingBox(BoundingBox box);
}
