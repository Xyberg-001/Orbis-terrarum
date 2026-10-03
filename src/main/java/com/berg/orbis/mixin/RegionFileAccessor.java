package com.berg.orbis.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.ByteBuffer;

/** A region file's compression, and its write of a finished chunk buffer (for RegionFileStorageMixin). */
@Mixin(RegionFile.class)
public interface RegionFileAccessor {

    @Accessor("version")
    RegionFileVersion orbis$version();

    @Invoker("write")
    void orbis$write(ChunkPos pos, ByteBuffer buffer);
}
