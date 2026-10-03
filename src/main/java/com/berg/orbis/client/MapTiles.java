package com.berg.orbis.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.berg.orbis.mc.McClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web map tiles (Esri street map, satellite photos with place names, and the elevation layer drawn from the terrain
 * tiles; Esri's topographic map was dropped for it on 3 Oct 2026) for the in-game area
 * preview: downloaded by a few background threads, newest requests first, decoded off the render thread and
 * uploaded as textures a few per frame. Tiles are kept on disk like a browser keeps them (TileDiskCache, up to
 * 300 MB) and the latest few MB in memory, so reopening the preview is instant; textures are freed on close.
 */
public final class MapTiles {
    public enum Layer {
        STREET("Street map", "World_Street_Map", null),
        SATELLITE("Satellite", "World_Imagery", "Reference/World_Boundaries_and_Places"),
        /** Heights in colour with hillshading, drawn here from the world's terrain tiles (see {@link ElevationTiles}). */
        ELEVATION("Elevation", ElevationTiles.SERVICE, "Reference/World_Boundaries_and_Places");

        public final String label, service, labels;

        Layer(String label, String service, String labels) {
            this.label = label;
            this.service = service;
            this.labels = labels;
        }

        public Layer next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final int MAX_ZOOM = 19;
    private static final int THREADS = 6, MAX_TEXTURES = 400, UPLOADS_PER_FRAME = 6;
    private static final String USER_AGENT = "OrbisTerrarum/1.0 (Minecraft mod; area preview)";

    /** Downloaded tile bytes across screen openings, least recently used first. */
    private static final Map<String, byte[]> BYTES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 900;
        }
    };
    private static final AtomicInteger IDS = new AtomicInteger();

    private enum State { QUEUED, DECODED, READY, FAILED }

    private static final class Tile {
        final String service;
        final int z, x, y;
        volatile State state = State.QUEUED;
        volatile int[] argb;
        volatile int w, h;
        volatile long retryAt;
        volatile long wanted;
        Identifier id;
        long lastUsed;

        Tile(String service, int z, int x, int y) {
            this.service = service;
            this.z = z;
            this.x = x;
            this.y = y;
        }
    }

    /** Tile textures sample linearly with clamped edges, so a scaled tile looks like a map, not pixel art. */
    private static final class TileTexture extends DynamicTexture {
        TileTexture(String name, NativeImage image) {
            super(() -> name, image);
            this.sampler = McClient.linearClampSampler();
        }
    }

    private final Map<String, Tile> tiles = new ConcurrentHashMap<>();
    /** Heights of the elevation tiles on the GPU (ElevationTiles.GRID a side, metres), for the cursor readout. */
    private final Map<String, short[]> heights = new ConcurrentHashMap<>();
    private final TileDiskCache disk = new TileDiskCache(com.berg.orbis.OrbisMod.dataDir().resolve("map-tile-cache"), USER_AGENT);
    private final LinkedBlockingDeque<Tile> queue = new LinkedBlockingDeque<>();
    private final ConcurrentLinkedQueue<Tile> decoded = new ConcurrentLinkedQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean closed;
    private volatile long frame;
    private int ready;

    public MapTiles() {
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(this::work, "Orbis map tiles " + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    private static String key(String service, int z, int x, int y) {
        return service + "/" + z + "/" + x + "/" + y;
    }

    /** The texture of a tile if it is on the GPU, else null; asks for it when {@code request} is set. */
    public Identifier get(String service, int z, int x, int y, boolean request) {
        String k = key(service, z, x, y);
        Tile t = tiles.get(k);
        if (t == null) {
            if (!request) return null;
            t = new Tile(service, z, x, y);
            tiles.put(k, t);
            t.wanted = frame;
            queue.offerFirst(t);
            return null;
        }
        t.wanted = frame;
        if (t.state == State.READY) {
            t.lastUsed = frame;
            return t.id;
        }
        if (request && t.state == State.FAILED && System.currentTimeMillis() >= t.retryAt && t.retryAt > 0) {
            t.state = State.QUEUED;
            queue.offerFirst(t);
        }
        return null;
    }

    /** Whether the tile could not be had (no such tile at that zoom, or the download failed). */
    public boolean failed(String service, int z, int x, int y) {
        Tile t = tiles.get(key(service, z, x, y));
        return t != null && t.state == State.FAILED;
    }

    /** Uploads a few decoded tiles and frees the least recently drawn ones; call once per frame on the render thread. */
    public void pump(Minecraft mc) {
        frame++;
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Tile t = decoded.poll();
            if (t == null) break;
            int[] px = t.argb;
            if (px == null || closed) continue;
            NativeImage img = image(t.w, t.h, px);
            t.id = Identifier.fromNamespaceAndPath("orbisterrarum", "preview/tile_" + IDS.incrementAndGet());
            mc.getTextureManager().register(t.id, new TileTexture("orbis map tile " + key(t.service, t.z, t.x, t.y), img));
            t.argb = null;
            t.lastUsed = frame;
            t.state = State.READY;
            ready++;
        }
        if (ready > MAX_TEXTURES) {
            List<Tile> old = new ArrayList<>();
            for (Tile t : tiles.values()) if (t.state == State.READY && t.lastUsed < frame - 2) old.add(t);
            old.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
            for (int i = 0; i < Math.min(old.size(), ready - MAX_TEXTURES + 32); i++) {
                Tile t = old.get(i);
                mc.getTextureManager().release(t.id);
                tiles.remove(key(t.service, t.z, t.x, t.y));
                heights.remove(key(t.service, t.z, t.x, t.y));
                ready--;
            }
        }
    }

    private void work() {
        while (!closed) {
            Tile t;
            try {
                t = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            if (t.wanted < frame - 3) {
                // Scrolled or zoomed away before its turn: forget it, it is asked for again if it comes back.
                tiles.remove(key(t.service, t.z, t.x, t.y), t);
                continue;
            }
            String k = key(t.service, t.z, t.x, t.y);
            if (ElevationTiles.SERVICE.equals(t.service)) {
                elevationTile(t, k);
                continue;
            }
            try {
                byte[] bytes;
                synchronized (BYTES) {
                    bytes = BYTES.get(k);
                }
                if (bytes == null) {
                    bytes = disk.fetch(t.service, t.z, t.x, t.y);
                    if (bytes == null) {
                        t.retryAt = 0; // no such tile at this zoom: the parent is drawn instead
                        t.state = State.FAILED;
                        continue;
                    }
                    synchronized (BYTES) {
                        BYTES.put(k, bytes);
                    }
                }
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) {
                    t.state = State.FAILED;
                    continue;
                }
                int w = img.getWidth(), h = img.getHeight();
                t.argb = toAbgr(com.berg.orbis.dem.TileImages.argbPixels(img));
                t.w = w;
                t.h = h;
                t.state = State.DECODED;
                decoded.add(t);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                t.retryAt = System.currentTimeMillis() + 15_000;
                t.state = State.FAILED;
            }
        }
    }

    /** An elevation tile: the terrain tile coloured (and its heights kept for the readout). */
    private void elevationTile(Tile t, String k) {
        try {
            byte[] bytes = ElevationTiles.fetch(t.z, t.x, t.y);
            if (bytes == null) {
                t.retryAt = 0; // no terrain at this zoom: the parent is drawn instead
                t.state = State.FAILED;
                return;
            }
            ElevationTiles.Rendered r = ElevationTiles.render(bytes, t.z, t.x, t.y);
            heights.put(k, r.grid());
            t.argb = toAbgr(r.argb());
            t.w = r.w();
            t.h = r.h();
            t.state = State.DECODED;
            decoded.add(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            t.retryAt = System.currentTimeMillis() + 15_000;
            t.state = State.FAILED;
        }
    }

    /**
     * The height in metres at a place from the elevation tiles loaded (the finest of zoom {@code z} and the eight
     * above it), or NaN while none covers it.
     */
    public double elevationAt(double lat, double lon, int z) {
        for (int zz = Math.min(MAX_ZOOM, z); zz >= Math.max(0, z - 8); zz--) {
            double[] f = com.berg.orbis.dem.DemTileProvider.latLonToTileFraction(lat, lon, zz);
            int tx = (int) Math.floor(f[0]), ty = (int) Math.floor(f[1]);
            short[] grid = heights.get(key(ElevationTiles.SERVICE, zz, Math.floorMod(tx, 1 << zz), ty));
            if (grid == null) continue;
            int gx = Math.min(ElevationTiles.GRID - 1, (int) ((f[0] - tx) * ElevationTiles.GRID));
            int gy = Math.min(ElevationTiles.GRID - 1, (int) ((f[1] - ty) * ElevationTiles.GRID));
            return grid[gy * ElevationTiles.GRID + gx];
        }
        return Double.NaN;
    }

    /** ARGB (Java images) to the byte order NativeImage keeps in memory (R, G, B, A = ABGR as a little-endian int), in place. */
    public static int[] toAbgr(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            argb[i] = (c & 0xFF00FF00) | ((c >>> 16) & 0xFF) | ((c & 0xFF) << 16);
        }
        return argb;
    }

    /** A NativeImage filled from ABGR pixels in one memory copy (render thread). */
    public static NativeImage image(int w, int h, int[] abgr) {
        NativeImage img = new NativeImage(w, h, false);
        org.lwjgl.system.MemoryUtil.memIntBuffer(img.getPointer(), w * h).put(abgr, 0, w * h);
        return img;
    }

    /** How many tiles are still on their way (for the "loading map" hint). */
    public int loading() {
        return queue.size() + decoded.size();
    }

    /** Stops the downloads and frees every tile texture; call when the screen closes (render thread). */
    public void close(Minecraft mc) {
        closed = true;
        for (Thread t : workers) t.interrupt();
        disk.trimLater();
        queue.clear();
        decoded.clear();
        for (Tile t : tiles.values()) {
            if (t.state == State.READY && t.id != null) mc.getTextureManager().release(t.id);
        }
        tiles.clear();
        heights.clear();
        ready = 0;
    }
}
