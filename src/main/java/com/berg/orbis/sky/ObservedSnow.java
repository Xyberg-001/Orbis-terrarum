package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Where snow lies and water is frozen today, as seen: NOAA's Interactive Multisensor Snow and Ice Mapping System (IMS,
 * via NSIDC, public domain), drawn every day by analysts from satellite images, weather stations and models, so it has no
 * gaps under cloud: the whole Northern Hemisphere at 1 km, each cell open water, land, ice on water or snow on land. The day's
 * file (3 MB) is posted the next afternoon; it is a text grid of 24,576 rows on a polar stereographic map (true at 60° N,
 * centred on 80° W), read as a stream and kept as runs of snow and ice per row (a few MB), on disk for a restart. Looked
 * at again every six hours for a newer day.
 */
public final class ObservedSnow {
    public static final String ATTRIBUTION = "NOAA/NESDIS IMS daily Northern Hemisphere snow and ice analysis, 1 km (US National Ice Center, via NSIDC; public domain)";

    /** What a place's 1 km cell is today: open water, bare land, ice on water, snow on land; or not known. */
    public static final int UNKNOWN = -1, WATER = 1, LAND = 2, ICE = 3, SNOW = 4;

    private static final String URL = "https://noaadata.apps.nsidc.org/NOAA/G02156/1km/%d/ims%d%03d_00UTC_1km_v1.3.asc.gz";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final int N = 24576;
    private static final double CELL_M = 1000, HALF = N * CELL_M / 2;
    private static final long CHECK_MS = 6 * 3600_000L, RETRY_MS = 30 * 60_000L;

    /** Per row from the top: runs of {start column, end column (exclusive), class} for water, ice and snow (bare land between them). */
    private record Grid(String day, char[][] rows) {
    }

    private static final Map<Path, ObservedSnow> SHARED = new java.util.HashMap<>();

    private final Path cacheDir;
    private volatile Grid grid;
    private volatile long checkedAt;
    private volatile CompletableFuture<Void> loading;

    private ObservedSnow(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static synchronized ObservedSnow shared(Path cacheDir) {
        return SHARED.computeIfAbsent(cacheDir, ObservedSnow::new);
    }

    /** What a place is today (WATER, LAND, ICE, SNOW), or UNKNOWN (south of the equator, or not loaded yet; this starts the load). */
    public int classAt(double lat, double lon) {
        if (lat < 1) return UNKNOWN;
        Grid g = grid;
        if (g == null || System.currentTimeMillis() - checkedAt > CHECK_MS) refresh();
        g = grid;
        if (g == null) return UNKNOWN;
        double[] xy = project(lat, lon);
        int col = (int) Math.floor((xy[0] + HALF) / CELL_M);
        int fromBottom = (int) Math.floor((xy[1] + HALF) / CELL_M);
        int row = N - 1 - fromBottom;
        if (row < 0 || row >= N || col < 0 || col >= N) return UNKNOWN;
        char[] runs = g.rows[row];
        if (runs == null) return UNKNOWN;
        // Binary search over the runs (sorted by start column).
        int lo = 0, hi = runs.length / 3 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int s = runs[mid * 3], e = runs[mid * 3 + 1];
            if (col < s) hi = mid - 1;
            else if (col >= e) lo = mid + 1;
            else return runs[mid * 3 + 2];
        }
        return LAND;
    }

    /** WGS84 polar stereographic, true at 60° N, central meridian 80° W: x, y in metres. */
    static double[] project(double lat, double lon) {
        final double a = 6378137.0, e = 0.0818191908426;
        double phi = Math.toRadians(lat), lam = Math.toRadians(lon + 80), phic = Math.toRadians(60);
        double rho = a * m(phic, e) * t(phi, e) / t(phic, e);
        return new double[]{rho * Math.sin(lam), -rho * Math.cos(lam)};
    }

    private static double t(double p, double e) {
        double s = Math.sin(p);
        return Math.tan(Math.PI / 4 - p / 2) / Math.pow((1 - e * s) / (1 + e * s), e / 2);
    }

