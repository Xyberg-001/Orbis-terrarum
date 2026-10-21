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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Where snow lies and the sea is frozen today south of the equator, as seen: NOAA's Global Multisensor Automated Snow
 * and Ice map (GMASI, the "Autosnow" 4 km product, public domain), made every day from the visible, infrared and
 * microwave channels of several satellites, with no gaps under cloud. IMS, which Orbis uses in the north, stops at
 * the equator; this covers the Andes, Patagonia, New Zealand, Lesotho, the Australian Alps and the Antarctic sea ice.
 * The day's map is one 0.04 degree byte grid of the whole planet (9,000 x 4,500; 0 water, 1 land, 2 snow, 3 ice),
 * packed with Unix compress (350 kB), posted early the next morning (UTC); its southern half is kept as runs of
 * water, snow and ice per row, on disk for a restart.
 */
final class GmasiSnow {
    public static final String ATTRIBUTION = "NOAA/NESDIS GMASI daily global snow and ice map, 4 km (public domain)";

    private static final String URL = "https://www.star.nesdis.noaa.gov/pub/smcd/emb/snow/binary/multisensor/global/%d/gl_4km_snow_ice_map_%d%03d.Z";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final int COLS = 9000, ROWS = 4500, FIRST_ROW = 2225; // from 1 N, so the band up to IMS's edge is covered
    private static final double CELL = 0.04;
    private static final long CHECK_MS = 6 * 3600_000L, RETRY_MS = 30 * 60_000L;

    /** Per row from FIRST_ROW down: runs of {start column, end column (exclusive), class} for water, snow and ice. */
    private record Grid(String day, char[][] rows) {
    }

    private final Path cacheDir;
    private volatile Grid grid;
    private volatile long checkedAt;
    private volatile CompletableFuture<Void> loading;

    GmasiSnow(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * What a place is today ({@link ObservedSnow}'s WATER, ICE or SNOW), or UNKNOWN (bare land, north of 1 N, or not
     * loaded yet; this starts the load). Bare land is not told: a 4 km cell seen bare can hold a snowy peak, so the
     * model's depth stands there (IMS's 1 km cells take 30 cm off it).
     */
    int classAt(double lat, double lon) {
        if (lat >= 1) return ObservedSnow.UNKNOWN;
        if (grid == null || System.currentTimeMillis() - checkedAt > CHECK_MS) refresh();
        Grid g = grid;
        if (g == null) return ObservedSnow.UNKNOWN;
        int row = (int) Math.floor((90 - lat) / CELL) - FIRST_ROW;
        int col = (int) Math.floor((lon + 180) / CELL);
        if (row < 0 || row >= g.rows.length || col < 0 || col >= COLS) return ObservedSnow.UNKNOWN;
        char[] runs = g.rows[row];
        int lo = 0, hi = runs.length / 3 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int s = runs[mid * 3], e = runs[mid * 3 + 1];
            if (col < s) hi = mid - 1;
            else if (col >= e) lo = mid + 1;
            else return runs[mid * 3 + 2];
        }
        return ObservedSnow.UNKNOWN;
    }

    private synchronized void refresh() {
        if (loading != null && !loading.isDone()) return;
        checkedAt = System.currentTimeMillis();
        loading = CompletableFuture.runAsync(() -> {
            try {
                load();
            } catch (Exception e) {
                checkedAt = System.currentTimeMillis() - CHECK_MS + RETRY_MS;
                System.err.println("[orbis] Observed snow in the south (GMASI) unavailable: " + e.getMessage());
            }
        });
    }

    private void load() throws IOException, InterruptedException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int back = 1; back <= 4; back++) {
            LocalDate d = today.minusDays(back);
            String day = String.format(Locale.ROOT, "%d%03d", d.getYear(), d.getDayOfYear());
            Grid current = grid;
            if (current != null && current.day.compareTo(day) >= 0) return;
            Path kept = cacheDir.resolve("gmasi-" + day + ".bin.gz");
            if (Files.exists(kept)) {
                try {
                    grid = read(kept, day);
                    return;
                } catch (IOException e) {
                    Files.deleteIfExists(kept);
                }
            }
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, URL, d.getYear(), d.getYear(), d.getDayOfYear()), HEADERS, 120);
            if (r.status() == 404) continue;
            if (r.status() != 200 || r.body() == null) throw new IOException("HTTP " + r.status());
            Grid g = decode(UnixCompress.decompress(r.body(), COLS * ROWS), day);
            Files.createDirectories(cacheDir);
            write(kept, g);
            try (var files = Files.list(cacheDir)) {
                files.filter(p -> p.getFileName().toString().startsWith("gmasi-") && !p.equals(kept)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            grid = g;
            System.out.println("[orbis] Observed snow and ice in the south: GMASI day " + day + " (" + ATTRIBUTION + ")");
            return;
        }
        throw new IOException("no GMASI map in the last four days");
    }

    /** Runs per row (class 0 water, 2 snow, 3 ice as WATER, SNOW, ICE; land between them) for the rows from FIRST_ROW. */
    static Grid decode(byte[] map, String day) throws IOException {
        if (map.length != COLS * ROWS) throw new IOException("GMASI map of " + map.length + " bytes");
        char[][] rows = new char[ROWS - FIRST_ROW][];
        char[] buf = new char[3 * 64];
        for (int r = FIRST_ROW; r < ROWS; r++) {
            int base = r * COLS, n = 0, col = 0;
            while (col < COLS) {
                int c = map[base + col];
                if (c == 1) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < COLS && map[base + col] == c) col++;
                int cls = c == 0 ? ObservedSnow.WATER : c == 2 ? ObservedSnow.SNOW : c == 3 ? ObservedSnow.ICE : ObservedSnow.LAND;
                if (cls == ObservedSnow.LAND) continue;
                if (n + 3 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = (char) start;
                buf[n++] = (char) col;
                buf[n++] = (char) cls;
            }
            rows[r - FIRST_ROW] = java.util.Arrays.copyOf(buf, n);
        }
        return new Grid(day, rows);
    }

    private static void write(Path file, Grid g) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
            out.writeInt(1);
            out.writeInt(g.rows.length);
            for (char[] row : g.rows) {
                out.writeInt(row.length);
                for (char v : row) out.writeChar(v);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Grid read(Path file, String day) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != 1 || in.readInt() != ROWS - FIRST_ROW) throw new IOException("unknown version");
            char[][] rows = new char[ROWS - FIRST_ROW][];
            for (int r = 0; r < rows.length; r++) {
                int n = in.readInt();
                if (n < 0 || n > 3 * COLS) throw new IOException("bad row");
                char[] row = new char[n];
                for (int i = 0; i < n; i++) row[i] = in.readChar();
                rows[r] = row;
            }
            return new Grid(day, rows);
        }
    }
}
