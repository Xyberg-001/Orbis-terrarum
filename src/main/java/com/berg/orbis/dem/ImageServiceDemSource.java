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
import java.util.Collections;
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

    @Override
    public double sampleMeters(double lat, double lon) {
        if (System.currentTimeMillis() < disabledUntil) return Double.NaN;
        try {
            double v = sampler.sampleMeters(lat, lon);
            consecutiveFailures.set(0);
            if (!announced && !Double.isNaN(v)) {
                announced = true;
                System.out.println("[orbis] Elevation source '" + src.name + "' is live (" + src.resolutionMeters + " m)");
            }
            return v;
        } catch (RuntimeException e) {
            if (consecutiveFailures.incrementAndGet() >= 4) {
                disabledUntil = System.currentTimeMillis() + 10 * 60_000L;
                consecutiveFailures.set(0);
                System.err.println("[orbis] Elevation source '" + src.name + "' unavailable (" + e.getMessage()
                        + "); falling back to the next source for 10 minutes");
            }
            return Double.NaN;
        }
    }

    @Override
    public double nativeResolutionMeters() {
        return src.resolutionMeters;
    }

    @Override
    public boolean hasCoverage(double lat, double lon) {
        return lat >= src.south && lat <= src.north && lon >= src.west && lon <= src.east;
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
                String url = src.urlTemplate
                        .replace("{west}", fmt(west)).replace("{south}", fmt(south))
                        .replace("{east}", fmt(east)).replace("{north}", fmt(north));
                com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.get(url,
                        java.util.Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 45);
                if (resp.status() != 200) throw new IOException("HTTP " + resp.status());
                bytes = resp.body();
                if (bytes.length < 100 || (bytes[0] != 'I' && bytes[0] != 'M')) {
                    String head = new String(bytes, 0, Math.min(bytes.length, 120), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("\\s+", " ");
                    throw new IOException("not a TIFF: " + head);
                }
                Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return decodeFloatTiff(bytes, src.noData);
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
