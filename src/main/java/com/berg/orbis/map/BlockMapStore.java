package com.berg.orbis.map;

import com.berg.orbis.worldgen.PregenMap;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The Minecraft map of the overworld for the world map screen: one byte per block column (a vanilla map colour
 * with its brightness, as on a map item), 512 x 512 per region file, stored in {@code <world>/orbis-map/}.
 * Chunks are drawn in as they load and unload (so pre-generation and building keep it current at a few
 * microseconds a chunk), and a region pre-generated before this existed is drawn once from its region file the
 * first time someone looks at it. Also knows which chunks are fully generated, for greying the rest.
 */
public final class BlockMapStore {
    public static final int SIZE = 512;
    private static final int MAGIC = 0x4F4D4150; // "OMAP"
    private static final int CACHE = 64;

    public static final class Region {
        final int rx, rz;
        final byte[] col = new byte[SIZE * SIZE];
        final short[] height = new short[SIZE * SIZE];
        final long[] full = new long[16];
        int version;
        boolean complete, dirty;

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
            java.util.Arrays.fill(height, Short.MIN_VALUE);
        }
    }

    private final ServerLevel level;
    private final Path dir, regionDir;
    private final Codec<PalettedContainer<BlockState>> sectionCodec;
    private final Map<Long, Region> cache = new LinkedHashMap<>(64, 0.75f, true);

    public BlockMapStore(ServerLevel level) {
        this.level = level;
        this.dir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("orbis-map");
        this.regionDir = PregenMap.regionDir(level);
        this.sectionCodec = PalettedContainerFactory.create(level.registryAccess()).blockStatesContainerCodec();
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) ^ (rz & 0xffffffffL);
    }

    private Path file(int rx, int rz) {
        return dir.resolve("r." + rx + "." + rz + ".bin");
    }

    /** The region's map, from memory or disk, or a blank one. */
    public Region get(int rx, int rz) {
        synchronized (cache) {
            Region r = cache.get(key(rx, rz));
            if (r != null) return r;
            r = load(rx, rz);
            cache.put(key(rx, rz), r);
            if (cache.size() > CACHE) {
                var it = cache.entrySet().iterator();
                Region old = it.next().getValue();
                it.remove();
                if (old.dirty) save(old);
            }
            return r;
        }
    }

    private Region load(int rx, int rz) {
        Region r = new Region(rx, rz);
        Path f = file(rx, rz);
        if (!Files.exists(f)) return r;
        try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != MAGIC) return r;
            r.version = in.readInt();
            r.complete = in.readBoolean();
            for (int i = 0; i < 16; i++) r.full[i] = in.readLong();
            try (InputStream z = new InflaterInputStream(in)) {
                z.readNBytes(r.col, 0, r.col.length);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Map region " + rx + "," + rz + " unreadable, drawing it again: " + e);
            return new Region(rx, rz);
        }
        return r;
    }

    private void save(Region r) {
        byte[] col;
        long[] full;
        int version;
        boolean complete;
        synchronized (r) {
            col = r.col.clone();
            full = r.full.clone();
            version = r.version;
            complete = r.complete;
            r.dirty = false;
        }
        try {
            Files.createDirectories(dir);
            Path f = file(r.rx, r.rz), tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(raw)) {
                out.writeInt(MAGIC);
                out.writeInt(version);
                out.writeBoolean(complete);
                for (long l : full) out.writeLong(l);
                DeflaterOutputStream z = new DeflaterOutputStream(out, new Deflater(6));
                z.write(col);
                z.finish();
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save map region " + r.rx + "," + r.rz + ": " + e);
        }
    }

    /** Writes every changed region (on a background thread, and on shutdown). */
    public void flush() {
        List<Region> dirty = new ArrayList<>();
        synchronized (cache) {
            for (Region r : cache.values()) if (r.dirty) dirty.add(r);
        }
        for (Region r : dirty) save(r);
    }

    // ------------------------------------------------------------------ drawing chunks in

    /** Access to one chunk's blocks by local x, absolute y, local z. */
    private interface Column {
        BlockState at(int x, int y, int z);

        /** Y of the top non-air block of the column, or below the world. */
        int top(int x, int z);
    }

    /** A loaded chunk (main thread). */
    public void draw(LevelChunk chunk) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ChunkPos pos = chunk.getPos();
        drawColumns(pos.x(), pos.z(), new Column() {
            @Override
            public BlockState at(int x, int y, int z) {
                return chunk.getBlockState(p.set(x, y, z));
            }

            @Override
            public int top(int x, int z) {
                return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        });
    }

    /** A saved chunk, from its NBT (background thread). False when it is not fully generated. */
    private boolean draw(ChunkPos pos, CompoundTag tag) {
        if (!"minecraft:full".equals(tag.getStringOr("Status", ""))) return false;
        int minY = level.getMinY(), height = level.getHeight(), minSection = minY >> 4;
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sections = new PalettedContainer[height >> 4];
        ListTag list = tag.getListOrEmpty("sections");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompoundOrEmpty(i);
            int idx = s.getByteOr("Y", (byte) 0) - minSection;
            if (idx < 0 || idx >= sections.length || s.get("block_states") == null) continue;
            Optional<PalettedContainer<BlockState>> c = sectionCodec.parse(NbtOps.INSTANCE, s.get("block_states")).result();
            if (c.isPresent()) sections[idx] = c.get();
        }
        long[] hm = tag.getCompoundOrEmpty("Heightmaps").getLongArray("WORLD_SURFACE").orElse(null);
        int bits = Mth.ceillog2(height + 1), per = 64 / bits;
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        drawColumns(pos.x(), pos.z(), new Column() {
            @Override
            public BlockState at(int x, int y, int z) {
                int idx = (y >> 4) - minSection;
                if (idx < 0 || idx >= sections.length || sections[idx] == null) return air;
                return sections[idx].get(x, y & 15, z);
            }

            @Override
            public int top(int x, int z) {
                if (hm == null) return minY + height - 1;
                int i = z * 16 + x;
                int v = (int) ((hm[i / per] >>> ((i % per) * bits)) & ((1L << bits) - 1));
                return minY + v - 1;
            }
        });
        return true;
    }

    private void drawColumns(int cx, int cz, Column c) {
        Region r = get(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        int minY = level.getMinY();
        int ox = Math.floorMod(cx, 32) * 16, oz = Math.floorMod(cz, 32) * 16;
        byte[] col = new byte[256];
        short[] h = new short[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = c.top(x, z);
                BlockState s = y >= minY ? c.at(x, y, z) : null;
                int guard = 0;
                while (s != null && y > minY && s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == MapColor.NONE && guard++ < 96) {
                    s = c.at(x, --y, z);
                }
                MapColor mc = s == null ? MapColor.NONE : s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                MapColor.Brightness b;
                if (mc == MapColor.WATER) {
                    int depth = 0;
                    for (int yy = y - 1; yy > minY && depth < 32 && !c.at(x, yy, z).getFluidState().isEmpty(); yy--) depth++;
                    double d = depth * 0.1 + ((x + z) & 1) * 0.2;
                    b = d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    int north;
                    if (z > 0) north = h[(z - 1) * 16 + x];
                    else {
                        short known;
                        synchronized (r) {
                            known = oz + z > 0 ? r.height[(oz + z - 1) * SIZE + ox + x] : Short.MIN_VALUE;
                        }
                        north = known == Short.MIN_VALUE ? y : known;
                    }
                    double d = (y - north) * 0.8 + (((x + z) & 1) - 0.5) * 0.4;
                    b = d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                col[z * 16 + x] = mc == MapColor.NONE ? 0 : mc.getPackedId(b);
                h[z * 16 + x] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
            }
        }
        int bit = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
        synchronized (r) {
            for (int z = 0; z < 16; z++) {
                System.arraycopy(col, z * 16, r.col, (oz + z) * SIZE + ox, 16);
                System.arraycopy(h, z * 16, r.height, (oz + z) * SIZE + ox, 16);
            }
            r.full[bit >> 6] |= 1L << (bit & 63);
            r.version++;
            r.dirty = true;
        }
    }

    // ------------------------------------------------------------------ serving

    /**
     * The region with every saved, fully generated chunk drawn in: a region pre-generated before the map existed
     * is drawn from its region file here (about a thousand chunks; seconds), once. Background threads only.
     */
    public Region complete(int rx, int rz) {
        Region r = get(rx, rz);
        if (r.complete) return r;
        Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
        try {
            if (Files.exists(file)) {
                byte[] state = PregenMap.regionState(file);
                var chunkMap = level.getChunkSource().chunkMap;
                for (int i = 0; i < 1024; i++) {
                    if (state[i] != 2) continue;
                    if ((r.full[i >> 6] & (1L << (i & 63))) != 0) continue;
                    ChunkPos pos = new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5));
                    Optional<CompoundTag> tag = chunkMap.read(pos).join();
                    if (tag.isPresent()) draw(pos, tag.get());
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Map region " + rx + "," + rz + ": " + e);
        }
        synchronized (r) {
            r.complete = true;
            r.dirty = true;
            r.version++;
        }
        save(r);
        return r;
    }

    /** Which chunks of a region are fully generated (bit z*32+x), from the map or else the region file. */
    public long[] fullMask(int rx, int rz) {
        Region r = get(rx, rz);
        long[] mask;
        synchronized (r) {
            mask = r.full.clone();
            if (r.complete) return mask;
        }
        Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
        try {
            if (Files.exists(file)) {
                byte[] state = PregenMap.regionState(file);
                for (int i = 0; i < 1024; i++) if (state[i] == 2) mask[i >> 6] |= 1L << (i & 63);
            }
        } catch (IOException e) {
            // no file readable: only what the map knows
        }
        return mask;
    }

    /** The region's colours at 1 pixel per 2^level blocks, deflated. */
    public byte[] tile(Region r, int lvl) {
        int s = SIZE >> lvl, step = 1 << lvl, off = step / 2;
        byte[] out = new byte[s * s];
        synchronized (r) {
            for (int y = 0; y < s; y++) {
                int row = (y * step + off) * SIZE;
                for (int x = 0; x < s; x++) {
                    byte v = r.col[row + x * step + off];
                    if (v == 0 && step > 1) v = r.col[y * step * SIZE + x * step];
                    out[y * s + x] = v;
                }
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(out.length / 4 + 64);
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos, new Deflater(6))) {
            z.write(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    public int version(Region r) {
        synchronized (r) {
            return r.version;
        }
    }
}
