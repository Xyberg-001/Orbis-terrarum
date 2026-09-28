package com.berg.orbis.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/** Chunks handed to the region-file writer and not yet written: the write backlog. */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("pendingWrites")
    SequencedMap<ChunkPos, ?> orbisterrarum$pendingWrites();
}
