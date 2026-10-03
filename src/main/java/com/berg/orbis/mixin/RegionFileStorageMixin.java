package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.ChunkCompression;
import com.berg.orbis.worldgen.PrecompressedTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * The region file writer (one disk thread per storage): a chunk whose save data was already compressed on a
 * background thread (worldgen/ChunkCompression) is copied into the file as it is, instead of being compressed here.
 * Only when the compression matches the file's; everything else is written as before.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Unique
    private static final ThreadLocal<ByteBuffer> orbis$ready = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    private void orbis$takePrepared(ChunkPos pos, CompoundTag tag, CallbackInfo ci) {
        orbis$ready.set(tag == null ? null : ((PrecompressedTag) (Object) tag).orbis$precompressed());
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;getChunkDataOutputStream(Lnet/minecraft/world/level/ChunkPos;)Ljava/io/DataOutputStream;"))
    private DataOutputStream orbis$writePrepared(RegionFile file, ChunkPos pos) throws IOException {
        ByteBuffer ready = orbis$ready.get();
        if (ready != null && ((RegionFileAccessor) file).orbis$version().getId() == ChunkCompression.versionOf(ready)) {
            ((RegionFileAccessor) file).orbis$write(pos, ready.duplicate());
            return new DataOutputStream(OutputStream.nullOutputStream());
        }
        orbis$ready.remove();
        return file.getChunkDataOutputStream(pos);
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;write(Lnet/minecraft/nbt/CompoundTag;Ljava/io/DataOutput;)V"))
    private void orbis$skipWritten(CompoundTag tag, DataOutput out) throws IOException {
        if (orbis$ready.get() != null) {
            orbis$ready.remove(); // already in the file
            return;
        }
        NbtIo.write(tag, out);
    }
}
