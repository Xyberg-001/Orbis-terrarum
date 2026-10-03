package com.berg.orbis.geology;

import com.berg.orbis.net.OrbisHttp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

/**
 * The rock under the whole world, from Macrostrat's geological map (macrostrat.org: some 300 source maps from
 * national and state surveys, merged; CC BY 4.0). Its vector tiles show each place at the most detailed map that
 * suits the zoom level, and how detailed that is varies (1:24 000 in parts of the USA, 1:1 million in Norway, only
 * a world map over parts of Asia), so each place takes the most detailed of zoom 10, 8, 6 and 4 that has a map
 * there. Each tile's rock units are drawn into a 256 x 256 grid of rock codes and their descriptions read with
 * {@link Rocks#classify}; the tiles are kept as downloaded.
 */
public final class MacrostratMap implements Rocks.Source {

    private static final int[] ZOOMS = {10, 8, 6, 4};
    private static final int PX = 256;
    /** No map at this pixel (sea, or none at this zoom): look at the next zoom. */
    private static final byte NONE = -1;
    private static final double MAX_LAT = 85.0511;

    private final Path cacheDir;
    private final String urlTemplate;
    private final Map<Long, byte[]> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, Object> loading = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Orbis-macrostrat");
        t.setDaemon(true);
        return t;
    });
    private volatile long failingUntil;

    /** @param urlTemplate the vector tile URL with {z}, {x} and {y} */
    public MacrostratMap(Path cacheDir, String urlTemplate) {
        this.cacheDir = cacheDir;
        this.urlTemplate = urlTemplate;
    }

    @Override
    public String name() {
        return "macrostrat";
    }

    @Override
    public Path cacheDir() {
        return cacheDir;
    }

    @Override
    public boolean overlaps(double south, double west, double north, double east) {
        return north > -MAX_LAT && south < MAX_LAT;
    }

    /** The most detailed zoom's tiles of the box, in parallel (the coarser ones are few and load when sampled). */
    @Override
    public void prefetch(double south, double west, double north, double east) {
        int z = ZOOMS[0];
        int x0 = tileX(west, z), x1 = tileX(east, z), y0 = tileY(north, z), y1 = tileY(south, z);
        List<Future<?>> jobs = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int kx = x, ky = y;
                jobs.add(pool.submit(() -> tile(z, kx, ky)));
            }
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public Rocks.Rock rockAt(double lat, double lon) {
        if (lat <= -MAX_LAT || lat >= MAX_LAT) return null;
        for (int z : ZOOMS) {
            double n = 1 << z;
            double gx = (lon + 180) / 360 * n * PX;
            double gy = (1 - asinhTan(lat) / Math.PI) / 2 * n * PX;
            int tx = (int) Math.floor(gx / PX), ty = (int) Math.floor(gy / PX);
            byte[] t = tile(z, Math.floorMod(tx, 1 << z), Math.max(0, Math.min((1 << z) - 1, ty)));
            if (t == null) continue;
            int px = Math.max(0, Math.min(PX - 1, (int) (gx - (double) tx * PX)));
            int py = Math.max(0, Math.min(PX - 1, (int) (gy - (double) ty * PX)));
            byte code = t[py * PX + px];
            if (code != NONE) return Rocks.Rock.of(code);
        }
        return null;
    }

    private static double asinhTan(double lat) {
        double t = Math.tan(Math.toRadians(lat));
        return Math.log(t + Math.sqrt(t * t + 1));
    }

    private static int tileX(double lon, int z) {
        return Math.max(0, Math.min((1 << z) - 1, (int) Math.floor((lon + 180) / 360 * (1 << z))));
    }

    private static int tileY(double lat, int z) {
        double l = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        return Math.max(0, Math.min((1 << z) - 1, (int) Math.floor((1 - asinhTan(l) / Math.PI) / 2 * (1 << z))));
    }

    // ------------------------------------------------------------------ tiles

    private byte[] tile(int z, int x, int y) {
        long key = ((long) z << 58) | ((long) x << 29) | y;
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
            byte[] t = load(z, x, y);
            if (t == null) return null;
            synchronized (tiles) {
                tiles.put(key, t);
                if (tiles.size() > 300) tiles.remove(tiles.keySet().iterator().next());
            }
            loading.remove(key);
            return t;
        }
    }

    private byte[] load(int z, int x, int y) {
        Path file = cacheDir.resolve(z + "/" + x + "_" + y + ".mvt");
        try {
            byte[] mvt;
            if (Files.exists(file)) {
                mvt = Files.readAllBytes(file);
            } else {
                if (System.currentTimeMillis() < failingUntil) return null;
                String url = urlTemplate.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
                OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0", "Accept-Encoding", "gzip"), 30);
                if (r.status() == 204 || r.status() == 404) {
                    mvt = new byte[0];                 // nothing mapped in this tile
                } else if (r.status() != 200) {
                    throw new IOException("HTTP " + r.status());
                } else {
                    mvt = gunzip(r.body());
                }
                Files.createDirectories(file.getParent());
                Path part = file.resolveSibling(file.getFileName() + ".part");
                Files.write(part, mvt);
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            return rasterize(gunzip(mvt));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (System.currentTimeMillis() >= failingUntil) {
                System.err.println("[orbis] Geological map (macrostrat.org) unavailable (" + e + "); plain stone for 5 minutes");
            }
            failingUntil = System.currentTimeMillis() + 5 * 60_000L;
            return null;
        }
    }

    private static byte[] gunzip(byte[] b) throws IOException {
        if (b.length < 2 || (b[0] & 0xFF) != 0x1F || (b[1] & 0xFF) != 0x8B) return b;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ vector tiles

    /** The tile's rock units ("units" layer) as rock codes per pixel, {@link #NONE} where no unit lies. */
    static byte[] rasterize(byte[] mvt) {
        byte[] out = new byte[PX * PX];
        Arrays.fill(out, NONE);
        Reader tile = new Reader(mvt, 0, mvt.length);
        while (tile.more()) {
            int tag = tile.tag();
            if (tag >>> 3 == 3 && (tag & 7) == 2) {
                Reader layer = tile.message();
                if (isUnits(layer.copy())) drawLayer(layer, out);
            } else {
                tile.skip(tag & 7);
            }
        }
        return out;
    }

    private static boolean isUnits(Reader layer) {
        while (layer.more()) {
            int tag = layer.tag();
            if (tag >>> 3 == 1 && (tag & 7) == 2) return "units".equals(layer.string());
            layer.skip(tag & 7);
        }
        return false;
    }

    private static void drawLayer(Reader layer, byte[] out) {
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Reader> features = new ArrayList<>();
        int extent = 4096;
        while (layer.more()) {
            int tag = layer.tag(), field = tag >>> 3, wire = tag & 7;
            if (field == 2 && wire == 2) features.add(layer.message());
            else if (field == 3 && wire == 2) keys.add(layer.string());
            else if (field == 4 && wire == 2) values.add(stringValue(layer.message()));
            else if (field == 5 && wire == 0) extent = (int) layer.varint();
            else layer.skip(wire);
        }
        int lith = keys.indexOf("lith"), name = keys.indexOf("name"), descrip = keys.indexOf("descrip");
        Map<String, Rocks.Rock> known = new java.util.HashMap<>();
        for (Reader f : features) {
            long[] tags = null, geometry = null;
            int type = 0;
            while (f.more()) {
                int tag = f.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 2 && wire == 2) tags = f.packed();
                else if (field == 3 && wire == 0) type = (int) f.varint();
                else if (field == 4 && wire == 2) geometry = f.packed();
                else f.skip(wire);
            }
            if (type != 3 || geometry == null) continue;
            String l = null, n = null, d = null;
            if (tags != null) {
                for (int i = 0; i + 1 < tags.length; i += 2) {
                    int k = (int) tags[i], v = (int) tags[i + 1];
                    if (v < 0 || v >= values.size()) continue;
                    if (k == lith) l = values.get(v);
                    else if (k == name) n = values.get(v);
                    else if (k == descrip) d = values.get(v);
                }
            }
            String cacheKey = l + "\u0000" + n + "\u0000" + d;
            Rocks.Rock rock = known.get(cacheKey);
            if (rock == null) known.put(cacheKey, rock = rockOf(l, n, d));
            fill(geometry, extent, (byte) rock.ordinal(), out);
        }
    }

    /** Macrostrat's harmonised lithology first, then the map's own words. */
    static Rocks.Rock rockOf(String lith, String name, String descrip) {
        Rocks.Rock r = lithology(lith);
        if (r == null) r = Rocks.classify(name, null);
        if (r == null) r = Rocks.classify(descrip, null);
        return r == null ? Rocks.Rock.UNKNOWN : r;
    }

    private static final java.util.regex.Pattern SHARE = java.util.regex.Pattern.compile("\\[([0-9.]+)%\\.\\.([0-9.]+)%\\]");

    /**
     * Macrostrat's lithology field: "Major:{gneiss}, Minor{...}" (the major part), or shares like "clay [5.0%..50.0%];
     * impure Carbonate [50.0%..95.0%]" (the largest share that is rock: the rock under the loose ground), or plain words.
     */
    static Rocks.Rock lithology(String lith) {
        if (lith == null || lith.isBlank()) return null;
        if (lith.indexOf('[') < 0) {
            int minor = lith.indexOf("Minor");
            return Rocks.classify(minor > 0 ? lith.substring(0, minor) : lith, null);
        }
        Rocks.Rock best = null;
        double bestShare = -1;
        boolean loose = false;
        for (String part : lith.split(";")) {
            java.util.regex.Matcher m = SHARE.matcher(part);
            double share = m.find() ? (Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(2))) / 2 : 0;
            Rocks.Rock r = Rocks.classify(m.reset().replaceAll(""), null);
            if (r == null) continue;
            if (r == Rocks.Rock.UNKNOWN) {
                loose = true;
            } else if (share > bestShare) {
                best = r;
                bestShare = share;
            }
        }
        return best != null ? best : loose ? Rocks.Rock.UNKNOWN : null;
    }

    private static String stringValue(Reader v) {
        while (v.more()) {
            int tag = v.tag();
            if (tag >>> 3 == 1 && (tag & 7) == 2) return v.string();
            v.skip(tag & 7);
        }
        return null;
    }

    /** Fills a polygon feature's rings, even-odd (holes stay open), sampled at pixel centres. */
    private static void fill(long[] geometry, int extent, byte code, byte[] out) {
        List<double[]> rings = new ArrayList<>();
        double scale = (double) PX / extent;
        int cx = 0, cy = 0, i = 0;
        double[] ring = null;
        int ringLen = 0;
        while (i < geometry.length) {
            int cmd = (int) geometry[i] & 7, count = (int) (geometry[i] >>> 3);
            i++;
            if (cmd == 7) {
                if (ring != null && ringLen >= 6) rings.add(Arrays.copyOf(ring, ringLen));
                ring = null;
                continue;
            }
            for (int c = 0; c < count && i + 1 < geometry.length; c++) {
                cx += zigzag(geometry[i++]);
                cy += zigzag(geometry[i++]);
                if (cmd == 1) {
                    if (ring != null && ringLen >= 6) rings.add(Arrays.copyOf(ring, ringLen));
                    ring = new double[16];
                    ringLen = 0;
                }
                if (ring == null) continue;
                if (ringLen + 2 > ring.length) ring = Arrays.copyOf(ring, ring.length * 2);
                ring[ringLen++] = cx * scale;
                ring[ringLen++] = cy * scale;
            }
        }
        if (rings.isEmpty()) return;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] r : rings) {
            for (int k = 1; k < r.length; k += 2) {
                minY = Math.min(minY, r[k]);
                maxY = Math.max(maxY, r[k]);
            }
        }
        int row0 = Math.max(0, (int) Math.floor(minY - 0.5)), row1 = Math.min(PX - 1, (int) Math.ceil(maxY - 0.5));
        double[] xs = new double[64];
        for (int py = row0; py <= row1; py++) {
            double y = py + 0.5;
            int n = 0;
            for (double[] r : rings) {
                int pts = r.length / 2;
                for (int a = 0, b = pts - 1; a < pts; b = a++) {
                    double ya = r[a * 2 + 1], yb = r[b * 2 + 1];
                    if ((ya > y) == (yb > y)) continue;
                    double xa = r[a * 2], xb = r[b * 2];
                    if (n == xs.length) xs = Arrays.copyOf(xs, n * 2);
                    xs[n++] = xa + (y - ya) / (yb - ya) * (xb - xa);
                }
            }
            if (n < 2) continue;
            Arrays.sort(xs, 0, n);
            for (int k = 0; k + 1 < n; k += 2) {
                int from = Math.max(0, (int) Math.ceil(xs[k] - 0.5)), to = Math.min(PX - 1, (int) Math.ceil(xs[k + 1] - 0.5) - 1);
                for (int px = from; px <= to; px++) out[py * PX + px] = code;
            }
        }
    }

    private static int zigzag(long v) {
        return (int) ((v >>> 1) ^ -(v & 1));
    }

    /** Just enough of the protobuf wire format for a vector tile. */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        Reader copy() {
            return new Reader(b, pos, end);
        }

        boolean more() {
            return pos < end;
        }

        int tag() {
            return (int) varint();
        }

        long varint() {
            long r = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte c = b[pos++];
                r |= (long) (c & 0x7F) << shift;
                if (c >= 0) return r;
            }
            throw new IllegalStateException("varint too long");
        }

        int length() {
            int n = (int) varint();
            if (n < 0 || pos + n > end) throw new IllegalStateException("bad length " + n);
            return n;
        }

        Reader message() {
            int n = length();
            Reader m = new Reader(b, pos, pos + n);
            pos += n;
            return m;
        }

        String string() {
            int n = length();
            String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        long[] packed() {
            int n = length(), stop = pos + n, count = 0;
            long[] out = new long[Math.max(4, n)];
            while (pos < stop) out[count++] = varint();
            return Arrays.copyOf(out, count);
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> varint();
                case 1 -> pos += 8;
                case 2 -> {
                    int n = length();
                    pos += n;
                }
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("unsupported wire type " + wire);
            }
        }
    }
}