    private static double m(double p, double e) {
        double s = Math.sin(p);
        return Math.cos(p) / Math.sqrt(1 - e * e * s * s);
    }

    private synchronized void refresh() {
        if (loading != null && !loading.isDone()) return;
        checkedAt = System.currentTimeMillis();
        loading = CompletableFuture.runAsync(() -> {
            try {
                load();
            } catch (Exception e) {
                checkedAt = System.currentTimeMillis() - CHECK_MS + RETRY_MS;
                System.err.println("[orbis] Observed snow (IMS) unavailable: " + e.getMessage());
            }
        });
    }

    private void load() throws IOException, InterruptedException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // The newest day there is: yesterday's is posted in the afternoon (UTC), so try up to four days back.
        for (int back = 1; back <= 4; back++) {
            LocalDate d = today.minusDays(back);
            String day = String.format(Locale.ROOT, "%d%03d", d.getYear(), d.getDayOfYear());
            Grid current = grid;
            if (current != null && current.day.compareTo(day) >= 0) return; // already the newest
            Path kept = cacheDir.resolve("ims-" + day + ".bin.gz");
            if (Files.exists(kept)) {
                try {
                    grid = read(kept, day);
                    return;
                } catch (IOException e) {
                    Files.deleteIfExists(kept);
                }
            }
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, URL, d.getYear(), d.getYear(), d.getDayOfYear()), HEADERS, 300);
            if (r.status() == 404) continue;
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            Grid g = decode(r.body(), day);
            Files.createDirectories(cacheDir);
            write(kept, g);
            // Older days' files are not needed any more.
            try (var files = Files.list(cacheDir)) {
                files.filter(p -> p.getFileName().toString().startsWith("ims-") && !p.equals(kept)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            grid = g;
            System.out.println("[orbis] Observed snow and ice: IMS day " + day + " (" + ATTRIBUTION + ")");
            return;
        }
        throw new IOException("no IMS file in the last four days");
    }

    /** Streams the gzipped text grid: header lines, then N rows of N digits, the bottom row first. */
    private static Grid decode(byte[] gz, String day) throws IOException {
        char[][] rows = new char[N][];
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new java.io.ByteArrayInputStream(gz)), StandardCharsets.US_ASCII), 1 << 20)) {
            String line;
            int fromBottom = 0;
            char[] buf = new char[3 * 64];
            while ((line = in.readLine()) != null && fromBottom < N) {
                if (line.length() != N || !Character.isDigit(line.charAt(0)) || !Character.isDigit(line.charAt(N - 1))) continue;
                int n = 0, col = 0;
                while (col < N) {
                    char c = line.charAt(col);
                    if (c != '1' && c != '3' && c != '4') {
                        col++;
                        continue;
                    }
                    int start = col;
                    while (col < N && line.charAt(col) == c) col++;
                    if (n + 3 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[n++] = (char) start; // columns below 24,577 and classes: unsigned 16 bits
                    buf[n++] = (char) col;
                    buf[n++] = (char) (c - '0');
                }
                rows[N - 1 - fromBottom] = java.util.Arrays.copyOf(buf, n);
                fromBottom++;
            }
            if (fromBottom < N) throw new IOException("only " + fromBottom + " rows");
        }
        return new Grid(day, rows);
    }

    private static void write(Path file, Grid g) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
            out.writeInt(2);
            for (char[] row : g.rows) {
                out.writeInt(row.length);
                for (char v : row) out.writeChar(v);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Grid read(Path file, String day) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != 2) throw new IOException("unknown version");
            char[][] rows = new char[N][];
            for (int r = 0; r < N; r++) {
                int n = in.readInt();
                if (n < 0 || n > 3 * N) throw new IOException("bad row");
                char[] row = new char[n];
                for (int i = 0; i < n; i++) row[i] = in.readChar();
                rows[r] = row;
            }
            return new Grid(day, rows);
        }
    }
}
