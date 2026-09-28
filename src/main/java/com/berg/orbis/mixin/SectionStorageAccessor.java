package com.berg.orbis.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {

    @Accessor("levelHeightAccessor")
    LevelHeightAccessor orbisterrarum$heightAccessor();

    @Invoker("getOrLoad")
    Optional<?> orbisterrarum$getOrLoad(long sectionKey);
}
