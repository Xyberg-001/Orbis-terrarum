package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.PrecompressedTag;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

/** Room on a chunk's save data for its compressed bytes, made on a background thread (worldgen/ChunkCompression). */
@Mixin(CompoundTag.class)
public abstract class CompoundTagMixin implements PrecompressedTag {

    @Unique
    private volatile ByteBuffer orbis$bytes;

    @Override
    public ByteBuffer orbis$precompressed() {
        return orbis$bytes;
    }

    @Override
    public void orbis$setPrecompressed(ByteBuffer bytes) {
        orbis$bytes = bytes;
    }
}
