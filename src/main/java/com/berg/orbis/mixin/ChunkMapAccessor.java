package com.berg.orbis.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk map's two holder maps: what is loaded, and what is on its way out (saved when it leaves the second). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> orbisterrarum$visibleChunkMap();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> orbisterrarum$pendingUnloads();
}
