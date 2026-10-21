package com.berg.orbis.landcover;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A whole-planet 8-bit GeoTIFF on a web server in square tiles (TIFF or BigTIFF, deflate, no predictor), in lat/lon:
 * Copernicus' global forest type map (100 m, 256-pixel tiles, 1 GB). The tile tables (783 000 tiles) are read a row of
 * tiles at a time and kept; each tile is fetched once by a range request and kept as it came; a few hundred decoded tiles
 * stay in memory.
 */
final class TileTiff {
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; forest types)");

    private final String url;
    private final Path cacheDir;
    private volatile Layout layout;
    private final Map<Integer, long[][]> tableRows = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> decoded = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
            return size() > 160;
        }
    });

    private record Layout(boolean big, int width, int height, int tileW, int tileH, int across, double x0, double y0, double dx, double dy,
                          long offsetsAt, long countsAt, int entry, int nodata) {
    }

    TileTiff(String url, Path cacheDir) {
        this.url = url;
        this.cacheDir = cacheDir;
    }

    /** The value at a place, or -1 off the map, where it has none, or when its tile cannot be read (the exception is passed on). */
    int at(double lat, double lon) throws IOException, InterruptedException {
        Layout l = layout();
        int px = (int) Math.floor((lon - l.x0) / l.dx), py = (int) Math.floor((l.y0 - lat) / l.dy);
        if (px < 0 || py < 0 || px >= l.width || py >= l.height) return -1;
        int tx = px / l.tileW, ty = py / l.tileH;
        byte[] tile = tile(l, ty, tx);
        int v = tile[(py - ty * l.tileH) * l.tileW + (px - tx * l.tileW)] & 0xFF;
        return v == l.nodata ? -1 : v;
    }

    /** Loads the tiles a box needs (so sampling it afterwards needs no network). */
    void prefetch(double south, double west, double north, double east) throws IOException, InterruptedException {
        Layout l = layout();
        int ty0 = Math.max(0, (int) Math.floor((l.y0 - north) / l.dy) / l.tileH), ty1 = Math.min(l.height - 1, (int) Math.floor((l.y0 - south) / l.dy)) / l.tileH;
        int tx0 = Math.max(0, (int) Math.floor((west - l.x0) / l.dx) / l.tileW), tx1 = Math.min(l.width - 1, (int) Math.floor((east - l.x0) / l.dx)) / l.tileW;
        for (int ty = ty0; ty <= ty1; ty++) for (int tx = tx0; tx <= tx1; tx++) tile(l, ty, tx);
    }

    private byte[] tile(Layout l, int ty, int tx) throws IOException, InterruptedException {
        long key = (long) ty * l.across + tx;
        byte[] t = decoded.get(key);
        if (t != null) return t;
        Path file = cacheDir.resolve("tiles").resolve(String.valueOf(ty)).resolve(tx + ".z");
        byte[] raw = null;
        if (Files.exists(file)) raw = Files.readAllBytes(file);
        long[][] row = tableRow(l, ty);
        long offset = row[0][tx], count = row[1][tx];
        if (count == 0) {
            t = new byte[l.tileW * l.tileH];
            java.util.Arrays.fill(t, (byte) l.nodata);
        } else {
            if (raw == null || raw.length != count) {
                raw = range(offset, count);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
                Files.write(tmp, raw);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                t = inflate(raw, l.tileW * l.tileH);
            } catch (IOException e) {
                Files.deleteIfExists(file);
                throw e;
            }
        }
        decoded.put(key, t);
        return t;
    }

    /** {offsets, byte counts} of one row of tiles, kept on disk once read. */
    private long[][] tableRow(Layout l, int ty) throws IOException, InterruptedException {
        long[][] t = tableRows.get(ty);
        if (t != null) return t;
        Path file = cacheDir.resolve("table").resolve(ty + ".bin");
        int n = l.across;
        if (Files.exists(file) && Files.size(file) == 16L * n) {
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file));
            t = new long[2][n];
            for (int i = 0; i < n; i++) t[0][i] = b.getLong();
            for (int i = 0; i < n; i++) t[1][i] = b.getLong();
        } else {
            long first = (long) ty * n;
            ByteBuffer o = ByteBuffer.wrap(range(l.offsetsAt + first * l.entry, (long) n * l.entry)).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer c = ByteBuffer.wrap(range(l.countsAt + first * l.entry, (long) n * l.entry)).order(ByteOrder.LITTLE_ENDIAN);
            t = new long[2][n];
            for (int i = 0; i < n; i++) {
                t[0][i] = l.entry == 8 ? o.getLong(i * 8) : o.getInt(i * 4) & 0xFFFFFFFFL;
                t[1][i] = l.entry == 8 ? c.getLong(i * 8) : c.getInt(i * 4) & 0xFFFFFFFFL;
            }
            ByteBuffer out = ByteBuffer.allocate(16 * n);
            for (int i = 0; i < n; i++) out.putLong(t[0][i]);
            for (int i = 0; i < n; i++) out.putLong(t[1][i]);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            Files.write(tmp, out.array());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        tableRows.put(ty, t);
        return t;
    }

    private byte[] range(long offset, long length) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            try {
                Map<String, String> h = new HashMap<>(HEADERS);
                h.put("Range", "bytes=" + offset + "-" + (offset + length - 1));
                OrbisHttp.Response r = OrbisHttp.get(url, h, 60);
                if (r.status() == 206 && r.body().length == length) return r.body();
                last = new IOException("HTTP " + r.status() + " (" + r.body().length + " of " + length + " bytes)");
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1500L << attempt);
        }
        throw last;
    }

    private static byte[] inflate(byte[] raw, int expected) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(raw);
        byte[] out = new byte[expected];
        try {
            int off = 0;
            while (off < expected && !inf.finished()) {
                int k = inf.inflate(out, off, expected - off);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                off += k;
            }
            if (off < expected) throw new IOException("short tile (" + off + " of " + expected + " bytes)");
        } catch (DataFormatException e) {
            throw new IOException("bad deflate tile: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
        return out;
    }

    // ------------------------------------------------------------------ layout

    private Layout layout() throws IOException, InterruptedException {
        Layout l = layout;
        if (l != null) return l;
        synchronized (this) {
            if (layout != null) return layout;
            Path file = cacheDir.resolve("layout.txt");
            try {
                if (Files.exists(file)) l = load(file);
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] " + file + " unreadable, reading the map's layout again: " + e.getMessage());
            }
            if (l == null) {
                l = readHeader();
                Files.createDirectories(cacheDir);
                save(l, file);
            }
            layout = l;
            return l;
        }
    }

    private Layout readHeader() throws IOException, InterruptedException {
        ByteBuffer head = ByteBuffer.wrap(range(0, 16)).order(ByteOrder.LITTLE_ENDIAN);
        if (head.get(0) != 'I') throw new IOException("not a little-endian TIFF");
        boolean big = head.getShort(2) == 43;
        long ifd = big ? head.getLong(8) : head.getInt(4) & 0xFFFFFFFFL;
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12;
        ByteBuffer cnt = ByteBuffer.wrap(range(ifd, countSize)).order(ByteOrder.LITTLE_ENDIAN);
        long n = big ? cnt.getLong(0) : cnt.getShort(0) & 0xFFFF;
        ByteBuffer e = ByteBuffer.wrap(range(ifd + countSize, n * entrySize)).order(ByteOrder.LITTLE_ENDIAN);
        int width = 0, height = 0, bits = 8, compression = 1, predictor = 1, tileW = 0, tileH = 0, nodata = 255, entry = big ? 8 : 4;
        long offsetsAt = -1, countsAt = -1;
        double[] scale = null, tie = null;
        for (int i = 0; i < n; i++) {
            int p = i * entrySize;
            int tag = e.getShort(p) & 0xFFFF, type = e.getShort(p + 2) & 0xFFFF;
            long count = big ? e.getLong(p + 4) : e.getInt(p + 4) & 0xFFFFFFFFL;
            int valuePos = p + (big ? 12 : 8);
            int size = switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11 -> 4;
                default -> 8;
            };
            boolean inline = size * count <= (big ? 8 : 4);
            long at = inline ? -1 : big ? e.getLong(valuePos) : e.getInt(valuePos) & 0xFFFFFFFFL;
            ByteBuffer data = inline ? e.slice(valuePos, big ? 8 : 4).order(ByteOrder.LITTLE_ENDIAN) : null;
            switch (tag) {
                case 256 -> width = (int) num(data, type);
                case 257 -> height = (int) num(data, type);
                case 258 -> bits = (int) num(data, type);
                case 259 -> compression = (int) num(data, type);
                case 317 -> predictor = (int) num(data, type);
                case 322 -> tileW = (int) num(data, type);
                case 323 -> tileH = (int) num(data, type);
                case 324 -> {
                    offsetsAt = at;
                    entry = size;
                }
                case 325 -> countsAt = at;
                case 33550 -> {
                    ByteBuffer b = ByteBuffer.wrap(range(at, 24)).order(ByteOrder.LITTLE_ENDIAN);
                    scale = new double[]{b.getDouble(0), b.getDouble(8)};
                }
                case 33922 -> {
                    ByteBuffer b = ByteBuffer.wrap(range(at, 48)).order(ByteOrder.LITTLE_ENDIAN);
                    tie = new double[]{b.getDouble(24), b.getDouble(32)};
                }
                case 42113 -> {
                    byte[] b = new byte[(int) count];
                    if (inline) data.get(0, b);
                    else b = range(at, count);
                    String text = new String(b, StandardCharsets.US_ASCII).replace("\0", "").trim();
                    if (!text.isEmpty()) nodata = (int) Double.parseDouble(text);
                }
                default -> { }
            }
        }
        if (bits != 8 || compression != 8 || predictor != 1 || tileW <= 0 || tileH <= 0 || offsetsAt < 0 || countsAt < 0 || scale == null || tie == null) {
            throw new IOException("unexpected layout (bits " + bits + ", compression " + compression + ", predictor " + predictor + ")");
        }
        int across = (width + tileW - 1) / tileW;
        return new Layout(big, width, height, tileW, tileH, across, tie[0], tie[1], scale[0], scale[1], offsetsAt, countsAt, entry, nodata);
    }

    private static long num(ByteBuffer b, int type) {
        return switch (type) {
            case 1 -> b.get(0) & 0xFF;
            case 3 -> b.getShort(0) & 0xFFFF;
            case 4 -> b.getInt(0) & 0xFFFFFFFFL;
            default -> b.getLong(0);
        };
    }

    private static void save(Layout l, Path file) throws IOException {
        String text = String.join("\n", "1", String.valueOf(l.big), String.valueOf(l.width), String.valueOf(l.height), String.valueOf(l.tileW),
                String.valueOf(l.tileH), String.valueOf(l.x0), String.valueOf(l.y0), String.valueOf(l.dx), String.valueOf(l.dy),
                String.valueOf(l.offsetsAt), String.valueOf(l.countsAt), String.valueOf(l.entry), String.valueOf(l.nodata));
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        Files.writeString(tmp, text);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Layout load(Path file) throws IOException {
        String[] v = Files.readString(file).trim().split("\n");
        if (v.length != 14 || !v[0].equals("1")) throw new IOException("unknown layout file");
        int width = Integer.parseInt(v[2]), tileW = Integer.parseInt(v[4]);
        return new Layout(Boolean.parseBoolean(v[1]), width, Integer.parseInt(v[3]), tileW, Integer.parseInt(v[5]), (width + tileW - 1) / tileW,
                Double.parseDouble(v[6]), Double.parseDouble(v[7]), Double.parseDouble(v[8]), Double.parseDouble(v[9]),
                Long.parseLong(v[10]), Long.parseLong(v[11]), Integer.parseInt(v[12]), Integer.parseInt(v[13]));
    }
}
