package com.berg.orbis.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The region-file writer behind a chunk map (ChunkMap extends SimpleRegionStorage). */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {

    @Accessor("worker")
    IOWorker orbisterrarum$worker();
}
