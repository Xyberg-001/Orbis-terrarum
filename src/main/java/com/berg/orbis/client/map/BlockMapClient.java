package com.berg.orbis.client.map;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.client.MapTiles;
import com.berg.orbis.net.MapTilePayloads;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.InflaterInputStream;

/**
 * The game's side of the world map's Minecraft layer: asks the server for the regions on screen, keeps them as
 * textures (the block colours at the zoom's level of detail, and a grey mask over chunks not generated yet), and
 * caches them on disk per world ({@code config/orbisterrarum/mc-map-cache/}) so an area is downloaded once.
 * Render thread only.
 */
public final class BlockMapClient {
    private static final int MAX_TILES = 400, MAX_PENDING = 48;
    private static final long PENDING_TIMEOUT_MS = 30_000;
    private static final AtomicInteger IDS = new AtomicInteger();

    private static final class Tile {
        Identifier id;
        int version;
        long used;
    }

    private static final class Mask {
        long[] bits;
        Identifier grey;
        long used;
    }

    private static final Map<Long, Tile> TILES = new HashMap<>();
    private static final Map<Long, Mask> MASKS = new HashMap<>();
    private static final Map<Long, Long> PENDING = new HashMap<>();
    private static final List<long[]> WANT_BLOCKS = new ArrayList<>(), WANT_MASK = new ArrayList<>();
    private static String world;
    private static long frame;

    private BlockMapClient() {}

    private static long rk(int rx, int rz) {
        return ((long) rx << 32) ^ (rz & 0xffffffffL);
    }

    private static long tk(int rx, int rz, int level) {
        return (rk(rx, rz) << 3) ^ level;
    }

    public static boolean available() {
        return com.berg.orbis.client.OrbisClient.worldInfo != null && ClientPlayNetworking.canSend(MapTilePayloads.Request.TYPE);
    }

    /** Call once per frame before asking for tiles: notices a change of world. */
    public static void beginFrame(Minecraft mc) {
        frame++;
        String key = MapMarks.worldKey(mc);
        if (!key.equals(world)) {
            clear(mc);
            world = key;
        }
    }

    /** The colours of a region at a level (or, while it loads, at any level already here), or null. */
    public static Identifier tile(int rx, int rz, int level) {
        Tile t = TILES.get(tk(rx, rz, level));
        if (t == null) {
            t = fromDisk(rx, rz, level);
            if (t != null) TILES.put(tk(rx, rz, level), t);
        }
        if (t == null || t.version < 0) want(WANT_BLOCKS, rx, rz, level, t == null ? 0 : Math.max(0, t.version));
        else if (t.used == 0) want(WANT_BLOCKS, rx, rz, level, t.version); // loaded from disk: check it is current, once
        if (t != null && t.id != null) {
            t.used = frame;
            return t.id;
        }
        for (int d = 1; d <= 4; d++) {
            for (int l : new int[]{level - d, level + d}) {
                if (l < 0 || l > 4) continue;
                Tile o = TILES.get(tk(rx, rz, l));
                if (o != null && o.id != null) {
                    o.used = frame;
                    return o.id;
                }
            }
        }
        return null;
    }

    public static int tileSize(int level) {
        return 512 >> level;
    }

    /** Which chunks of a region are generated (bit z*32+x), or null while unknown. */
    public static long[] mask(int rx, int rz) {
        Mask m = MASKS.get(rk(rx, rz));
        if (m == null) {
            want(WANT_MASK, rx, rz, 0, 0);
            return null;
        }
        m.used = frame;
        return m.bits;
    }

    /** The grey veil over a region's ungenerated chunks (32 x 32 pixels), or null while unknown. */
    public static Identifier grey(int rx, int rz) {
        Mask m = MASKS.get(rk(rx, rz));
        if (m == null) {
            want(WANT_MASK, rx, rz, 0, 0);
            return null;
        }
        m.used = frame;
        return m.grey;
    }

    private static void want(List<long[]> list, int rx, int rz, int level, int known) {
        long key = list == WANT_BLOCKS ? tk(rx, rz, level) : -1 - rk(rx, rz);
        Long since = PENDING.get(key);
        if (since != null && System.currentTimeMillis() - since < PENDING_TIMEOUT_MS) return;
        for (long[] w : list) if (w[0] == rx && w[1] == rz && w[2] == level) return;
        list.add(new long[]{rx, rz, level, known});
    }

