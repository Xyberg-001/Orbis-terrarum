package com.berg.orbis.dem;

import com.berg.orbis.config.OrbisConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-resolution elevation from any web service that can return a float32
 * GeoTIFF for a lat/lon bounding box (ArcGIS ImageServer exportImage, OGC
 * WCS GetCoverage, ...). Tiles are requested on the slippy-map grid at a
 * configurable zoom, cached on disk, and sampled with the same bicubic
 * interpolation as the global source.
 *
 * Configured through {@code elevationSources} / {@code surfaceModelSources}
 * in the config, with a coverage box per source. Shipped defaults: USGS 3DEP
 * (USA, ~10 m) and Kartverket's national 1 m lidar terrain and surface
 * models (Norway). Any failure returns NaN so the next source takes over;
 * after repeated failures a source disables itself for ten minutes.
 */
public class ImageServiceDemSource implements DemSource, TileSource {

    private final OrbisConfig.ElevationSource src;
    private final Path cacheDir;
    private final int zoom;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final BicubicElevationSampler sampler;
    private final Map<String, float[][]> memoryCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[][]> eldest) {
            return size() > 192;
        }
    });
    private final ConcurrentHashMap<String, CompletableFuture<float[][]>> loading = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long disabledUntil;
    private volatile boolean announced;
    /** The terrain in metres, for a service whose values are heights above the ground. */
    private volatile java.util.function.DoubleBinaryOperator ground;

    public ImageServiceDemSource(OrbisConfig.ElevationSource src, Path cacheDir) {
        this.src = src;
        this.cacheDir = cacheDir.resolve(src.name.replaceAll("[^A-Za-z0-9_-]", "_"));
        this.zoom = Math.max(11, Math.min(17, src.zoom));
        this.sampler = new BicubicElevationSampler(this, this.zoom);
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create cache dir: " + this.cacheDir, e);
        }
    }

    public String name() {
        return src.name;
    }

    public void setGround(java.util.function.DoubleBinaryOperator ground) {
        this.ground = ground;
    }

    /** Parallel downloads for {@link #prefetch}: a few at a time, as a busy public service can take. */
    private static final java.util.concurrent.ExecutorService PREFETCH = java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "Orbis-lidar-prefetch");
        t.setDaemon(true);
        return t;
    });

    /**
     * Loads every tile under the given lat/lon boxes (south, west, north, east) in parallel and waits for them, so a
     * slow service (Kartverket answers some requests only after 30 s) is waited on once per region, not once per
     * tile in a row. Tiles already on disk cost nothing.
     */
    public void prefetch(List<double[]> boxes) {
        if (unavailable()) return;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<CompletableFuture<?>> jobs = new ArrayList<>();
        for (double[] b : boxes) {
            if (b[2] < src.south || b[0] > src.north || b[3] < src.west || b[1] > src.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(src.name, b[0], b[1], b[2], b[3])) continue; // outside its country
            double[] a = DemTileProvider.latLonToTileFraction(b[2], b[1], zoom), c = DemTileProvider.latLonToTileFraction(b[0], b[3], zoom);
            for (int x = (int) Math.floor(a[0]); x <= (int) Math.floor(c[0]); x++) {
                for (int y = (int) Math.floor(a[1]); y <= (int) Math.floor(c[1]); y++) {
                    if (!seen.add(((long) x << 32) | (y & 0xffffffffL))) continue;
                    int tx = x, ty = y;
                    if (memoryCache.containsKey(zoom + "/" + tx + "/" + ty)) continue;
                    jobs.add(CompletableFuture.runAsync(() -> {
                        if (unavailable()) return; // gave up meanwhile: the rest of the queue is skipped at once
                        try {
                            getTile(zoom, tx, ty);
                            consecutiveFailures.set(0);
                        } catch (RuntimeException e) {
                            noteFailure(e); // the next region tries again
                        }
                    }, PREFETCH));
                }
            }
        }
        for (CompletableFuture<?> j : jobs) {
            try {
                j.join();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Switched off for a while after a long run of failures, and while the server stops: closing the world must not
     * wait minutes for a slow service (the chunks still being generated then use estimated heights).
     */
    private boolean unavailable() {
        return System.currentTimeMillis() < disabledUntil || com.berg.orbis.OrbisMod.stopping() || !answers();
    }

    /** A request that tells whether the service answers from this network, when it is only used if it does. */
    private volatile String probe;
    /** The last answer per probe, shared by every model built in this game: {answers (1/0), when it was asked}. */
    private static final Map<String, long[]> PROBES = new ConcurrentHashMap<>();
    private static final long TRUST_MS = 15 * 60_000L, RETRY_MS = 2 * 60_000L;

    /**
     * Use the service only while it answers from this network (Kartverket: some networks reach it, others never do):
     * asked on first use with a tiny real tile, from the generation threads, never the render thread. A yes is
     * trusted for 15 minutes across the models a world creation builds (the world height rebuilds it); a no is asked
     * again after two minutes. Tile failures after that are handled like any service's (noteFailure).
     */
    public void useWhenReachable(String probeUrl) {
        this.probe = probeUrl;
    }

    private boolean answers() {
        String p = probe;
        if (p == null) return true;
        long now = System.currentTimeMillis();
        long[] last = PROBES.get(p);
        if (last != null && now - last[1] < (last[0] == 1 ? TRUST_MS : RETRY_MS)) return last[0] == 1;
        synchronized (PROBES) {
            last = PROBES.get(p);
            if (last != null && System.currentTimeMillis() - last[1] < (last[0] == 1 ? TRUST_MS : RETRY_MS)) return last[0] == 1;
            boolean ok = false;
            for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                try {
                    com.berg.orbis.net.OrbisHttp.Response r = com.berg.orbis.net.OrbisHttp.get(p, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 20);
                    ok = r.status() == 200 && com.berg.orbis.config.DataSources.isTiff(r.body());
                } catch (IOException | RuntimeException e) {
                    ok = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean before = last != null && last[0] == 1;
            PROBES.put(p, new long[]{ok ? 1 : 0, System.currentTimeMillis()});
            if (last == null || before != ok) {
                System.out.println("[orbis] Surface model '" + src.name + "' " + (ok
                        ? "answers from this network: measured building heights and roofs are on"
                        : "does not answer from this network: building heights come from the other sources (asked again in 2 minutes)"));
            }
            return ok;
        }
    }

    private void noteFailure(RuntimeException e) {
        // A tile has already had its tries (Kartverket answers half its requests with a 504 on a bad day): only a
        // long run of failed tiles means the service is really gone, and then only for a short while.
        if (consecutiveFailures.incrementAndGet() >= 12) {
            disabledUntil = System.currentTimeMillis() + 3 * 60_000L;
            consecutiveFailures.set(0);
            System.err.println("[orbis] Elevation source '" + src.name + "' unavailable (" + e.getMessage()
                    + "); falling back to the next source for 3 minutes");
        }
    }

    @Override
    public double sampleMeters(double lat, double lon) {
        if (unavailable()) return Double.NaN;
        try {
            double v = sampler.sampleMeters(lat, lon);
            if (!Double.isNaN(v)) {
                v *= src.valueScale; // feet
                if (src.aboveGround) {
                    java.util.function.DoubleBinaryOperator g = ground;
                    double terrain = g == null ? Double.NaN : g.applyAsDouble(lat, lon);
                    v = Double.isNaN(terrain) ? Double.NaN : terrain + v;
                }
            }
            consecutiveFailures.set(0);
            if (!announced && !Double.isNaN(v)) {
                announced = true;
                System.out.println("[orbis] Elevation source '" + src.name + "' is live (" + src.resolutionMeters + " m)");
            }
            return v;
        } catch (RuntimeException e) {
            noteFailure(e);
            return Double.NaN;
        }
    }

    @Override
    public double nativeResolutionMeters() {
        return src.resolutionMeters;
    }

    @Override
    public boolean hasCoverage(double lat, double lon) {
        return lat >= src.south && lat <= src.north && lon >= src.west && lon <= src.east && !unavailable();
    }

    @Override
    public float[][] getTile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        float[][] cached = memoryCache.get(key);
        if (cached != null) return cached;
        CompletableFuture<float[][]> f = loading.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(z, x, y)));
        try {
            float[][] tile = f.join();
            memoryCache.put(key, tile);
            return tile;
        } finally {
            loading.remove(key, f);
        }
    }

    private float[][] load(int z, int x, int y) {
        try {
            Path cached = cacheDir.resolve(z + "_" + x + "_" + y + ".tif");
            byte[] bytes;
            if (Files.exists(cached)) {
                bytes = Files.readAllBytes(cached);
            } else {
                double west = DemTileProvider.tileToLon(x, z);
                double east = DemTileProvider.tileToLon(x + 1, z);
                double north = DemTileProvider.tileToLat(y, z);
                double south = DemTileProvider.tileToLat(y + 1, z);
                if (!com.berg.orbis.config.DataSources.covers(src.name, south, west, north, east)) {
                    // Outside the service's country (its box is wider): nothing to ask it for.
                    float[][] none = new float[256][256];
                    for (float[] row : none) java.util.Arrays.fill(row, Float.NaN);
                    return none;
                }
                String url = src.urlTemplate
                        .replace("{west}", fmt(west)).replace("{south}", fmt(south))
                        .replace("{east}", fmt(east)).replace("{north}", fmt(north));
                // Kartverket's services answer about half the requests with 504 after 30 s on a bad day: a tile gets
                // three tries (a missing tile leaves its buildings with estimated heights, and is tried again later).
                com.berg.orbis.net.OrbisHttp.Response resp = null;
                IOException lastError = null;
                // Once tiles are failing in a row, one try each: three tries of 45 s would hold a region for minutes.
                int tries = consecutiveFailures.get() >= 2 ? 1 : 3;
                for (int attempt = 0; attempt < tries; attempt++) {
                    if (unavailable()) throw new IOException("stopped asking (server stopping or service failing)");
                    try {
                        resp = com.berg.orbis.net.OrbisHttp.get(url, java.util.Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 45);
                        if (resp.status() == 200) break;
                        lastError = new IOException("HTTP " + resp.status());
                        if (resp.status() < 500 && resp.status() != 429) break; // a real refusal: no point asking again
                    } catch (IOException e) {
                        lastError = e;
                        if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException) break; // unreachable
                    }
                    resp = null;
                    if (attempt + 1 < tries) Thread.sleep(1000L * (attempt + 1));
                }
                if (resp == null || resp.status() != 200) throw lastError != null ? lastError : new IOException("no answer");
                bytes = resp.body();
                if (bytes.length < 100 || (bytes[0] != 'I' && bytes[0] != 'M')) {
                    String head = new String(bytes, 0, Math.min(bytes.length, 120), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("\\s+", " ");
                    throw new IOException("not a TIFF: " + head);
                }
                DemTileProvider.writeCached(cached, bytes);
            }
            try {
                return decodeFloatTiff(bytes, src.noData);
            } catch (java.io.EOFException e) {
                // Kartverket answers a tile with no data at all (open sea) with an 848-byte TIFF that declares the
                // full size but carries no pixels. Nothing measured here: not a failure (counting it as one switched
                // the service off for minutes, and the buildings around with it).
                if (bytes.length > 4096) throw e;
                float[][] empty = new float[256][256];
                for (float[] row : empty) java.util.Arrays.fill(row, Float.NaN);
                return empty;
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(src.name + " tile " + z + "/" + x + "/" + y + ": " + e.getMessage(), e);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.7f", v);
    }

    private static float[][] decodeFloatTiff(byte[] bytes, double noData) throws IOException {
        BufferedImage img;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            img = ImageIO.read(in);
        }
        if (img == null) throw new IOException("ImageIO cannot decode the TIFF");
        Raster raster = img.getRaster();
        int w = raster.getWidth(), h = raster.getHeight();
        float[][] out = new float[h][w];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float v = raster.getSampleFloat(px, py, 0);
                if (Float.isNaN(v) || v < -1000f || v > 9000f || (!Double.isNaN(noData) && Math.abs(v - noData) < 0.01)) v = Float.NaN;
                out[py][px] = v;
            }
        }
        return out;
    }
}
