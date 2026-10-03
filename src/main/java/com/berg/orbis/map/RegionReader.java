package com.berg.orbis.map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads saved chunks straight from a folder of region files, read-only and safe to use from many threads at once
 * (positional reads on shared channels). It bypasses Minecraft's chunk system and its single IO thread, so a whole
 * pre-generated area can be read as fast as the disk and the decompression allow. Chunks the server holds in memory
 * but has not written yet are not seen; callers that need them read the loaded chunk instead.
 */
public final class RegionReader implements AutoCloseable {

    private static final Region MISSING = new Region(null, null);

    private final Path dir;
    private final ConcurrentHashMap<Long, Region> regions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private record Region(FileChannel channel, int[] locations) {}

    public RegionReader(Path regionDir) {
        this.dir = regionDir;
    }

    /** The chunk's NBT, or null when it was never saved (or its data is unreadable). */
    public CompoundTag read(int chunkX, int chunkZ) throws IOException {
        if (closed) return null;
        Region r = region(chunkX >> 5, chunkZ >> 5);
        if (r == MISSING) return null;
        int entry = r.locations[(chunkX & 31) + (chunkZ & 31) * 32];
        if (entry == 0) return null;
        long offset = (long) (entry >>> 8) * 4096L;
        int sectors = entry & 0xFF;
        ByteBuffer head = ByteBuffer.allocate(5);
        readFully(r.channel, head, offset);
        int length = head.getInt(0);
        int type = head.get(4) & 0xFF;
        if (length <= 0 || (sectors > 0 && length > sectors * 4096)) return null;

        InputStream raw;
        if ((type & 0x80) != 0) { // stored in an external c.<x>.<z>.mcc file
            Path external = dir.resolve("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.exists(external)) return null;
            raw = Files.newInputStream(external);
            type &= 0x7F;
        } else {
            ByteBuffer body = ByteBuffer.allocate(length - 1);
            readFully(r.channel, body, offset + 5);
            raw = new ByteArrayInputStream(body.array());
        }
        RegionFileVersion version = RegionFileVersion.fromId(type);
        if (version == null) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(version.wrap(raw)))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    private Region region(int rx, int rz) {
        long key = ((long) rx << 32) | (rz & 0xffffffffL);
        return regions.computeIfAbsent(key, k -> open(rx, rz));
    }

    private Region open(int rx, int rz) {
        Path file = dir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file)) return MISSING;
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
            ByteBuffer header = ByteBuffer.allocate(4096);
            readFully(ch, header, 0);
            int[] locations = new int[1024];
            for (int i = 0; i < 1024; i++) locations[i] = header.getInt(i * 4);
            return new Region(ch, locations);
        } catch (IOException e) {
            return MISSING;
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, position + buf.position());
            if (n < 0) throw new IOException("region file ends early");
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Region r : regions.values()) {
            if (r.channel != null) {
                try {
                    r.channel.close();
                } catch (IOException ignored) {
                }
            }
        }
        regions.clear();
    }
}
