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
        /** OpenStreetMap's data drawn here (see {@link StreetTiles}): streets, buildings, places, house numbers. */
        STREET("Street map", StreetTiles.SERVICE, null),
        /** The street map in night colours: the moon button beside the map switches to it; the layer button skips it. */
        STREET_DARK("Street map", StreetTiles.SERVICE_DARK, null),
        SATELLITE("Satellite", "World_Imagery", "Reference/World_Boundaries_and_Places"),
        /** Heights in colour with hillshading, drawn here from the world's terrain tiles (see {@link ElevationTiles}). */
        ELEVATION("Elevation", ElevationTiles.SERVICE, "Reference/World_Boundaries_and_Places");

        public final String label, service, labels;

        Layer(String label, String service, String labels) {
            this.label = label;
            this.service = service;
            this.labels = labels;
        }

        /** The next layer for the layer button: street map (light or dark, as last chosen), satellite, elevation. */
        public Layer next() {
            return switch (this) {
                case STREET, STREET_DARK -> SATELLITE;
                case SATELLITE -> ELEVATION;
                case ELEVATION -> street(com.berg.orbis.OrbisMod.config() != null && com.berg.orbis.OrbisMod.config().streetMapDark);
            };
        }

        public boolean isStreet() {
            return this == STREET || this == STREET_DARK;
        }

        public static Layer street(boolean dark) {
            return dark ? STREET_DARK : STREET;
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
            if (StreetTiles.SERVICE.equals(t.service) || StreetTiles.SERVICE_DARK.equals(t.service)) {
                streetTile(t, StreetTiles.SERVICE_DARK.equals(t.service));
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

    /** A street map tile, drawn from OpenStreetMap's vector data. */
    private void streetTile(Tile t, boolean dark) {
        try {
            int[] argb = StreetTiles.render(disk, t.z, t.x, t.y, dark);
            t.argb = toAbgr(argb);
            t.w = 512;
            t.h = 512;
            t.state = State.DECODED;
            decoded.add(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            t.retryAt = System.currentTimeMillis() + 15_000;
            t.state = State.FAILED;
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

    /**
     * The street map's light / night switch: a small button with a moon (or a sun on the night map) at the map's
     * right edge, shown while the street map is on. Returns it so the screen can show or hide it with the layer.
     */
    public static net.minecraft.client.gui.components.Button nightButton(java.util.function.Supplier<Layer> get, java.util.function.Consumer<Layer> set,
                                                                        int x, int y) {
        net.minecraft.client.gui.components.Button b = net.minecraft.client.gui.components.Button.builder(nightLabel(get.get()), btn -> {
            boolean dark = get.get() != Layer.STREET_DARK;
            set.accept(Layer.street(dark));
            com.berg.orbis.OrbisMod.updateConfig(c -> c.streetMapDark = dark);
            btn.setMessage(nightLabel(Layer.street(dark)));
        }).bounds(x, y, 20, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                net.minecraft.network.chat.Component.translatable("orbisterrarum.map.night.tip"))).build();
        b.visible = get.get().isStreet();
        return b;
    }

    /** No text: the symbol is drawn over the button by {@link #drawNightIcon} (the font's moon came out as a "C"). */
    private static net.minecraft.network.chat.Component nightLabel(Layer l) {
        return net.minecraft.network.chat.Component.empty();
    }

    private static final int ICON = 12;
    /**
     * The night button's symbols, ICON x ICON pixels with soft edges (how much of each pixel the shape covers, from
     * 4 x 4 samples): a crescent moon tilted like the emoji, both horns tapering (a sharper cut left the lower horn
     * flat), and a sun with rays.
     */
    private static final float[] MOON = new float[ICON * ICON], SUN = new float[ICON * ICON];

    static {
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                int moon = 0, sun = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        double px = x + (i + 0.5) / 4, py = y + (j + 0.5) / 4;
                        if (Math.hypot(px - 6, py - 6) <= 5.4 && Math.hypot(px - 9.1, py - 5.0) > 4.7) moon++;
                        double r = Math.hypot(px - 6, py - 6), a = Math.atan2(py - 6, px - 6);
                        if (r <= 3.0 || r >= 4.1 && r <= 5.9 && Math.abs(Math.IEEEremainder(a, Math.PI / 4)) < 0.2) sun++;
                    }
                }
                MOON[y * ICON + x] = moon / 16f;
                SUN[y * ICON + x] = sun / 16f;
            }
        }
    }

    /**
     * The night button's symbol for the mode the map is in: a moon while the street map is dark, a sun while it is
     * light (players read the symbol as the mode they are in). Call after the screen has drawn its widgets.
     */
    public static void drawNightIcon(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.components.Button b, Layer layer) {
        if (b == null || !b.visible) return;
        boolean dark = layer == Layer.STREET_DARK;
        float[] mask = dark ? MOON : SUN;
        int rgb = dark ? 0xE8E6F5 : 0xFFC93C;
        int x0 = b.getX() + (b.getWidth() - ICON) / 2, y0 = b.getY() + (b.getHeight() - ICON) / 2;
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                float a = mask[y * ICON + x];
                if (a > 0.05f) g.fill(x0 + x, y0 + y, x0 + x + 1, y0 + y + 1, Math.round(a * 255) << 24 | rgb);
            }
        }
    }

    /**
     * A map layer's credit line in the top-right corner of the map, at half the GUI text size when the whole text
     * would take more than a third of the map's width (the street map's three credits ran across the map at GUI
     * scale 4).
     */
    public static void drawCredit(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String credit, int right, int top, int mapWidth) {
        int w = font.width(credit);
        if (w <= mapWidth / 3) {
            g.text(font, credit, right - w, top, 0xC0000000, false);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(right - w * 0.5f, top);
        g.pose().scale(0.5f, 0.5f);
        g.text(font, credit, 0, 0, 0xC0000000, false);
        g.pose().popMatrix();
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
