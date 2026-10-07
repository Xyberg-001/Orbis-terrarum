package com.berg.orbis.dem;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Bare-earth terrain where no national survey exists: GEDTM30 (OpenLandMap, CC BY 4.0), a 30 m model of the ground
 * itself, made from Copernicus GLO-30 with trees and buildings taken out by machine learning on ICESat-2 and GEDI
 * laser heights. Copernicus is a surface model: it measures the tops of forests and roofs, so outside the lidar
 * countries every forest floor stood 5 to 25 m too high (measured against lidar on 4 Oct 2026: Oregon's Coast Range
 * 18.7 m, Sihlwald 24.5 m; GEDTM30 1.8 m and 10.1 m).
 *
 * The model is one 432 GB cloud-optimised GeoTIFF (EPSG:4326, EGM2008 heights like Copernicus, 2048 x 2048 blocks,
 * deflate with the integer predictor). Only the blocks a world touches are read, with range requests in 128 kB pieces,
 * 16 at a time, and kept on disk as they came. The host limits each connection, not the total: one piece at a time
 * came at 26-63 KB/s from India, 16 at a time at 300-800 KB/s (5 Oct 2026), so a block of a few MB takes seconds
 * instead of minutes (a new place's first terrain waited about 4 minutes for it).
 */
public final class BareEarth {

    public static final String URL = "https://s3.opengeohub.org/global/dtm/v1.2/gedtm_rf_m_30m_s_20060101_20151231_go_epsg.4326.3855_v1.2.tif";
    public static final String ATTRIBUTION = "GEDTM30 v1.2 (OpenLandMap / OpenGeoHub, CC BY 4.0): bare-earth terrain where no national survey exists";

    private static final int PIECE = 128 << 10, PARALLEL = 16;
    /** The range requests of a block's pieces, all at once up to PARALLEL. */
    private static final java.util.concurrent.ExecutorService RANGES = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL, r -> {
        Thread t = new Thread(r, "Orbis-bare-earth-range");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0");

    private final String url;
    private final Path dir;
    private volatile Layout layout;
    private final Map<Long, CompletableFuture<Block>> loading = new ConcurrentHashMap<>();
    private final Map<Long, Block> blocks = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, long[][]> rows = new ConcurrentHashMap<>();
    /** Its own threads: a block is fetched while terrain tiles wait on the tile threads, which must not be the ones fetching it. */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Orbis-bare-earth");
        t.setDaemon(true);
        return t;
    });

    public BareEarth(Path cacheDir) {
        this(cacheDir, URL);
    }

    public BareEarth(Path cacheDir, String url) {
        this.url = url;
        this.dir = cacheDir;
    }

    /** Where the image is and how it is stored (read once from the file's header, then kept on disk). */
    private record Layout(int width, int height, int tileW, int tileH, int across, int down, int compression, int predictor,
                          int bits, double x0, double y0, double scale, double noData, long offsetsPos, long countsPos, int offsetSize, int countSize) {
    }

    /** A block's heights in steps of {@code step} metres above {@code base}; {@link Short#MIN_VALUE} where there are none. */
    private record Block(short[] q, double base, double step, int w, int h) {
        double at(int x, int y) {
            short v = q[y * w + x];
            return v == Short.MIN_VALUE ? Double.NaN : base + (v + 32767) * step;
        }
    }

    /**
     * Heights on GEDTM30's own grid over a lat/lon box (with a pixel to spare), for bilinear sampling inside it; the
     * cells a tile needs are read once instead of per block column.
     */
    public final class Window {
        final int col0, row0, cols, rows;
        final float[] v;

        Window(int col0, int row0, int cols, int rows, float[] v) {
            this.col0 = col0;
            this.row0 = row0;
            this.cols = cols;
            this.rows = rows;
            this.v = v;
        }

        /** Bilinear height at a place inside the window, or NaN where the model has none (open sea, past 85 N). */
        public double sample(double lat, double lon) {
            Layout l = layout;
            double fx = (lon - l.x0) / l.scale - 0.5 - col0, fy = (l.y0 - lat) / l.scale - 0.5 - row0;
            int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
            if (i < 0 || j < 0 || i + 1 >= cols || j + 1 >= rows) return Double.NaN;
            double tx = fx - i, ty = fy - j;
            float a = v[j * cols + i], b = v[j * cols + i + 1], c = v[(j + 1) * cols + i], d = v[(j + 1) * cols + i + 1];
            if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)) return Double.NaN;
            return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
        }
    }

    /** The model's heights over a box (south, west, north, east); downloads the blocks it needs and waits. */
    public Window window(double south, double west, double north, double east) throws IOException, InterruptedException {
        Layout l = layout();
        int col0 = (int) Math.floor((west - l.x0) / l.scale) - 2, col1 = (int) Math.floor((east - l.x0) / l.scale) + 2;
        int row0 = (int) Math.floor((l.y0 - north) / l.scale) - 2, row1 = (int) Math.floor((l.y0 - south) / l.scale) + 2;
        int cols = col1 - col0 + 1, rws = row1 - row0 + 1;
        float[] v = new float[cols * rws];
        java.util.Arrays.fill(v, Float.NaN);
        int bc0 = Math.floorDiv(col0, l.tileW), bc1 = Math.floorDiv(col1, l.tileW);
        int br0 = Math.max(0, Math.floorDiv(row0, l.tileH)), br1 = Math.min(l.down - 1, Math.floorDiv(row1, l.tileH));
        for (int br = br0; br <= br1; br++) {
            for (int bc = bc0; bc <= bc1; bc++) {
                if (bc < 0 || bc >= l.across) continue; // past the antimeridian: no worlds reach it at 30 m detail
                Block b = block(br, bc);
                if (b == null) continue;
                int r0 = Math.max(row0, br * l.tileH), r1 = Math.min(row1, br * l.tileH + b.h - 1);
                int c0 = Math.max(col0, bc * l.tileW), c1 = Math.min(col1, bc * l.tileW + b.w - 1);
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        v[(r - row0) * cols + (c - col0)] = (float) b.at(c - bc * l.tileW, r - br * l.tileH);
                    }
                }
            }
        }
        return new Window(col0, row0, cols, rws, v);
    }

    // ------------------------------------------------------------------ blocks

    private Block block(int row, int col) throws IOException, InterruptedException {
        Layout l = layout();
        long key = (long) row * l.across + col;
        synchronized (blocks) {
            Block b = blocks.get(key);
            if (b != null) return b;
        }
        CompletableFuture<Block> f = loading.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                return readBlock(l, row, col);
            } catch (IOException | InterruptedException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, POOL));
        try {
            Block b = f.join();
            synchronized (blocks) {
                blocks.put(key, b);
                // 8 MB each (a 2048 x 2048 block in 16-bit steps): the few around a world's area.
                while (blocks.size() > 6) blocks.remove(blocks.keySet().iterator().next());
            }
            return b;
        } catch (java.util.concurrent.CompletionException e) {
            Throwable c = e.getCause();
            throw c instanceof IOException io ? io : new IOException(String.valueOf(c), c);
        } finally {
            loading.remove(key, f);
        }
    }

    private Block readBlock(Layout l, int row, int col) throws IOException, InterruptedException {
        Path file = dir.resolve("blocks").resolve(row + "_" + col + ".bin");
        byte[] raw;
        if (Files.exists(file)) {
            raw = Files.readAllBytes(file);
        } else {
            long[][] table = tableRow(l, row);
            long offset = table[0][col], count = table[1][col];
            if (count < 0 || count > (long) l.tileW * l.tileH * (l.bits / 8) * 2) throw new IOException("implausible block size " + count + " at " + row + "/" + col);
            raw = count == 0 ? new byte[0] : fetchPieces(offset, count, file);
            if (count == 0) {
                Files.createDirectories(file.getParent());
                Files.write(file, raw);
            }
        }
        int n = l.tileW * l.tileH;
        short[] q = new short[n];
        if (raw.length == 0) {
            java.util.Arrays.fill(q, Short.MIN_VALUE);
            return new Block(q, 0, 1, l.tileW, l.tileH);
        }
        byte[] data = inflate(raw, n * (l.bits / 8));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (l.predictor == 2) {
            // Horizontal differencing of the 32-bit words (libtiff applies it to floats as integers too).
            for (int y = 0; y < l.tileH; y++) {
                int base = y * l.tileW * 4;
                for (int x = 1; x < l.tileW; x++) bb.putInt(base + x * 4, bb.getInt(base + x * 4) + bb.getInt(base + (x - 1) * 4));
            }
        }
        float[] f = new float[n];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float v = bb.getFloat(i * 4);
            if (Float.isNaN(v) || v < -12000 || v > 9000 || (!Double.isNaN(l.noData) && Math.abs(v - l.noData) <= Math.abs(l.noData) * 1e-6)) v = Float.NaN;
            f[i] = v;
            if (!Float.isNaN(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min > max) {
            java.util.Arrays.fill(q, Short.MIN_VALUE);
            return new Block(q, 0, 1, l.tileW, l.tileH);
        }
        // The model is stored to 0.1 m; a block spanning more than 6.5 km of relief gets coarser steps (still under 0.15 m).
        double step = Math.max(0.1, (max - min) / 65000.0);
        for (int i = 0; i < n; i++) {
            q[i] = Float.isNaN(f[i]) ? Short.MIN_VALUE : (short) (Math.round((f[i] - min) / step) - 32767);
        }
        return new Block(q, min, step, l.tileW, l.tileH);
    }

    /**
     * A block's bytes in range requests of PIECE, fetched PARALLEL at a time and appended in order to a part file
     * (continued after a failure from what it holds), then moved into place: a whole block in one request outlasted
     * every timeout on a slow line, and one piece at a time left the line mostly idle.
     */
    private byte[] fetchPieces(long offset, long count, Path file) throws IOException, InterruptedException {
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > count) {
            Files.delete(part);
            have = 0;
        }
        java.util.List<CompletableFuture<byte[]>> pieces = new java.util.ArrayList<>();
        for (long start = have; start < count; start += PIECE) {
            long from = start, len = Math.min(PIECE, count - start);
            pieces.add(CompletableFuture.supplyAsync(() -> {
                try {
                    if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
                    return range(offset + from, len);
                } catch (IOException | InterruptedException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, RANGES));
        }
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (CompletableFuture<byte[]> f : pieces) out.write(f.join());
        } catch (java.util.concurrent.CompletionException e) {
            for (CompletableFuture<byte[]> f : pieces) f.cancel(false);
            Throwable c = e.getCause();
            if (c instanceof InterruptedException ie) throw ie;
            throw c instanceof IOException io ? io : new IOException(String.valueOf(c), c);
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        return Files.readAllBytes(file);
    }

    private byte[] range(long offset, long length) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Map<String, String> h = new java.util.HashMap<>(HEADERS);
                h.put("Range", "bytes=" + offset + "-" + (offset + length - 1));
                OrbisHttp.Response r = OrbisHttp.get(url, h, 60);
                if (r.status() == 206 && r.body().length == length) return r.body();
                last = new IOException("HTTP " + r.status() + " (" + r.body().length + " of " + length + " bytes) for a range of " + url);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000L << attempt);
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
        } catch (DataFormatException e) {
            throw new IOException("bad deflate block: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
        return out;
    }

    /** {offsets, byte counts} of one row of blocks (the tables hold 167 000 entries; a row is 633). */
    private long[][] tableRow(Layout l, int row) throws IOException, InterruptedException {
        long[][] t = rows.get(row);
        if (t != null) return t;
        synchronized (rows) {
            t = rows.get(row);
            if (t != null) return t;
            Path file = dir.resolve("table").resolve(row + ".bin");
            int lo = l.across * l.offsetSize, lc = l.across * l.countSize;
            byte[] b;
            if (Files.exists(file) && Files.size(file) == lo + lc) {
                b = Files.readAllBytes(file);
            } else {
                byte[] o = range(l.offsetsPos + (long) row * lo, lo), c = range(l.countsPos + (long) row * lc, lc);
                b = new byte[lo + lc];
                System.arraycopy(o, 0, b, 0, lo);
                System.arraycopy(c, 0, b, lo, lc);
                Files.createDirectories(file.getParent());
                Files.write(file, b);
            }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            t = new long[2][l.across];
            for (int i = 0; i < l.across; i++) {
                t[0][i] = l.offsetSize == 8 ? bb.getLong(i * 8) : bb.getInt(i * 4) & 0xFFFFFFFFL;
                t[1][i] = l.countSize == 8 ? bb.getLong(lo + i * 8) : bb.getInt(lo + i * 4) & 0xFFFFFFFFL;
            }
            rows.put(row, t);
            return t;
        }
    }

    // ------------------------------------------------------------------ the header

    private Layout layout() throws IOException, InterruptedException {
        Layout l = layout;
        if (l != null) return l;
        synchronized (this) {
            if (layout != null) return layout;
            Path file = dir.resolve("layout.properties");
            if (Files.exists(file)) {
                try {
                    layout = load(file);
                    return layout;
                } catch (RuntimeException e) {
                    Files.deleteIfExists(file);
                }
            }
            layout = readHeader();
            Files.createDirectories(dir);
            save(layout, file);
            return layout;
        }
    }

    /** Reads the first image directory of the (BigTIFF) file: GDAL wrote it at the end of this one. */
    private Layout readHeader() throws IOException, InterruptedException {
        byte[] head = range(0, 16);
        ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (head[0] != 'I' || (hb.getShort(2) & 0xFFFF) != 43) throw new IOException("not a little-endian BigTIFF");
        long ifd = hb.getLong(8);
        byte[] d = range(ifd, 8);
        long entries = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getLong(0);
        if (entries <= 0 || entries > 200) throw new IOException("bad image directory (" + entries + " entries)");
        ByteBuffer bb = ByteBuffer.wrap(range(ifd + 8, entries * 20)).order(ByteOrder.LITTLE_ENDIAN);
        int width = 0, height = 0, tileW = 0, tileH = 0, compression = 1, predictor = 1, bits = 32, format = 1, offsetSize = 8, countSize = 8;
        long offsetsPos = 0, countsPos = 0;
        double[] pixel = null, tie = null;
        double noData = Double.NaN;
        for (int i = 0; i < entries; i++) {
            int p = i * 20;
            int tag = bb.getShort(p) & 0xFFFF, type = bb.getShort(p + 2) & 0xFFFF;
            long count = bb.getLong(p + 4);
            int size = switch (type) {
                case 3 -> 2;
                case 4, 11 -> 4;
                case 5, 12, 16 -> 8;
                default -> 1;
            };
            boolean inline = size * count <= 8;
            long value = type == 3 ? bb.getShort(p + 12) & 0xFFFF : type == 4 ? bb.getInt(p + 12) & 0xFFFFFFFFL : bb.getLong(p + 12);
            switch (tag) {
                case 256 -> width = (int) value;
                case 257 -> height = (int) value;
                case 258 -> bits = (int) value;
                case 259 -> compression = (int) value;
                case 317 -> predictor = (int) value;
                case 322 -> tileW = (int) value;
                case 323 -> tileH = (int) value;
                case 339 -> format = (int) value;
                case 324 -> {
                    offsetsPos = inline ? -1 : bb.getLong(p + 12);
                    offsetSize = size;
                }
                case 325 -> {
                    countsPos = inline ? -1 : bb.getLong(p + 12);
                    countSize = size; // 4 bytes in GEDTM30 while the offsets take 8
                }
                case 33550 -> pixel = doubles(inline, bb, p, count);
                case 33922 -> tie = doubles(inline, bb, p, count);
                case 42113 -> {
                    byte[] s = inline ? java.util.Arrays.copyOfRange(bb.array(), p + 12, p + 12 + (int) count) : range(bb.getLong(p + 12), count);
                    try {
                        noData = Double.parseDouble(new String(s, StandardCharsets.US_ASCII).replace("\0", "").trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                default -> { }
            }
        }
        if (tileW <= 0 || tileH <= 0 || pixel == null || tie == null || offsetsPos <= 0 || countsPos <= 0) throw new IOException("not a tiled, georeferenced GeoTIFF");
        if (compression != 8 && compression != 32946) throw new IOException("compression " + compression + " not supported");
        if (bits != 32 || format != 3) throw new IOException("not 32-bit floats");
        double scale = pixel[0];
        double x0 = tie[3] - tie[0] * scale, y0 = tie[4] + tie[1] * scale;
        return new Layout(width, height, tileW, tileH, (width + tileW - 1) / tileW, (height + tileH - 1) / tileH, compression, predictor, bits,
                x0, y0, scale, noData, offsetsPos, countsPos, offsetSize, countSize);
    }

    private double[] doubles(boolean inline, ByteBuffer bb, int p, long count) throws IOException, InterruptedException {
        ByteBuffer src = inline ? bb : ByteBuffer.wrap(range(bb.getLong(p + 12), count * 8)).order(ByteOrder.LITTLE_ENDIAN);
        int base = inline ? p + 12 : 0;
        double[] out = new double[(int) count];
        for (int i = 0; i < count; i++) out[i] = src.getDouble(base + i * 8);
        return out;
    }

    private static void save(Layout l, Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("width", String.valueOf(l.width));
        p.setProperty("height", String.valueOf(l.height));
        p.setProperty("tileW", String.valueOf(l.tileW));
        p.setProperty("tileH", String.valueOf(l.tileH));
        p.setProperty("compression", String.valueOf(l.compression));
        p.setProperty("predictor", String.valueOf(l.predictor));
        p.setProperty("bits", String.valueOf(l.bits));
        p.setProperty("x0", String.valueOf(l.x0));
        p.setProperty("y0", String.valueOf(l.y0));
        p.setProperty("scale", String.valueOf(l.scale));
        p.setProperty("noData", String.valueOf(l.noData));
        p.setProperty("offsetsPos", String.valueOf(l.offsetsPos));
        p.setProperty("countsPos", String.valueOf(l.countsPos));
        p.setProperty("offsetSize", String.valueOf(l.offsetSize));
        p.setProperty("countSize", String.valueOf(l.countSize));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "GEDTM30 file layout (read from " + URL + ")");
        }
    }

    private static Layout load(Path file) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        int width = Integer.parseInt(p.getProperty("width")), height = Integer.parseInt(p.getProperty("height"));
        int tileW = Integer.parseInt(p.getProperty("tileW")), tileH = Integer.parseInt(p.getProperty("tileH"));
        return new Layout(width, height, tileW, tileH, (width + tileW - 1) / tileW, (height + tileH - 1) / tileH,
                Integer.parseInt(p.getProperty("compression")), Integer.parseInt(p.getProperty("predictor")), Integer.parseInt(p.getProperty("bits")),
                Double.parseDouble(p.getProperty("x0")), Double.parseDouble(p.getProperty("y0")), Double.parseDouble(p.getProperty("scale")),
                Double.parseDouble(p.getProperty("noData")), Long.parseLong(p.getProperty("offsetsPos")), Long.parseLong(p.getProperty("countsPos")),
                Integer.parseInt(p.getProperty("offsetSize")), Integer.parseInt(p.getProperty("countSize")));
    }
}
