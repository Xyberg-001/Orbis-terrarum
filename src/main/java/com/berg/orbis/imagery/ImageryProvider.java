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

    /** A tile is stored as packed RGB ints, row-major, 256 x 256 (or whatever the server returns); -1 where no photo covers. */
    private record Tile(int[] rgb, int size) {}

    /** A downloaded picture, with the pixels its source has no photo for: 1 its white or black fill, 2 transparent. */
    private record Picture(int[] rgb, byte[] blank, int size) {}

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
    private final double originLat, originLon;

    public ImageryProvider(OrbisConfig cfg, Path cacheDir) {
        this.originLat = cfg.originLat;
        this.originLon = cfg.originLon;
        this.sources = new ArrayList<>();
        for (OrbisConfig.ImagerySource s : cfg.imagerySources) {
            if (s != null && s.enabled && s.urlTemplate != null && !s.urlTemplate.isBlank()) this.sources.add(s);
        }
        // The smallest box first: a state's photos before its country's, a country's before the world's. Boxes of
        // neighbours overlap (Cologne lies in the boxes of France and the Netherlands too), and each service asked
        // outside its border costs a request.
        this.sources.sort(java.util.Comparator.comparingDouble(s -> (s.north - s.south) * (s.east - s.west)));
        this.zoom = Math.max(14, Math.min(20, Math.min(cfg.imageryZoom, zoomFor(cfg.metersPerBlock, cfg.originLat))));
        this.cacheDir = cacheDir;
        int cap = Math.max(64, cfg.imageryTileCacheSize);
        this.memory = Collections.synchronizedMap(new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tile> eldest) {
                return size() > cap;
            }
        });
        this.fetchPool = Executors.newFixedThreadPool(8, r -> {
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

    /**
     * The coarsest photo zoom still giving about two pixels per block across (a pixel of at most 0.6 block): the
     * photos are sampled once per block (ground type, roof and street colours), so finer tiles are only downloads.
     * Bergen at 1:2 takes zoom 16 instead of 18 (a sixteenth of the tiles), at 1:1 zoom 17. The imageryZoom
     * setting is the finest the mod will go.
     */
    static int zoomFor(double metersPerBlock, double lat) {
        double metresPerPixelZ0 = 156543.03392 * Math.cos(Math.toRadians(Math.max(-85, Math.min(85, lat))));
        double want = 0.6 * Math.max(0.1, metersPerBlock);
        return (int) Math.ceil(Math.log(metresPerPixelZ0 / want) / Math.log(2) - 1e-9);
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
                if (c < 0) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
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

    /**
     * The tile from the first source covering it; where that source has no photo (outside its country's border,
     * drawn white, black or transparent), the next source's pixels fill in, so a tile across a border is half one
     * country's photo and half the other's (or Esri's).
     */
    private Tile load(int x, int y, double lat, double lon) {
        double n = Math.PI - 2 * Math.PI * y / (1 << zoom), s2 = Math.PI - 2 * Math.PI * (y + 1) / (1 << zoom);
        double north = Math.toDegrees(Math.atan(Math.sinh(n))), south = Math.toDegrees(Math.atan(Math.sinh(s2)));
        double west = x * 360.0 / (1 << zoom) - 180, east = (x + 1) * 360.0 / (1 << zoom) - 180;
        Picture acc = null;
        for (OrbisConfig.ImagerySource s : sources) {
            if (north < s.south || south > s.north || east < s.west || west > s.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(s.name, south, west, north, east)) continue; // outside its country (its box is wider)
            Picture p = fetch(s, x, y);
            if (p == null) continue;
            if (acc == null) {
                acc = new Picture(p.rgb.clone(), p.blank.clone(), p.size);
            } else {
                for (int py = 0; py < acc.size; py++) {
                    for (int px = 0; px < acc.size; px++) {
                        int i = py * acc.size + px;
                        if (acc.blank[i] == 0) continue;
                        int j = (py * p.size / acc.size) * p.size + px * p.size / acc.size;
                        if (p.blank[j] != 0) continue;
                        acc.rgb[i] = p.rgb[j];
                        acc.blank[i] = 0;
                    }
                }
            }
            boolean done = true;
            for (byte b : acc.blank) {
                if (b != 0) {
                    done = false;
                    break;
                }
            }
            if (done) break;
        }
        if (acc == null) return MISSING;
        // Fill no other source covered: white or black stays (it may be real snow or shadow), transparent is nothing.
        for (int i = 0; i < acc.rgb.length; i++) if (acc.blank[i] == 2) acc.rgb[i] = -1;
        return new Tile(acc.rgb, acc.size);
    }

    /** One source's picture of a tile, from the disk cache or the service; null when it has none or fails. */
    private Picture fetch(OrbisConfig.ImagerySource s, int x, int y) {
        Long disabled = sourceDisabledUntil.get(s.name);
        if (disabled != null && System.currentTimeMillis() < disabled) return null;
        String safeName = s.name.replaceAll("[^A-Za-z0-9_-]", "_");
        Path file = cacheDir.resolve(safeName + "_" + zoom + "_" + x + "_" + y + ".img");
        Path miss = cacheDir.resolve(safeName + "_" + zoom + "_" + x + "_" + y + ".missing");
        if (Files.exists(miss)) return null;
        try {
            byte[] bytes;
            if (Files.exists(file)) {
                bytes = Files.readAllBytes(file);
            } else {
                String url = s.urlTemplate.replace("{z}", String.valueOf(zoom)).replace("{x}", String.valueOf(x))
                        .replace("{y}", String.valueOf(y)).replace("{-y}", String.valueOf((1 << zoom) - 1 - y))
                        .replace("{quadkey}", quadKey(x, y, zoom)).replace("{bbox}", bbox3857(x, y, zoom));
                com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.get(url,
                        java.util.Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 12);
                if (resp.status() == 404 || resp.status() == 204) {
                    Files.write(miss, new byte[0]);
                    return null; // this source has no tile here; try the next
                }
                if (resp.status() != 200) throw new IOException("HTTP " + resp.status());
                bytes = resp.body();
                if (bytes.length < 200) {
                    Files.write(miss, new byte[0]);
                    return null;
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Picture t = decode(bytes);
            if (t == null) {
                Files.deleteIfExists(file);
                Files.write(miss, new byte[0]);
                return null;
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
        return null;
    }

    /** A tile's corners in web mercator metres (minx,miny,maxx,maxy), for WMS and ArcGIS export sources. */
    static String bbox3857(int x, int y, int z) {
        double size = 2 * Math.PI * 6378137 / (1 << z), o = Math.PI * 6378137;
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", x * size - o, o - (y + 1) * size, (x + 1) * size - o, o - y * size);
    }

    /**
     * Pure white or black, or transparency, covering 3 % of a tile or more is a service's fill outside its border,
     * not a photo; less is a speck not worth asking the next source for (transparent specks are left out).
     */
    private static final double FILL_SHARE = 0.03;

    private static Picture decode(byte[] bytes) throws IOException {
        BufferedImage img;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            img = ImageIO.read(in);
        }
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        int size = Math.min(w, h);
        int[] all = com.berg.orbis.dem.TileImages.argbPixels(img);
        int[] rgb = new int[size * size];
        byte[] blank = new byte[size * size];
        for (int y = 0; y < size; y++) System.arraycopy(all, y * w, rgb, y * size, size);
        // Blank/placeholder tiles (all one colour, or nothing but transparency) carry no information.
        int first = -2, fill = 0, clear = 0;
        boolean uniform = true;
        for (int i = 0; i < rgb.length; i++) {
            int argb = rgb[i], c = argb & 0xFFFFFF;
            rgb[i] = c;
            if ((argb >>> 24) < 128) {
                blank[i] = 2;
                clear++;
                continue;
            }
            int r = c >> 16, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (Math.min(r, Math.min(g, b)) >= 254 || Math.max(r, Math.max(g, b)) <= 1) {
                blank[i] = 1;
                fill++;
            }
            if (first == -2) first = c;
            else if (c != first) uniform = false;
        }
        if (clear == rgb.length || uniform) return null;
        if (fill + clear < FILL_SHARE * rgb.length) {
            for (int i = 0; i < blank.length; i++) {
                if (blank[i] == 2) rgb[i] = -1;
                blank[i] = 0;
            }
        }
        return new Picture(rgb, blank, size);
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

    /** Credits of the photo services covering the world's origin. */
    public String attribution() {
        StringBuilder sb = new StringBuilder();
        for (OrbisConfig.ImagerySource s : sources) {
            if (s.attribution == null || s.attribution.isBlank()) continue;
            if (originLat < s.south || originLat > s.north || originLon < s.west || originLon > s.east) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(s.attribution);
        }
        return sb.toString();
    }
}
