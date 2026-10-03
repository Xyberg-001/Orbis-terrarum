package com.berg.orbis.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * Which chunks a world already has finished on disk, read straight from the region files. A sweep skips those
 * instead of loading them: Minecraft marks every chunk it loads as unsaved (reading its light flag does it), so a
 * loaded chunk is written back when it unloads even if nothing changed, and a re-sweep of a finished area rewrote the
 * whole world (the Bergen repair, 2 Oct 2026: 563,000 chunks rewritten to regenerate 5,072, 1.5 hours). A chunk
 * counts as finished when its saved status is "minecraft:full"; anything unclear (another compression, a chunk
 * stored outside the region file, a file being written) counts as not finished and is left to the game as before.
 * Used by one sweep thread.
 */
final class DiskChunks {

    private static final byte[] STATUS = {8, 0, 6, 'S', 't', 'a', 't', 'u', 's'};
    private final Path folder;
    /** The last few region files read whole (a sweep moves through them tile by tile). */
    private final Map<Long, byte[]> files = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > 6;
        }
    };

    DiskChunks(ServerLevel level) {
        this(DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region"));
    }

    /** A region folder directly (offline checks). */
    DiskChunks(Path regionFolder) {
        this.folder = regionFolder;
    }

    /** True when the chunk is saved as finished; false when it is missing, unfinished or cannot be told. */
    boolean finished(int cx, int cz) {
        byte[] file = file(cx >> 5, cz >> 5);
        if (file.length < 8192) return false;
        ByteBuffer b = ByteBuffer.wrap(file);
        int location = b.getInt(((cx & 31) + (cz & 31) * 32) * 4);
        if (location == 0) return false;
        long start = (long) (location >>> 8) * 4096;
        int sectors = location & 0xff;
        if (start + 5 > file.length) return false;
        int length = b.getInt((int) start);
        int type = file[(int) start + 4];
        if (length <= 1 || length > sectors * 4096 || start + 4 + length > file.length) return false;
        if (type != 2 && type != 3) return false; // zlib (Minecraft's default) or none; others are left to the game
        try (InputStream in = type == 2
                ? new InflaterInputStream(new ByteArrayInputStream(file, (int) start + 5, length - 1))
                : new ByteArrayInputStream(file, (int) start + 5, length - 1)) {
            return "minecraft:full".equals(status(in));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The value of the first "Status" string field in a chunk's NBT, decompressing only as far as it. */
    private static String status(InputStream in) throws IOException {
        int matched = 0, c;
        while ((c = in.read()) >= 0) {
            if (c == (STATUS[matched] & 0xff)) {
                if (++matched == STATUS.length) {
                    int n = (in.read() << 8) | in.read();
                    if (n <= 0 || n > 64) return null;
                    byte[] s = in.readNBytes(n);
                    return new String(s, java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                matched = c == (STATUS[0] & 0xff) ? 1 : 0;
            }
        }
        return null;
    }

    private byte[] file(int rx, int rz) {
        long key = ChunkPos.pack(rx, rz);
        byte[] f = files.get(key);
        if (f == null) {
            Path p = folder.resolve("r." + rx + "." + rz + ".mca");
            try {
                f = Files.isRegularFile(p) ? Files.readAllBytes(p) : new byte[0];
            } catch (IOException e) {
                f = new byte[0];
            }
            files.put(key, f);
        }
        return f;
    }
}
