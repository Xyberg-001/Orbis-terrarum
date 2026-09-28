package com.berg.orbis.imagery;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.dem.DemTileProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aerial / satellite imagery in slippy-map tiles, used to read the REAL
 * colour of the world at any block column: roof colours for buildings
 * without an OSM colour tag, and ground cover (lawn, tree canopy, asphalt,
 * concrete, soil, sand, rock, snow) wherever OSM has no polygon.
 *
 * Several sources can be configured with a coverage box; the first source
 * that covers the point and answers wins, so sharp national orthophotos
 * (Norway, USA) take precedence over the global Esri layer. Tiles are cached
 * on disk and in a bounded LRU. A source that keeps failing is skipped for a
 * while instead of slowing every region down.
 */
public final class ImageryProvider {

    /** A tile is stored as packed RGB ints, row-major, 256 x 256 (or whatever the server returns). */
    private record Tile(int[] rgb, int size) {}

    private static final Tile MISSING = new Tile(new int[0], 0);

    private final List<OrbisConfig.ImagerySource> sources;
    private final int zoom;
    private final Path cacheDir;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Tile> memory;
    private final ConcurrentHashMap<String, CompletableFuture<Tile>> loading = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sourceDisabledUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> sourceFailures = new ConcurrentHashMap<>();
    private final ExecutorService fetchPool;

