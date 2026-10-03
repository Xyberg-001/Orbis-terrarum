package com.berg.orbis.geology;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.berg.orbis.geology.Rocks.Rock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rock under Norway, from the Geological Survey of Norway's bedrock map (NGU, Nasjonal berggrunnsdatabase,
 * main rock types at the most detailed scale available at each place, down to 1:50 000; open under NLOD / CC BY 4.0).
 * The map service only draws the map, so it is fetched as pictures (1.1 km tiles, about 4 m a pixel) and each colour
 * is identified once by asking the service which rock lies under a pixel of it; the answers are kept, so a place
 * costs one picture and the country a few hundred questions in all. Boundary lines and labels (grey) and the soft
 * edges between two colours take the rock of the pixels around them.
 */
public final class BedrockMap implements Rocks.Source {

    private static final double TILE_LAT = 0.01, TILE_LON = 0.02;
    private static final int PX = 256;
    private static final String LAYER = "Berggrunn_sammenstilt_hovedbergarter";
    /** Mainland Norway with its islands (the service has nothing outside). */
    private static final double SOUTH = 57.8, NORTH = 71.4, WEST = 4.0, EAST = 31.5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ROCK_NAME = Pattern.compile("hovedbergart_tekst = '([^']*)'");

    private final Path cacheDir;
    private final String serviceUrl;
    private final Map<Long, byte[]> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, String> colourNames;
    private final Map<Long, Object> loading = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Orbis-bedrock");
        t.setDaemon(true);
        return t;
    });
    private volatile long failingUntil;

    public BedrockMap(Path cacheDir, String serviceUrl) {
        this.cacheDir = cacheDir;
        this.serviceUrl = serviceUrl;
        this.colourNames = new ConcurrentHashMap<>(loadColourNames());
    }

    @Override
    public String name() {
        return "ngu-bedrock";
    }

    @Override
    public Path cacheDir() {
        return cacheDir;
    }

    public static boolean covers(double lat, double lon) {
        return lat >= SOUTH && lat <= NORTH && lon >= WEST && lon <= EAST;
    }

    @Override
    public boolean overlaps(double south, double west, double north, double east) {
        return north >= SOUTH && south <= NORTH && east >= WEST && west <= EAST;
    }

    /** Loads every tile of a box in parallel (a region asks for its own before it samples column by column). */
    @Override
    public void prefetch(double south, double west, double north, double east) {
        if (!overlaps(south, west, north, east)) return;
        List<Future<?>> jobs = new ArrayList<>();
        for (long ty = (long) Math.floor(south / TILE_LAT); ty <= (long) Math.floor(north / TILE_LAT); ty++) {
            for (long tx = (long) Math.floor(west / TILE_LON); tx <= (long) Math.floor(east / TILE_LON); tx++) {
                long ky = ty, kx = tx;
                jobs.add(pool.submit(() -> tile(ky, kx)));
            }
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
    }

    /** The rock at a place, or null outside Norway, at sea, or while the service cannot be reached (the next map's turn). */
    @Override
    public Rock rockAt(double lat, double lon) {
        if (!covers(lat, lon)) return null;
        long ty = (long) Math.floor(lat / TILE_LAT), tx = (long) Math.floor(lon / TILE_LON);
        byte[] t = tile(ty, tx);
        if (t == null) return null;
        int px = (int) Math.min(PX - 1, (lon / TILE_LON - tx) * PX);
        int py = (int) Math.min(PX - 1, (1 - (lat / TILE_LAT - ty)) * PX);
        byte code = t[py * PX + px];
        return code == 0 ? null : Rock.of(code);
    }

    // ------------------------------------------------------------------ tiles

    private byte[] tile(long ty, long tx) {
        long key = ty * 100_000L + tx;
        synchronized (tiles) {
            byte[] t = tiles.get(key);
            if (t != null) return t;
        }
        Object lock = loading.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            synchronized (tiles) {
                byte[] t = tiles.get(key);
                if (t != null) return t;
            }
            byte[] t = load(ty, tx);
            if (t == null) return null;
            synchronized (tiles) {
                tiles.put(key, t);
                if (tiles.size() > 400) tiles.remove(tiles.keySet().iterator().next());
            }
            loading.remove(key);
            return t;
        }
    }

    private double[] bbox(long ty, long tx) {
        return new double[]{tx * TILE_LON, ty * TILE_LAT, (tx + 1) * TILE_LON, (ty + 1) * TILE_LAT};
    }

    private String url(long ty, long tx, String request) {
        double[] b = bbox(ty, tx);
        return String.format(Locale.ROOT, "%s?service=WMS&version=1.3.0&layers=%s&styles=&crs=CRS:84&bbox=%.6f,%.6f,%.6f,%.6f&width=%d&height=%d&request=%s",
                serviceUrl, LAYER, b[0], b[1], b[2], b[3], PX, PX, request);
    }

    private byte[] load(long ty, long tx) {
        Path file = cacheDir.resolve(ty + "_" + tx + ".png");
        try {
            byte[] png;
            if (Files.exists(file)) {
                png = Files.readAllBytes(file);
            } else {
                if (System.currentTimeMillis() < failingUntil) return null;
                OrbisHttp.Response r = OrbisHttp.get(url(ty, tx, "GetMap&format=image/png"), Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 30);
                if (r.status() != 200 || r.header("content-type") == null || !r.header("content-type").startsWith("image/")) {
                    throw new IOException("HTTP " + r.status());
                }
                png = r.body();
                Files.createDirectories(cacheDir);
                Files.write(file, png);
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return null;
            return classify(img, ty, tx);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (System.currentTimeMillis() >= failingUntil) {
                System.err.println("[orbis] Bedrock map (geo.ngu.no) unavailable (" + e + "); plain stone for 5 minutes");
            }
            failingUntil = System.currentTimeMillis() + 5 * 60_000L;
            return null;
        }
    }

    /** Rock code per pixel: known colours directly, new common colours identified by asking the service, the rest filled in. */
    private byte[] classify(BufferedImage img, long ty, long tx) {
        int w = Math.min(PX, img.getWidth()), h = Math.min(PX, img.getHeight());
        int[] all = com.berg.orbis.dem.TileImages.argbPixels(img);
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(all, y * img.getWidth(), argb, y * w, w);
        Map<Integer, Integer> counts = new HashMap<>();
        Map<Integer, Integer> firstAt = new HashMap<>();
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            if ((c >>> 24) < 128 || grey(c)) continue;
            int rgb = c & 0xFFFFFF;
            counts.merge(rgb, 1, Integer::sum);
            firstAt.putIfAbsent(rgb, i);
        }
        // Colours covering a real area of the tile that are new: which rock are they?
        for (var e : counts.entrySet()) {
            if (e.getValue() < 30 || colourNames.containsKey(key(e.getKey()))) continue;
            int at = firstAt.get(e.getKey());
            String name = identify(ty, tx, at % w, at / w);
            if (name != null) {
                colourNames.put(key(e.getKey()), name);
                saveColourNames();
            }
        }
        List<int[]> known = new ArrayList<>(); // {rgb, rock}
        for (int rgb : counts.keySet()) {
            String name = colourNames.get(key(rgb));
            if (name != null) known.add(new int[]{rgb, rockFor(name).ordinal()});
        }
        byte[] out = new byte[PX * PX];
        byte[] fill = new byte[PX * PX];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int i = y * PX + x;
                if ((c >>> 24) < 128) continue;       // no bedrock mapped here (sea)
                if (grey(c)) {
                    fill[i] = 1;                       // a line or a label: the rock around it
                    continue;
                }
                String name = colourNames.get(key(c & 0xFFFFFF));
                if (name != null) {
                    out[i] = (byte) rockFor(name).ordinal();
                    continue;
                }
                // A soft edge between two areas: the nearest of the tile's known colours.
                int best = -1, bestD = 60 * 60;
                for (int[] k : known) {
                    int d = dist2(c, k[0]);
                    if (d < bestD) {
                        bestD = d;
                        best = k[1];
                    }
                }
                if (best >= 0) out[i] = (byte) best;
                else fill[i] = 1;
            }
        }
        // Fill lines and labels from their neighbours, a pixel ring at a time.
        for (int pass = 0; pass < 8; pass++) {
            boolean any = false;
            byte[] next = out.clone();
            for (int y = 0; y < PX; y++) {
                for (int x = 0; x < PX; x++) {
                    int i = y * PX + x;
                    if (fill[i] == 0) continue;
                    int[] votes = new int[Rock.values().length];
                    int bestV = 0, best = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= PX || ny >= PX) continue;
                            int j = ny * PX + nx;
                            if (fill[j] != 0 || out[j] == 0) continue;
                            if (++votes[out[j]] > bestV) {
                                bestV = votes[out[j]];
                                best = out[j];
                            }
                        }
                    }
                    if (best > 0) {
                        next[i] = (byte) best;
                        fill[i] = 0;
                        any = true;
                    }
                }
            }
            out = next;
            if (!any) break;
        }
        return out;
    }

    /** The service's name for the main rock at a pixel of a tile (Norwegian), or null. */
    private String identify(long ty, long tx, int i, int j) {
        try {
            String url = url(ty, tx, "GetFeatureInfo&query_layers=" + LAYER + "&info_format=text/plain&feature_count=1&i=" + i + "&j=" + j);
            OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 30);
            if (r.status() != 200) return null;
            Matcher m = ROCK_NAME.matcher(new String(r.body(), StandardCharsets.UTF_8));
            return m.find() && !m.group(1).isBlank() ? m.group(1).trim() : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The block for one of NGU's main rock names. Gneisses and migmatites (most of Norway) are stone; the order of
     * the tests matters: a "granittisk gneis" is a gneiss, a "kvartsskifer" a quartzite.
     */
    static Rock rockFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("gneis") || n.contains("migmatitt")) return Rock.GNEISS;
        if (n.contains("marmor") || n.contains("kalkstein") || n.contains("dolomitt") || n.contains("kalkspat")) return Rock.MARBLE;
        if (n.contains("kvarts") && !n.contains("dioritt") || n.contains("anortositt")) return Rock.DIORITE;
        if (n.contains("gabbro") || n.contains("norritt") || n.contains("amfibolitt") || n.contains("grønnstein") || n.contains("basalt")
                || n.contains("diabas") || n.contains("eklogitt") || n.contains("peridotitt") || n.contains("serpentinitt")
                || n.contains("pyroksenitt") || n.contains("ultramafisk") || n.contains("hornblenditt") || n.contains("dunitt")) return Rock.MAFIC;
        if (n.contains("skifer") || n.contains("fyllitt") || n.contains("gråvakke") || n.contains("siltstein") || n.contains("leirstein")
                || n.contains("tuff")) return Rock.SCHIST;
        if (n.contains("sandstein") || n.contains("arkose")) return Rock.SANDSTONE;
        if (n.contains("dioritt") || n.contains("tonalitt") || n.contains("trondhjemitt")) return Rock.DIORITE;
        if (n.contains("granitt") || n.contains("granodioritt") || n.contains("monzonitt") || n.contains("mangeritt") || n.contains("syenitt")
                || n.contains("charnockitt") || n.contains("pegmatitt") || n.contains("aplitt") || n.contains("larvikitt")) return Rock.GRANITE;
        if (n.contains("ryolitt") || n.contains("dacitt") || n.contains("andesitt") || n.contains("porfyr") || n.contains("vulkan")
                || n.contains("konglomerat") || n.contains("breksje")) return Rock.VOLCANIC;
        return Rock.GNEISS;
    }

    private static boolean grey(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.abs(r - g) < 6 && Math.abs(g - b) < 6 && Math.abs(r - b) < 6;
    }

    private static int dist2(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF), dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF), db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static String key(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private Map<String, String> loadColourNames() {
        try {
            Path f = cacheDir.resolve("colours.json");
            if (Files.exists(f)) {
                Map<String, String> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) return m;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new HashMap<>();
    }

    private synchronized void saveColourNames() {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve("colours.json"), GSON.toJson(new java.util.TreeMap<>(colourNames)), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
