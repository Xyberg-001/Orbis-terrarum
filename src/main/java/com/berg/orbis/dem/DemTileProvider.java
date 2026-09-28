package com.berg.orbis.dem;

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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches elevation tiles in "Terrarium" PNG encoding (RGB channels encode
 * elevation in metres: value = (R * 256 + G + B / 256) - 32768), caches them
 * on disk so the network is hit once per tile, and keeps a bounded LRU of
 * decoded tiles in memory.
 *
 * Default source: AWS Open Data "elevation-tiles-prod" (Mapzen/Tilezen
 * terrain tiles: SRTM/GMTED/ETOPO composite, with bathymetry -- so the sea
 * floor is real too). Any Terrarium-format server works via the config URL.
 */
public class DemTileProvider implements TileSource {

    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    /**
     * All tile loads go through this small pool. The spawn search and the biome source can ask for hundreds of
     * tiles in the same instant (the world's spawn selection samples biomes over a 20 km circle); on the common
     * pool that became a hundred simultaneous requests on one HTTP/2 connection ("too many concurrent streams")
     * and 35-second timeouts. Eight at a time keeps the server happy and the disk cache fills just as fast.
     */
    private static final java.util.concurrent.ExecutorService DOWNLOADS = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "Orbis-DEM");
        t.setDaemon(true);
        return t;
    });

    private final Path cacheDir;
    private final String urlTemplate;
    private final HttpClient client;
    private final Map<String, float[][]> memoryCache;
    private final ConcurrentHashMap<String, CompletableFuture<float[][]>> loading = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> recentFailures = new ConcurrentHashMap<>();
    /** Tiles asked for that could not be delivered (download failed or backing off): their terrain fell back to sea level. */
    private final java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();

    /** How many tile requests have failed so far; a change during some work means it used sea-level fallbacks. */
    public long failures() {
        return failures.get();
    }

    /** The original source: its cache stays in dem-cache itself so old worlds keep their tiles; any other source gets its own folder. */
    public static final String LEGACY_AWS_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";
    public static final String MAPTERHORN_URL = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp";
    public static final String SEASCAPE_URL = "https://tiles.openwaters.io/seascape/{z}/{x}/{y}.webp";

    private final boolean legacyLayout;
    private final long pixelBudget;
    private long cachedPixels;

    public DemTileProvider(Path cacheDir, String urlTemplate, int memoryTiles) {
        this.urlTemplate = urlTemplate;
        this.legacyLayout = LEGACY_AWS_URL.equals(urlTemplate);
        this.cacheDir = legacyLayout ? cacheDir : cacheDir.resolve(sourceSlug(urlTemplate));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // The budget is counted in 256x256 tiles: a 512-pixel Mapterhorn tile costs four of them.
        this.pixelBudget = Math.max(32, memoryTiles) * 65536L;
        this.memoryCache = new LinkedHashMap<>(256, 0.75f, true);
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create DEM cache dir: " + cacheDir, e);
        }
    }

    /** A short folder name for a tile source: its host, e.g. "tiles.mapterhorn.com". */
    static String sourceSlug(String urlTemplate) {
        String s = urlTemplate.replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        s = s.replaceAll("[^A-Za-z0-9.-]", "_");
        return s.isEmpty() ? "tiles" : s;
    }

    public String describeSource() {
        if (urlTemplate.contains("mapterhorn")) {
            return "Mapterhorn (tiles.mapterhorn.com): national lidar terrain models where they exist (Norway 1 m, Switzerland, Austria, "
                    + "Germany, Japan, the USA, ...), Copernicus GLO-30 elsewhere; sources and licences: https://mapterhorn.com/attribution";
        }
        if (urlTemplate.contains("openwaters")) {
            return "Open Waters Seascape (tiles.openwaters.io): GEBCO 2026 with regional surveys (EMODnet, NOAA, ...) where they exist, "
                    + "CC BY 4.0, (c) Open Waters https://openwaters.io/charts/seascape#license";
        }
        if (legacyLayout) return "AWS Open Data terrain tiles (Mapzen/Tilezen: SRTM, GMTED, ETOPO1)";
        return urlTemplate;
    }

    private float[][] remember(String key, float[][] tile) {
        synchronized (memoryCache) {
            float[][] previous = memoryCache.put(key, tile);
            if (previous != null) cachedPixels -= (long) previous.length * previous.length;
            cachedPixels += (long) tile.length * tile.length;
            var it = memoryCache.entrySet().iterator();
            while (cachedPixels > pixelBudget && memoryCache.size() > 1 && it.hasNext()) {
                Map.Entry<String, float[][]> eldest = it.next();
                if (eldest.getKey().equals(key)) continue;
                cachedPixels -= (long) eldest.getValue().length * eldest.getValue().length;
                it.remove();
            }
        }
        return tile;
    }

    @Override
    public float[][] getTile(int zoom, int x, int y) {
        String key = zoom + "/" + x + "/" + y;
        float[][] cached;
        synchronized (memoryCache) {
            cached = memoryCache.get(key);
        }
        if (cached != null) return cached;

        Instant lastFailure = recentFailures.get(key);
        if (lastFailure != null && Duration.between(lastFailure, Instant.now()).compareTo(FAILURE_BACKOFF) < 0) {
            failures.incrementAndGet();
            throw new RuntimeException("DEM tile " + key + " recently failed; backing off");
        }
        // One download per tile even when 256 columns ask for it at once.
        CompletableFuture<float[][]> f = loading.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(zoom, x, y, k), DOWNLOADS));
        try {
            float[][] tile = f.join();
            remember(key, tile);
            recentFailures.remove(key);
            return tile;
        } catch (RuntimeException e) {
            recentFailures.put(key, Instant.now());
            failures.incrementAndGet();
            System.err.println("[orbis] DEM tile " + key + " unavailable: " + e.getMessage() + " -- terrain on it falls back to sea level until it can be fetched");
            throw e;
        } finally {
            loading.remove(key, f);
        }
    }

    /** The server has no tile at this zoom here (Mapterhorn stops at the zoom its data for an area justifies). */
    private static final class TileMissing extends IOException {
        TileMissing(String msg) {
            super(msg);
        }
    }

    private float[][] load(int zoom, int x, int y, String key) {
        try {
            Path cached = cacheDir.resolve(zoom + "_" + x + "_" + y + (legacyLayout ? ".png" : ".tile"));
            Path missing = cacheDir.resolve(zoom + "_" + x + "_" + y + ".missing");
            byte[] pngBytes;
            if (Files.exists(cached)) {
                pngBytes = Files.readAllBytes(cached);
            } else if (Files.exists(missing)) {
                return fromParent(zoom, x, y);
            } else {
                try {
                    pngBytes = downloadTile(zoom, x, y);
                } catch (TileMissing e) {
                    if (zoom <= 0) throw e;
                    Files.write(missing, new byte[0]);
                    return fromParent(zoom, x, y);
                }
                Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp");
                Files.write(tmp, pngBytes);
                Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            float[][] tile = decodeTerrarium(pngBytes);
            if (zoom > 0 && !plausible(tile)) {
                // A mis-decoded image (TwelveMonkeys 3.12 scrambled one lossless Mapterhorn WebP into +-32768 m, which
                // built a 2 km stone pillar): trust the coarser tile over heights no place on Earth has.
                System.err.println("[orbis] DEM tile " + key + " decoded to impossible heights; using the tile one zoom up there");
                return fromParent(zoom, x, y);
            }
            return tile;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch DEM tile " + key + ": " + e.getMessage(), e);
        }
    }

    /**
     * A tile the source does not have at this zoom, built from the parent tile one zoom up: its quadrant is
     * upsampled two times with bilinear interpolation (the bicubic sampler on top smooths the rest). Mapterhorn
     * only serves the zooms an area's data justifies (about 12 where only Copernicus GLO-30 exists, 16 over
     * 1 m lidar), so a world at zoom 15 must work everywhere; it just carries no more detail than the data has.
     */
    private float[][] fromParent(int zoom, int x, int y) {
        float[][] parent = obtain(zoom - 1, x >> 1, y >> 1);
        int n = parent.length;
        int half = n / 2;
        int ox = (x & 1) * half, oy = (y & 1) * half;
        float[][] out = new float[n][n];
        for (int j = 0; j < n; j++) {
            double sy = oy + (j + 0.5) / 2.0 - 0.5;
            int y0 = (int) Math.floor(sy);
            double fy = sy - y0;
            int ya = Math.max(0, Math.min(n - 1, y0)), yb = Math.max(0, Math.min(n - 1, y0 + 1));
            for (int i = 0; i < n; i++) {
                double sx = ox + (i + 0.5) / 2.0 - 0.5;
                int x0 = (int) Math.floor(sx);
                double fx = sx - x0;
                int xa = Math.max(0, Math.min(n - 1, x0)), xb = Math.max(0, Math.min(n - 1, x0 + 1));
                double top = parent[ya][xa] * (1 - fx) + parent[ya][xb] * fx;
                double bottom = parent[yb][xa] * (1 - fx) + parent[yb][xb] * fx;
                out[j][i] = (float) (top * (1 - fy) + bottom * fy);
            }
        }
        return out;
    }

    /** A tile through the memory cache, loading it here (not through the async path) so nested loads cannot wait on each other. */
    private float[][] obtain(int zoom, int x, int y) {
        String key = zoom + "/" + x + "/" + y;
        synchronized (memoryCache) {
            float[][] cached = memoryCache.get(key);
            if (cached != null) return cached;
        }
        return remember(key, load(zoom, x, y, key));
    }

    private byte[] downloadTile(int z, int x, int y) throws IOException, InterruptedException {
        String url = urlTemplate.replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));
        // A missing elevation tile would leave every chunk on it at sea level, so a transient network
        // failure is retried for a couple of minutes before giving up (and each failure is logged).
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.get(url,
                        java.util.Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 20);
                if (resp.status() == 200) return resp.body();
                if (resp.status() == 404) throw new TileMissing("DEM tile not found (404): " + url);
                last = new IOException("DEM tile fetch failed (" + resp.status() + "): " + url);
            } catch (TileMissing e) {
                throw e; // a 404 is an answer, not a failure to retry
            } catch (IOException e) {
                last = e;
            }
            System.err.println("[orbis] DEM tile " + z + "/" + x + "/" + y + " attempt " + (attempt + 1) + "/6 failed: " + last.getMessage());
            boolean streamLimit = last.getMessage() != null && last.getMessage().contains("concurrent streams");
            Thread.sleep(streamLimit ? 300L + (long) (Math.random() * 500) : Math.min(30_000L, 2000L << attempt));
        }
        throw last;
    }

    /** Whether every height is one the Earth has: above the Mariana Trench (-11 km) and below Everest (8.8 km). */
    private static boolean plausible(float[][] tile) {
        for (float[] row : tile) {
            for (float v : row) {
                if (!(v > -11500f && v < 9000f)) return false;
            }
        }
        return true;
    }

    private static float[][] decodeTerrarium(byte[] pngBytes) throws IOException {
        BufferedImage img = TileImages.decode(pngBytes); // PNG (AWS) or lossless WebP (Mapterhorn)
        int w = img.getWidth(), h = img.getHeight();
        float[][] elevation = new float[h][w];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                elevation[py][px] = (r * 256 + g + b / 256.0f) - 32768f;
            }
        }
        return elevation;
    }

    /** Converts lat/lon to fractional tile coordinates at a given zoom (for interpolation). */
    public static double[] latLonToTileFraction(double lat, double lon, int zoom) {
        double n = Math.pow(2, zoom);
        double x = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
        double y = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n;
        return new double[]{x, y};
    }

    /** Longitude of the west edge of tile column x. */
    public static double tileToLon(int x, int zoom) {
        return x / Math.pow(2, zoom) * 360.0 - 180.0;
    }

    /** Latitude of the north edge of tile row y. */
    public static double tileToLat(int y, int zoom) {
        double n = Math.PI - 2.0 * Math.PI * y / Math.pow(2, zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public int cachedTiles() {
        return memoryCache.size();
    }
}
