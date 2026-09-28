package com.berg.orbis.dem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tree canopy height in metres from local tiles under config/orbisterrarum/canopy-cache, written by
 * tools/canopy_tiles.py from the Meta / WRI 1 m global canopy height map (CC BY 4.0). Tiles are slippy-map
 * PNGs at one zoom level in the same terrarium encoding as the terrain tiles, so the bicubic sampler is
 * shared. No network: where no tile exists the answer is NaN and the caller falls back.
 */
public final class CanopyProvider implements TileSource {

    public static final int ZOOM = 16;
    private static final int MEMORY_TILES = 256;

    private final Path dir;
    private final BicubicElevationSampler sampler;
    private final Map<String, float[][]> memory = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[][]> eldest) {
            return size() > MEMORY_TILES;
        }
    });

    /**
     * Tiles known not to exist. Without it every sample outside the imported area asked the disk again and built
     * an exception with a stack trace: a map region (a million samples) then took minutes, and a pre-generation
     * whose first rows lay outside the canopy import generated nothing for over five minutes.
     */
    private final java.util.Set<String> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Thrown for a missing tile: shared and without a stack trace, since callers only catch it. */
    private static final RuntimeException NO_TILE = new RuntimeException("no canopy tile", null, false, false) {
    };

    private CanopyProvider(Path dir) {
        this.dir = dir;
        this.sampler = new BicubicElevationSampler(this, ZOOM);
    }

    /** The provider for a tile folder, or null when the folder holds no tiles. */
    public static CanopyProvider open(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) {
            if (s.noneMatch(p -> p.getFileName().toString().endsWith(".png"))) return null;
        } catch (IOException e) {
            return null;
        }
        return new CanopyProvider(dir);
    }

    /** Canopy height in metres (0 where there is no tree), or NaN outside the imported tiles. */
    public double sampleMeters(double lat, double lon) {
        try {
            double v = sampler.sampleMeters(lat, lon);
            return Double.isNaN(v) ? Double.NaN : Math.max(0, v);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    @Override
    public float[][] getTile(int zoom, int x, int y) {
        String key = zoom + "_" + x + "_" + y;
        float[][] t = memory.get(key);
        if (t != null) return t;
        if (missing.contains(key)) throw NO_TILE;
        Path file = dir.resolve(key + ".png");
        if (!Files.exists(file)) {
            missing.add(key);
            throw NO_TILE;
        }
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) throw new RuntimeException("unreadable canopy tile " + key);
            int w = img.getWidth(), h = img.getHeight();
            int[] all = TileImages.rgbPixels(img);
            t = new float[h][w];
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    int rgb = all[py * w + px];
                    t[py][px] = (((rgb >> 16) & 0xFF) * 256 + ((rgb >> 8) & 0xFF) + (rgb & 0xFF) / 256.0f) - 32768f;
                }
            }
            memory.put(key, t);
            return t;
        } catch (IOException e) {
            throw new RuntimeException("canopy tile " + key + ": " + e.getMessage(), e);
        }
    }
}
