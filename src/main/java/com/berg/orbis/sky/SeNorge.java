package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Norway's snow depth today at 1 km: seNorge (MET Norway and NVE, NLOD), the snow model run every day on observed
 * temperature and precipitation and checked against snow stations, on MET's THREDDS server. Read in tiles of 32 x 32 km
 * (OPeNDAP ASCII, a few kB each), kept on disk for the day; the newest day is looked up again every few hours.
 */
public final class SeNorge {
    public static final String ATTRIBUTION = "seNorge snow depth (MET Norway / NVE, NLOD)";

    private static final String BASE = "https://thredds.met.no/thredds/dodsC/senorge/seNorge_snow/sd/sd_%d.nc";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final int NX = 1195, NY = 1550, TILE = 32;
    private static final double X0 = -74500, Y0 = 6450500, CELL = 1000;
    private static final long DAY_CHECK_MS = 3 * 3600_000L, RETRY_MS = 10 * 60_000L;

    private static final Map<Path, SeNorge> SHARED = new java.util.HashMap<>();

    private final Path cacheDir;
    private volatile int year, dayIndex = -1;
    private volatile long dayCheckedAt, failedUntil;
    private final Map<Long, CompletableFuture<float[]>> tiles = new ConcurrentHashMap<>();

    private SeNorge(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static synchronized SeNorge shared(Path cacheDir) {
        return SHARED.computeIfAbsent(cacheDir, SeNorge::new);
    }

    /** Whether a place lies on seNorge's grid at all (Norway's mainland and some of Sweden's edge). */
    public static boolean covers(double lat, double lon) {
        return lat > 57.8 && lat < 71.5 && lon > 4.0 && lon < 31.5;
    }

    /** Snow depth in metres today at a place, NaN where seNorge has none (the sea, outside Norway) or it cannot be had in time. */
    public double depthM(double lat, double lon, long waitMs) {
        if (!covers(lat, lon) || System.currentTimeMillis() < failedUntil) return Double.NaN;
        double[] xy = utm33(lat, lon);
        double fx = (xy[0] - X0) / CELL, fy = (xy[1] - Y0) / CELL;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= NX || y0 + 1 >= NY) return Double.NaN;
        if (!day(waitMs)) return Double.NaN;
        double tx = fx - x0, ty = fy - y0, sum = 0, wsum = 0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                float v = cell(x0 + i, y0 + j, waitMs);
                if (Float.isNaN(v)) continue;
                double w = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
                sum += v * w;
                wsum += w;
            }
        }
        return wsum > 1e-6 ? sum / wsum / 100.0 : Double.NaN;
    }

    private float cell(int x, int y, long waitMs) {
        int tx = x / TILE, ty = y / TILE;
        long key = ((long) year * 1000 + dayIndex) << 32 | (long) ty << 16 | tx;
        CompletableFuture<float[]> f = tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> tile(tx, ty)));
        float[] t;
        try {
            t = waitMs <= 0 ? f.getNow(null) : f.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Float.NaN;
        }
        if (t == null) {
            if (f.isDone()) tiles.remove(key, f);
            return Float.NaN;
        }
        return t[(y - ty * TILE) * TILE + (x - tx * TILE)];
    }

    /** Finds the newest day on the server (the time axis' length of this year's file). */
    private boolean day(long waitMs) {
        if (dayIndex >= 0 && System.currentTimeMillis() - dayCheckedAt < DAY_CHECK_MS) return true;
        if (waitMs <= 0 && dayIndex >= 0) return true;
        synchronized (this) {
            if (dayIndex >= 0 && System.currentTimeMillis() - dayCheckedAt < DAY_CHECK_MS) return true;
            try {
                int y = LocalDate.now(ZoneOffset.UTC).getYear();
                OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, BASE, y) + ".dds", HEADERS, 30);
                if (r.status() != 200) throw new IOException("HTTP " + r.status());
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("time\\[time = (\\d+)\\]").matcher(r.text());
                if (!m.find()) throw new IOException("no time axis");
                int n = Integer.parseInt(m.group(1));
                if (n <= 0) throw new IOException("empty year");
                if (y != year || n - 1 != dayIndex) tiles.clear();
                year = y;
                dayIndex = n - 1;
                dayCheckedAt = System.currentTimeMillis();
                return true;
            } catch (IOException | InterruptedException | RuntimeException e) {
                failedUntil = System.currentTimeMillis() + RETRY_MS;
                System.err.println("[orbis] seNorge snow depth unavailable (" + e.getMessage() + ")");
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return dayIndex >= 0;
            }
        }
    }

    /** One tile of the day's depths (cm, NaN where none), from disk or the server; null on failure. */
    private float[] tile(int tx, int ty) {
        int y = year, d = dayIndex;
        Path file = cacheDir.resolve(String.format(Locale.ROOT, "%d_%03d_%d_%d.bin", y, d, tx, ty));
        try {
            if (Files.exists(file)) {
                try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
                    float[] t = new float[TILE * TILE];
                    for (int i = 0; i < t.length; i++) t[i] = in.readFloat();
                    return t;
                }
            }
            int xa = tx * TILE, ya = ty * TILE, xb = Math.min(NX, xa + TILE) - 1, yb = Math.min(NY, ya + TILE) - 1;
            String url = String.format(Locale.ROOT, BASE, y) + ".ascii?snow_depth%5B" + d + "%5D%5B" + ya + ":1:" + yb + "%5D%5B" + xa + ":1:" + xb + "%5D";
            OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 60);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            float[] t = new float[TILE * TILE];
            java.util.Arrays.fill(t, Float.NaN);
            // Rows look like "[0][5], 12.3, NaN, 4.0": time and y index, then the values along x.
            for (String line : r.text().split("\n")) {
                line = line.trim();
                if (!line.startsWith("[")) continue;
                String[] parts = line.split(",");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)\\]\\[(\\d+)\\]").matcher(parts[0]);
                if (!m.find()) continue;
                int row = Integer.parseInt(m.group(2));
                for (int i = 1; i < parts.length && i - 1 < TILE; i++) {
                    String v = parts[i].trim();
                    float f;
                    try {
                        f = Float.parseFloat(v);
                    } catch (NumberFormatException e) {
                        f = Float.NaN;
                    }
                    if (f < 0 || f > 10_000) f = Float.NaN;
                    t[row * TILE + (i - 1)] = f;
                }
            }
            Files.createDirectories(cacheDir);
            Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                for (float v : t) out.writeFloat(v);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return t;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + RETRY_MS;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** WGS84 / UTM zone 33 N (transverse Mercator, central meridian 15° E), easting and northing in metres. */
    static double[] utm33(double lat, double lon) {
        final double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996, e2 = f * (2 - f), ep2 = e2 / (1 - e2);
        double phi = Math.toRadians(lat), dl = Math.toRadians(lon - 15);
        double s = Math.sin(phi), c = Math.cos(phi), tn = Math.tan(phi);
        double n = a / Math.sqrt(1 - e2 * s * s), t = tn * tn, cc = ep2 * c * c, aa = c * dl;
        double e4 = e2 * e2, e6 = e4 * e2;
        double m = a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi) - (35 * e6 / 3072) * Math.sin(6 * phi));
        double x = k0 * n * (aa + (1 - t + cc) * Math.pow(aa, 3) / 6 + (5 - 18 * t + t * t + 72 * cc - 58 * ep2) * Math.pow(aa, 5) / 120) + 500000;
        double y = k0 * (m + n * tn * (aa * aa / 2 + (5 - t + 9 * cc + 4 * cc * cc) * Math.pow(aa, 4) / 24
                + (61 - 58 * t + t * t + 600 * cc - 330 * ep2) * Math.pow(aa, 6) / 720));
        return new double[]{x, y};
    }
}
