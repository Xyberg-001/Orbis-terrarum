package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Chunk saving with the compression done in parallel. Minecraft saves a chunk in three steps: a copy on the server
 * thread, the save data built on its background threads, and compression plus the region file write on one disk
 * thread per storage. The compression is most of that thread's work, so in Orbis worlds it is done right after the
 * save data is built, still on the background threads, into exactly the bytes the region file would have produced
 * (a 4-byte length, the compression id, the compressed data); the disk thread then only copies them into the file
 * (mixin RegionFileStorageMixin). The files are the same as Minecraft writes them.
 */
public final class ChunkCompression {

    private ChunkCompression() {
    }

    /** Whether chunks of this server are compressed ahead (an Orbis world, and the setting on). */
    public static boolean on(net.minecraft.server.MinecraftServer server) {
        return OrbisMod.config().parallelChunkCompression && OrbisMod.isOrbisWorld(server);
    }

    /** Compresses a chunk's save data the way the region file would and keeps the bytes with it (left alone on failure). */
    public static void prepare(CompoundTag tag) {
        try {
            RegionFileVersion version = RegionFileVersion.getSelected();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
            bytes.write(0);
            bytes.write(0);
            bytes.write(0);
            bytes.write(0);
            bytes.write(version.getId());
            try (DataOutputStream out = new DataOutputStream(version.wrap(bytes))) {
                NbtIo.write(tag, out);
            }
            byte[] b = bytes.toByteArray();
            ByteBuffer buffer = ByteBuffer.wrap(b);
            buffer.putInt(0, b.length - 5 + 1);
            ((PrecompressedTag) (Object) tag).orbis$setPrecompressed(buffer);
        } catch (IOException | RuntimeException e) {
            // left to the disk thread, as without this
        }
    }

    /** The compression id the bytes were made with. */
    public static int versionOf(ByteBuffer prepared) {
        return prepared.get(4);
    }
}
