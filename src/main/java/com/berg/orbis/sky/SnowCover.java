package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Today's snow on the ground, anywhere: Open-Meteo's current snow depth, temperature and freezing level (weather
 * models, CC BY 4.0) for the centre of each 0.25 degree cell (about 25 km), turned into a snow line, and the depth at
 * a place growing with its height above that line: bare valleys and deep mountains, as in the real season. A
 * model's cell has one snow depth for all its heights, so: where it has snow at its elevation e0, the line lies
 * below it (e0 - depth / rate); where it has none, it lies above e0, at the height where today's air is at
 * freezing (the freezing level less 300 m, or 0.5 C on a 6.5 C/km lapse rate).
 *
 * The rate (metres of snow per metre of height) follows the climate: wet winters pile snow fast with height, dry ones
 * slowly. It comes from the last winter's precipitation (October to April north, April to October south, the whole
 * year near the equator) in Open-Meteo's weather archive for each 0.5 degree cell, scaled so Finse (837 mm) gets
 * 15 cm per 100 m: Denver and Tibet about 4, Zermatt 12, Hokkaido's mountains 22, Mt Rainier 36. Asked once a season.
 *
 * Each of the four nearest cells gives a depth; the place gets their blend. Today's snow is asked again after six
 * hours; both are kept in {@code snow-cache/} so a restart needs no request.
 */
public final class SnowCover {

    static final double CELL = 0.25, RATE_CELL = 0.5;
    /** Snow per metre of height at Finse's 837 mm of winter precipitation (typical of Norwegian winters). */
    static final double NORWAY_RATE = 0.0015, NORWAY_WINTER_MM = 837;
    static final double MIN_RATE = 0.0004, MAX_RATE = 0.004;
    /** No deeper than this (snow to the eaves of a house is plenty for a game). */
    public static final double MAX_DEPTH_M = 3.0;
    private static final long REFRESH_MS = 6 * 3600_000L, RETRY_MS = 10 * 60_000L;
    private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&current=snow_depth,freezing_level_height,temperature_2m";
    private static final String ARCHIVE = "https://archive-api.open-meteo.com/v1/archive?latitude=%.4f&longitude=%.4f"
            + "&start_date=%d-07-01&end_date=%d-06-30&daily=precipitation_sum&timezone=GMT";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");

    private static final Map<Path, SnowCover> SHARED = new HashMap<>();

    /** A cell's snow line (m above sea level), its rate (m of snow per m of height) and when it was asked for. */
    private record Cell(double snowlineM, double rate, long fetchedAt) {
        double depthM(double elevationM) {
            return Math.max(0, Math.min(MAX_DEPTH_M, (elevationM - snowlineM) * rate));
        }
    }