    /** Sends what this frame asked for, nearest to the centre first (the caller orders its asks), a few at a time. */
    public static void send() {
        if (!available()) {
            WANT_BLOCKS.clear();
            WANT_MASK.clear();
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(t -> now - t > PENDING_TIMEOUT_MS);
        sendList(WANT_BLOCKS, true, now);
        sendList(WANT_MASK, false, now);
        WANT_BLOCKS.clear();
        WANT_MASK.clear();
    }

    private static void sendList(List<long[]> list, boolean blocks, long now) {
        Map<Integer, List<long[]>> byLevel = new HashMap<>();
        for (long[] w : list) {
            if (PENDING.size() >= MAX_PENDING) break;
            long key = blocks ? tk((int) w[0], (int) w[1], (int) w[2]) : -1 - rk((int) w[0], (int) w[1]);
            PENDING.put(key, now);
            byLevel.computeIfAbsent((int) w[2], k -> new ArrayList<>()).add(w);
        }
        for (var e : byLevel.entrySet()) {
            List<long[]> ws = e.getValue();
            for (int from = 0; from < ws.size(); from += MapTilePayloads.Request.MAX) {
                int n = Math.min(MapTilePayloads.Request.MAX, ws.size() - from);
                int[] rx = new int[n], rz = new int[n], known = new int[n];
                for (int i = 0; i < n; i++) {
                    long[] w = ws.get(from + i);
                    rx[i] = (int) w[0];
                    rz[i] = (int) w[1];
                    known[i] = (int) w[3];
                }
                ClientPlayNetworking.send(new MapTilePayloads.Request(e.getKey(), blocks, rx, rz, known));
            }
        }
    }

    /** An answer from the server (render thread). */
    public static void receive(Minecraft mc, MapTilePayloads.Data d) {
        PENDING.remove(-1 - rk(d.rx(), d.rz()));
        setMask(mc, d.rx(), d.rz(), d.mask());
        if (d.version() == 0 && d.colours().length == 0) return; // a mask-only answer
        long key = tk(d.rx(), d.rz(), d.level());
        PENDING.remove(key);
        Tile t = TILES.computeIfAbsent(key, k -> new Tile());
        if (d.sameColours() && t.id != null) {
            t.version = d.version();
            t.used = Math.max(t.used, 1);
            return;
        }
        if (d.colours().length == 0) return;
        byte[] packed = inflate(d.colours(), tileSize(d.level()));
        if (packed == null) return;
        upload(mc, t, packed, tileSize(d.level()));
        t.version = d.version();
        t.used = Math.max(t.used, 1);
        toDisk(d, packed);
        evict(mc);
    }

    private static void setMask(Minecraft mc, int rx, int rz, long[] bits) {
        Mask m = MASKS.computeIfAbsent(rk(rx, rz), k -> new Mask());
        if (m.bits != null && java.util.Arrays.equals(m.bits, bits)) return;
        m.bits = bits.clone();
        int[] px = new int[32 * 32];
        for (int i = 0; i < 1024; i++) {
            boolean generated = (bits[i >> 6] & (1L << (i & 63))) != 0;
            px[i] = generated ? 0 : 0xB4202328;
        }
        NativeImage img = MapTiles.image(32, 32, MapTiles.toAbgr(px));
        if (m.grey == null) m.grey = Identifier.fromNamespaceAndPath("orbisterrarum", "map/grey_" + IDS.incrementAndGet());
        mc.getTextureManager().register(m.grey, new DynamicTexture(() -> "orbis map grey", img));
    }

    private static void upload(Minecraft mc, Tile t, byte[] packed, int size) {
        int[] px = new int[size * size];
        for (int i = 0; i < px.length; i++) {
            int id = packed[i] & 0xFF;
            px[i] = id < 4 ? 0 : MapColor.getColorFromPackedId(id) | 0xFF000000;
        }
        NativeImage img = MapTiles.image(size, size, MapTiles.toAbgr(px));
        if (t.id == null) t.id = Identifier.fromNamespaceAndPath("orbisterrarum", "map/blocks_" + IDS.incrementAndGet());
        mc.getTextureManager().register(t.id, new DynamicTexture(() -> "orbis map blocks", img));
    }

    private static byte[] inflate(byte[] deflated, int size) {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated))) {
            byte[] out = in.readNBytes(size * size);
            return out.length == size * size ? out : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void evict(Minecraft mc) {
        if (TILES.size() <= MAX_TILES) return;
        List<Map.Entry<Long, Tile>> all = new ArrayList<>(TILES.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue().used, b.getValue().used));
        for (int i = 0; i < all.size() - MAX_TILES + 50; i++) {
            Tile t = all.get(i).getValue();
            if (t.id != null) mc.getTextureManager().release(t.id);
            TILES.remove(all.get(i).getKey());
        }
    }

    /** Frees every texture (world left, or another world opened). */
    public static void clear(Minecraft mc) {
        for (Tile t : TILES.values()) if (t.id != null) mc.getTextureManager().release(t.id);
        for (Mask m : MASKS.values()) if (m.grey != null) mc.getTextureManager().release(m.grey);
        TILES.clear();
        MASKS.clear();
        PENDING.clear();
        WANT_BLOCKS.clear();
        WANT_MASK.clear();
    }

    // ------------------------------------------------------------------ disk cache

    private static Path file(int rx, int rz, int level) {
        String w = world == null ? "unknown" : world.replaceAll("[^A-Za-z0-9._-]", "_");
        return OrbisMod.configDir().resolve("mc-map-cache").resolve(w).resolve("L" + level).resolve("r." + rx + "." + rz + ".bin");
    }

    private static Tile fromDisk(int rx, int rz, int level) {
        Path f = file(rx, rz, level);
        if (!Files.isRegularFile(f)) return null;
        try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(raw)) {
            int version = in.readInt();
            byte[] packed;
            try (InputStream z = new InflaterInputStream(in)) {
                packed = z.readNBytes(tileSize(level) * tileSize(level));
            }
            if (packed.length != tileSize(level) * tileSize(level)) return null;
            Tile t = new Tile();
            upload(Minecraft.getInstance(), t, packed, tileSize(level));
            t.version = version;
            return t;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void toDisk(MapTilePayloads.Data d, byte[] packed) {
        Path f = file(d.rx(), d.rz(), d.level());
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream raw = Files.newOutputStream(f); DataOutputStream out = new DataOutputStream(raw)) {
                out.writeInt(d.version());
                out.write(d.colours()); // already deflated by the server
            }
        } catch (IOException e) {
            // only costs a download next time
        }
    }
}
