package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * The lower 48 states' snow depth today at 1 km: SNODAS (NOAA's National Operational Hydrologic Remote Sensing Center,
 * public domain, via NSIDC), the snow model run every day on satellite, airborne and station observations of the snow. A
 * day's file (a tar of eight grids, 3-30 MB) is posted the same afternoon; only its snow depth grid is fetched, by range
 * requests that walk the tar's headers, and kept unpacked on disk (46 MB, 6,935 x 3,351 cells of 16 bits) to be read
 * where needed. The newest day is looked up again every three hours.
 */
public final class Snodas {
    public static final String ATTRIBUTION = "SNODAS snow depth (NOAA NOHRSC, via NSIDC)";

    private static final String DAY_URL = "https://noaadata.apps.nsidc.org/NOAA/G02158/masked/%1$tY/%1$tm_%2$s/SNODAS_%1$tY%1$tm%1$td.tar";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    /** The snow depth grid in the tar (product 1036, snow depth, a 24-hour snapshot). */
    private static final String DEPTH = "ssmv11036tS__T0001";
    private static final int COLS = 6935, ROWS = 3351;
    private static final double WEST = -124.733333333328, NORTH = 52.8749999999979, CELL = 0.00833333333333300;
    private static final long DAY_CHECK_MS = 3 * 3600_000L, RETRY_MS = 15 * 60_000L;
    /** Deeper than this is a glacier's or a permanent snowfield's capped value (SNODAS writes 32.767 m): taken as this. */
    private static final double MAX_DEPTH_M = 4.0;

    private static final Map<Path, Snodas> SHARED = new HashMap<>();

    private final Path cacheDir;
    private volatile MappedByteBuffer grid;
    private volatile LocalDate gridDay;
    private volatile long checkedAt, failedUntil;
    private CompletableFuture<Void> loading;
    private boolean keptLooked;

    private Snodas(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static synchronized Snodas shared(Path cacheDir) {
        return SHARED.computeIfAbsent(cacheDir, Snodas::new);
    }

    /** Whether a place lies on SNODAS's grid (the lower 48 states and a rim of Canada and Mexico, land only inside it). */
    public static boolean covers(double lat, double lon) {
        return lat > 24.95 && lat < 52.87 && lon > -124.73 && lon < -66.95;
    }

    /** Snow depth in metres today at a place, NaN where SNODAS has none (water, outside the US) or it cannot be had in time. */
    public double depthM(double lat, double lon, long waitMs) {
        if (!covers(lat, lon)) return Double.NaN;
        MappedByteBuffer g = ready(waitMs);
        if (g == null) return Double.NaN;
        double fx = (lon - WEST) / CELL - 0.5, fy = (NORTH - lat) / CELL - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0, sum = 0, wsum = 0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                int x = x0 + i, y = y0 + j;
                if (x < 0 || y < 0 || x >= COLS || y >= ROWS) continue;
                int v = g.getShort(2 * (y * COLS + x));
                if (v < 0) continue; // -9999: water or outside
                double w = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
                sum += Math.min(MAX_DEPTH_M, v / 1000.0) * w;
                wsum += w;
            }
        }
        return wsum > 1e-6 ? sum / wsum : Double.NaN;
    }

    /** The newest grid, waiting up to {@code waitMs} for a lookup or download under way; null without one. */
    private MappedByteBuffer ready(long waitMs) {
        long now = System.currentTimeMillis();
        CompletableFuture<Void> f;
        synchronized (this) {
            if (!keptLooked) {
                keptLooked = true;
                openKept();
            }
            if (now >= failedUntil && now - checkedAt > DAY_CHECK_MS && (loading == null || loading.isDone())) {
                checkedAt = now;
                loading = CompletableFuture.runAsync(this::refresh);
            }
            f = loading;
        }
        if (grid == null && f != null && waitMs > 0) {
            try {
                f.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // not in time: no depth this time
            }
        }
        return grid;
    }

    /** Opens the newest day kept on disk (after a restart), so nothing is fetched until a newer day is due. */
    private void openKept() {
        try (var files = Files.list(cacheDir)) {
            Path newest = files.filter(p -> p.getFileName().toString().matches("snodas-\\d{8}\\.bin")).max(Path::compareTo).orElse(null);
            if (newest != null) open(newest, LocalDate.parse(newest.getFileName().toString().substring(7, 15), java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        } catch (IOException | RuntimeException ignored) {
            // nothing kept
        }
    }

    private void refresh() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            for (int back = 0; back <= 3; back++) {
                LocalDate day = today.minusDays(back);
                if (gridDay != null && !day.isAfter(gridDay)) return; // what we have is the newest
                Path file = cacheDir.resolve(String.format(Locale.ROOT, "snodas-%1$tY%1$tm%1$td.bin", day));
                if (Files.exists(file) || fetch(day, file)) {
                    open(file, day);
                    removeOlder(file);
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] SNODAS snow depth unavailable (" + e + "); the US gets the model's snow");
            failedUntil = System.currentTimeMillis() + RETRY_MS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Walks the day's tar by its headers to the snow depth grid and keeps it unpacked; false when the day is not posted. */
    private boolean fetch(LocalDate day, Path file) throws IOException, InterruptedException {
        String month = day.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        String url = String.format(Locale.ROOT, DAY_URL, day, month);
        long at = 0;
        for (int entry = 0; entry < 40; entry++) {
            byte[] header = range(url, at, 512);
            if (header == null) return false;
            String name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
            if (name.isEmpty()) return false; // the tar's end: no snow depth in it
            long size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim(), 8);
            if (name.contains(DEPTH) && name.endsWith(".dat.gz")) {
                byte[] gz = range(url, at + 512, size);
                if (gz == null) return false;
                Files.createDirectories(cacheDir);
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz), 1 << 16);
                     OutputStream out = Files.newOutputStream(tmp)) {
                    long n = in.transferTo(out);
                    if (n != 2L * COLS * ROWS) throw new IOException("SNODAS depth grid of " + n + " bytes");
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            }
            at += 512 + (size + 511) / 512 * 512;
        }
        return false;
    }

    private static byte[] range(String url, long offset, long length) throws IOException, InterruptedException {
        Map<String, String> h = new HashMap<>(HEADERS);
        h.put("Range", "bytes=" + offset + "-" + (offset + length - 1));
        OrbisHttp.Response r = OrbisHttp.get(url, h, 120);
        if (r.status() == 404) return null;
        if (r.status() != 206 || r.body() == null || r.body().length != length) throw new IOException("SNODAS answered " + r.status());
        return r.body();
    }

    private void open(Path file, LocalDate day) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() != 2L * COLS * ROWS) throw new IOException("SNODAS file " + file.getFileName() + " is " + ch.size() + " bytes");
            grid = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()); // big-endian, as the grid is
            gridDay = day;
        }
    }

    /** Older days go (a file still mapped on Windows stays until the next start). */
    private void removeOlder(Path keep) {
        try (var files = Files.list(cacheDir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("snodas-") && !p.equals(keep)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // in use
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }
}