    private final Path cacheDir;
    private final Map<Long, CompletableFuture<Cell>> cells = new ConcurrentHashMap<>();
    private final Map<Long, Long> failedAt = new ConcurrentHashMap<>();
    private final Map<Long, Double> rates = new ConcurrentHashMap<>();
    private final Map<Long, Long> rateFailedAt = new ConcurrentHashMap<>();
    private final Map<Long, Object> rateLocks = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "Orbis-snow");
        t.setDaemon(true);
        return t;
    });

    private SnowCover(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static synchronized SnowCover shared(Path cacheDir) {
        return SHARED.computeIfAbsent(cacheDir, SnowCover::new);
    }

    /**
     * Snow depth in metres at a place and height, waiting up to a few seconds for the cells it needs (chunk
     * generation); NaN when they cannot be had (offline): the caller keeps its climate guess.
     */
    public double depthM(double lat, double lon, double elevationM) {
        return depth(lat, lon, elevationM, 20_000);
    }

    /** The same without waiting (the server thread): NaN until the cells are in, which this starts. */
    public double depthIfKnownM(double lat, double lon, double elevationM) {
        return depth(lat, lon, elevationM, 0);
    }

    private double depth(double lat, double lon, double elevationM, long waitMs) {
        // The four cell centres around the place, and its position between them.
        double fy = lat / CELL - 0.5, fx = lon / CELL - 0.5;
        long y0 = (long) Math.floor(fy), x0 = (long) Math.floor(fx);
        double ty = fy - y0, tx = fx - x0;
        double e = Double.isNaN(elevationM) ? 0 : elevationM;
        double depth = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                Cell c = cell(y0 + dy, x0 + dx, waitMs);
                if (c == null) return Double.NaN;
                depth += c.depthM(e) * (dy == 0 ? 1 - ty : ty) * (dx == 0 ? 1 - tx : tx);
            }
        }
        return depth;
    }

    private Cell cell(long cy, long cx, long waitMs) {
        long key = (cy << 32) ^ (cx & 0xffffffffL);
        CompletableFuture<Cell> f = cells.get(key);
        if (f != null && f.isDone()) {
            Cell c = f.getNow(null);
            if (c != null && System.currentTimeMillis() - c.fetchedAt() > REFRESH_MS) {
                cells.remove(key, f); // stale: ask again, but use the old cell meanwhile
                fetch(key, cy, cx);
            }
            if (c != null) return c;
        }
        f = fetch(key, cy, cx);
        if (f == null) return null;
        if (waitMs <= 0) return f.isDone() ? f.getNow(null) : null;
        try {
            return f.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private CompletableFuture<Cell> fetch(long key, long cy, long cx) {
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
        return cells.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Path file = cacheDir.resolve(cy + "_" + cx + ".txt");
            Cell old = null;
            try {
                if (Files.exists(file)) {
                    String[] p = Files.readString(file, StandardCharsets.UTF_8).trim().split(" ");
                    if (p.length == 3) {
                        old = new Cell(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Long.parseLong(p[2]));
                        if (System.currentTimeMillis() - old.fetchedAt() <= REFRESH_MS) return old;
                    }
                }
                double lat = (cy + 0.5) * CELL, lon = (cx + 0.5) * CELL;
                double rate = rate(lat, lon);
                boolean rateKnown = !Double.isNaN(rate);
                if (!rateKnown) rate = old != null ? old.rate() : NORWAY_RATE;
                OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, URL, lat, lon), HEADERS, 20);
                if (r.status() != 200) throw new IOException("HTTP " + r.status());
                JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
                JsonObject cur = o.getAsJsonObject("current");
                double e0 = number(o, "elevation"), depth = number(cur, "snow_depth"), freezing = number(cur, "freezing_level_height"),
                        temp = number(cur, "temperature_2m");
                if (Double.isNaN(e0)) e0 = 0;
                double line;
                if (depth > 0.005) {
                    line = e0 - depth / rate; // snow at the cell's height: the line lies below it
                } else if (!Double.isNaN(freezing)) {
                    line = Math.max(e0, freezing - 300);
                } else if (!Double.isNaN(temp)) {
                    line = Math.max(e0, e0 + (temp - 0.5) / 0.0065);
                } else {
                    throw new IOException("no snow depth or temperature");
                }
                // Without the climate's rate the cell is asked again in ten minutes.
                long at = rateKnown ? System.currentTimeMillis() : System.currentTimeMillis() - REFRESH_MS + RETRY_MS;
                Cell c = new Cell(line, rate, at);
                Files.createDirectories(cacheDir);
                Files.writeString(file, String.format(Locale.ROOT, "%.1f %.6f %d", c.snowlineM(), c.rate(), c.fetchedAt()), StandardCharsets.UTF_8);
                return c;
            } catch (IOException | InterruptedException | RuntimeException e) {
                // Offline at a refresh: keep the last cell, asked again in ten minutes.
                if (old != null) return new Cell(old.snowlineM(), old.rate(), System.currentTimeMillis() - REFRESH_MS + RETRY_MS);
                cells.remove(k);
                failedAt.put(k, System.currentTimeMillis());
                return null;
            }
        }, POOL));
    }

    /**
     * The climate's rate for the 0.5 degree cell holding this place: from the last full season's (July to June)
     * winter precipitation, kept on disk until the next season is in the archive (August). NaN when not to be had.
     */
    private double rate(double lat, double lon) {
        long ry = (long) Math.floor(lat / RATE_CELL), rx = (long) Math.floor(lon / RATE_CELL);
        long key = (ry << 32) ^ (rx & 0xffffffffL);
        Double known = rates.get(key);
        if (known != null) return known;
        synchronized (rateLocks.computeIfAbsent(key, k -> new Object())) {
            known = rates.get(key);
            if (known != null) return known;
            Long failed = rateFailedAt.get(key);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return Double.NaN;
            LocalDate today = LocalDate.now();
            int season = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1; // the season ending in June
            Path file = cacheDir.resolve("rate-" + ry + "_" + rx + ".txt");
            try {
                if (Files.exists(file)) {
                    String[] p = Files.readString(file, StandardCharsets.UTF_8).trim().split(" ");
                    if (p.length == 2 && Integer.parseInt(p[1]) == season) {
                        double r = Double.parseDouble(p[0]);
                        rates.put(key, r);
                        return r;
                    }
                }
                double clat = (ry + 0.5) * RATE_CELL, clon = (rx + 0.5) * RATE_CELL;
                OrbisHttp.Response resp = OrbisHttp.get(String.format(Locale.ROOT, ARCHIVE, clat, clon, season - 1, season), HEADERS, 60);
                if (resp.status() != 200) throw new IOException("HTTP " + resp.status());
                JsonObject daily = JsonParser.parseString(resp.text()).getAsJsonObject().getAsJsonObject("daily");
                JsonArray days = daily.getAsJsonArray("time"), precip = daily.getAsJsonArray("precipitation_sum");
                double winter = 0;
                boolean tropics = Math.abs(clat) < 15, south = clat < 0;
                for (int i = 0; i < days.size() && i < precip.size(); i++) {
                    if (precip.get(i).isJsonNull()) continue;
                    int m = Integer.parseInt(days.get(i).getAsString().substring(5, 7));
                    boolean cold = tropics || (south ? m >= 4 && m <= 10 : m >= 10 || m <= 4);
                    if (cold) winter += precip.get(i).getAsDouble();
                }
                if (tropics) winter *= 7.0 / 12.0;
                double r = rateFor(winter);
                Files.createDirectories(cacheDir);
                Files.writeString(file, String.format(Locale.ROOT, "%.6f %d", r, season), StandardCharsets.UTF_8);
                rates.put(key, r);
                return r;
            } catch (IOException | InterruptedException | RuntimeException e) {
                rateFailedAt.put(key, System.currentTimeMillis());
                return Double.NaN;
            }
        }
    }

    /** Metres of snow per metre of height for a winter's precipitation (mm), Finse's 837 mm giving 15 cm per 100 m. */
    static double rateFor(double winterMm) {
        double r = NORWAY_RATE * Math.pow(Math.max(0, winterMm) / NORWAY_WINTER_MM, 0.8);
        return Math.max(MIN_RATE, Math.min(MAX_RATE, r));
    }

    private static double number(JsonObject o, String key) {
        if (o == null || !o.has(key)) return Double.NaN;
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? Double.NaN : v.getAsDouble();
    }
}