    public ImageryProvider(OrbisConfig cfg, Path cacheDir) {
        this.sources = new ArrayList<>();
        for (OrbisConfig.ImagerySource s : cfg.imagerySources) {
            if (s != null && s.enabled && s.urlTemplate != null && !s.urlTemplate.isBlank()) this.sources.add(s);
        }
        this.zoom = Math.max(14, Math.min(20, cfg.imageryZoom));
        this.cacheDir = cacheDir;
        int cap = Math.max(64, cfg.imageryTileCacheSize);
        this.memory = Collections.synchronizedMap(new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tile> eldest) {
                return size() > cap;
            }
        });
        this.fetchPool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "Orbis-imagery");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create imagery cache dir: " + cacheDir, e);
        }
    }

    public boolean isEnabled() {
        return !sources.isEmpty();
    }

    public int zoom() {
        return zoom;
    }

    /** Starts downloading every tile covering the lat/lon box, in parallel; returns when all are done. */
    public void prefetch(double south, double west, double north, double east) {
        if (sources.isEmpty()) return;
        double[] a = DemTileProvider.latLonToTileFraction(north, west, zoom);
        double[] b = DemTileProvider.latLonToTileFraction(south, east, zoom);
        int x0 = (int) Math.floor(a[0]), x1 = (int) Math.floor(b[0]);
        int y0 = (int) Math.floor(a[1]), y1 = (int) Math.floor(b[1]);
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) return; // absurd box, skip
        List<CompletableFuture<Tile>> futures = new ArrayList<>();
        double midLat = (south + north) / 2, midLon = (west + east) / 2;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) futures.add(tileFuture(x, y, midLat, midLon));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Average colour of a small window (about 1 m) around the point, packed
     * 0xRRGGBB, or -1 when no imagery is available there.
     */
    public int colourAt(double lat, double lon) {
        if (sources.isEmpty()) return -1;
        double[] f = DemTileProvider.latLonToTileFraction(lat, lon, zoom);
        int tx = (int) Math.floor(f[0]), ty = (int) Math.floor(f[1]);
        Tile tile = tile(tx, ty, lat, lon);
        if (tile == null || tile == MISSING || tile.size == 0) return -1;
        int size = tile.size;
        int px = (int) ((f[0] - tx) * size), py = (int) ((f[1] - ty) * size);
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = px + dx, y = py + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int c = tile.rgb[y * size + x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    // ---- ground classes from a segmentation model (tools/classify_ground.py) ----

    private final Map<String, byte[]> classTiles = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 512;
        }
    });
    private static final byte[] NO_CLASSES = new byte[0];
    private volatile Boolean anyClassTiles;

    /** Ground class code at the point from the model's class tiles (ground-classes/<z>_<x>_<y>.png), or -1 when there is none. */
    public int classAt(double lat, double lon) {
        if (sources.isEmpty()) return -1;
        Path dir = cacheDir.resolveSibling("ground-classes");
        Boolean any = anyClassTiles;
        if (any == null) {
            any = Files.isDirectory(dir);
            anyClassTiles = any;
        }
        if (!any) return -1;
        double[] f = DemTileProvider.latLonToTileFraction(lat, lon, zoom);
        int tx = (int) Math.floor(f[0]), ty = (int) Math.floor(f[1]);
        String key = zoom + "_" + tx + "_" + ty;
        byte[] t = classTiles.get(key);
        if (t == null) {
            t = NO_CLASSES;
            Path file = dir.resolve(key + ".png");
            if (Files.exists(file)) {
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file.toFile());
                    if (img != null && img.getWidth() == img.getHeight()) {
                        int size = img.getWidth();
                        t = new byte[size * size + 1];
                        t[0] = (byte) (size >> 4); // side length / 16 in the first byte (256 -> 16, 512 -> 32)
                        java.awt.image.Raster ras = img.getRaster();
                        for (int py = 0; py < size; py++) {
                            for (int px = 0; px < size; px++) t[1 + py * size + px] = (byte) ras.getSample(px, py, 0);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    t = NO_CLASSES;
                }
            }
            classTiles.put(key, t);
        }
        if (t.length == 0) return -1;
        int size = (t[0] & 0xFF) << 4;
        int px = (int) ((f[0] - tx) * size), py = (int) ((f[1] - ty) * size);
        if (px < 0 || py < 0 || px >= size || py >= size) return -1;
        return t[1 + py * size + px] & 0xFF;
    }

    private Tile tile(int x, int y, double lat, double lon) {
        try {
            return tileFuture(x, y, lat, lon).join();
        } catch (RuntimeException e) {
            return MISSING;
        }
    }

    private CompletableFuture<Tile> tileFuture(int x, int y, double lat, double lon) {
        String key = zoom + "/" + x + "/" + y;
        Tile cached = memory.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return loading.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Tile t = load(x, y, lat, lon);
            memory.put(key, t);
            loading.remove(key);
            return t;
        }, fetchPool));
    }

    private Tile load(int x, int y, double lat, double lon) {
        for (OrbisConfig.ImagerySource s : sources) {
            if (lat < s.south || lat > s.north || lon < s.west || lon > s.east) continue;
            Long disabled = sourceDisabledUntil.get(s.name);
            if (disabled != null && System.currentTimeMillis() < disabled) continue;
            String safeName = s.name.replaceAll("[^A-Za-z0-9_-]", "_");
            Path file = cacheDir.resolve(safeName + "_" + zoom + "_" + x + "_" + y + ".img");
            Path miss = cacheDir.resolve(safeName + "_" + zoom + "_" + x + "_" + y + ".missing");
            if (Files.exists(miss)) continue;
            try {
                byte[] bytes;
                if (Files.exists(file)) {
                    bytes = Files.readAllBytes(file);
                } else {
                    String url = s.urlTemplate.replace("{z}", String.valueOf(zoom)).replace("{x}", String.valueOf(x))
                            .replace("{y}", String.valueOf(y)).replace("{-y}", String.valueOf((1 << zoom) - 1 - y))
                            .replace("{quadkey}", quadKey(x, y, zoom));
                    com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.get(url,
                            java.util.Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 12);
                    if (resp.status() == 404 || resp.status() == 204) {
                        Files.write(miss, new byte[0]);
                        continue; // this source has no tile here; try the next
                    }
                    if (resp.status() != 200) throw new IOException("HTTP " + resp.status());
                    bytes = resp.body();
                    if (bytes.length < 200) {
                        Files.write(miss, new byte[0]);
                        continue;
                    }
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Tile t = decode(bytes);
                if (t == null) {
                    Files.deleteIfExists(file);
                    Files.write(miss, new byte[0]);
                    continue;
                }
                sourceFailures.remove(s.name);
                return t;
            } catch (IOException | InterruptedException | RuntimeException e) {
                int failures = sourceFailures.merge(s.name, 1, Integer::sum);
                if (failures >= 3) {
                    sourceDisabledUntil.put(s.name, System.currentTimeMillis() + 5 * 60_000L);
                    sourceFailures.remove(s.name);
                    String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    System.err.println("[orbis] Imagery source '" + s.name + "' failing (" + why + "); skipping it for 5 minutes");
                }
            }
        }
        return MISSING;
    }

    private static Tile decode(byte[] bytes) throws IOException {
        BufferedImage img;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            img = ImageIO.read(in);
        }
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        int size = Math.min(w, h);
        int[] all = com.berg.orbis.dem.TileImages.rgbPixels(img);
        int[] rgb = new int[size * size];
        for (int y = 0; y < size; y++) System.arraycopy(all, y * w, rgb, y * size, size);
        // Blank/placeholder tiles (all one colour) carry no information.
        int first = rgb[0] & 0xFFFFFF;
        boolean uniform = true;
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i] & 0xFFFFFF;
            rgb[i] = c;
            if (c != first) uniform = false;
        }
        return uniform ? null : new Tile(rgb, size);
    }

    private static String quadKey(int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        for (int i = z; i > 0; i--) {
            int digit = 0, mask = 1 << (i - 1);
            if ((x & mask) != 0) digit++;
            if ((y & mask) != 0) digit += 2;
            sb.append(digit);
        }
        return sb.toString();
    }

    public String attribution() {
        StringBuilder sb = new StringBuilder();
        for (OrbisConfig.ImagerySource s : sources) {
            if (s.attribution == null || s.attribution.isBlank()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(s.attribution);
        }
        return sb.toString();
    }
}
