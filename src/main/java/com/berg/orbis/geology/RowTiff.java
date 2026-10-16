package com.berg.orbis.geology;

import com.berg.orbis.net.OrbisHttp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A whole-planet GeoTIFF on a web server, one image row per strip (deflate, no predictor), in lat/lon: ISRIC's soil depth
 * maps (Shangguan et al. 2017, 250 m, 8.5 and 1.4 GB). Only the rows a world touches are read, with range requests (rows
 * next to each other in one), and each is kept on disk as it came. The strip tables (half a megabyte each) are read once
 * and kept too.
 */
final class RowTiff {
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; soil depth)");
    /** Rows fetched in one request at most (a row is up to 200 kB). */
    private static final int ROWS_PER_REQUEST = 8;

    private final String url;
    private final Path cacheDir;
    private volatile Layout layout;
    private final Map<Integer, CompletableFuture<byte[]>> loading = new ConcurrentHashMap<>();

    private record Layout(int width, int height, int bits, boolean signed, double x0, double y0, double dx, double dy,
                          long nodata, long[] offsets, long[] counts) {
    }

    RowTiff(String url, Path cacheDir) {
        this.url = url;
        this.cacheDir = cacheDir;
    }

    /** A box of the map: values row by row from its north-west corner, {@link Integer#MIN_VALUE} where it has none. */
    record Window(int row0, int col0, int rows, int cols, int[] v, double x0, double y0, double dx, double dy) {
        /** The value of the pixel holding a place, or MIN_VALUE outside the window or where there is none. */
        int at(double lat, double lon) {
            int row = (int) Math.floor((y0 - lat) / dy) - row0;
            int col = (int) Math.floor((lon - x0) / dx) - col0;
            if (row < 0 || col < 0 || row >= rows || col >= cols) return Integer.MIN_VALUE;
            return v[row * cols + col];
        }
    }

    /** The pixels covering the box (a pixel's margin around it), or null when the box lies off the map. */
    Window window(double south, double west, double north, double east) throws IOException, InterruptedException {
        Layout l = layout();
        int row0 = Math.max(0, (int) Math.floor((l.y0 - north) / l.dy) - 1);
        int row1 = Math.min(l.height - 1, (int) Math.floor((l.y0 - south) / l.dy) + 1);
        int col0 = Math.max(0, (int) Math.floor((west - l.x0) / l.dx) - 1);
        int col1 = Math.min(l.width - 1, (int) Math.floor((east - l.x0) / l.dx) + 1);
        if (row0 > row1 || col0 > col1) return null;
        int rows = row1 - row0 + 1, cols = col1 - col0 + 1;
        int[] v = new int[rows * cols];
        fetchMissing(l, row0, row1);
        int bytes = l.bits / 8;
        for (int r = row0; r <= row1; r++) {
            byte[] raw = row(l, r);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int c = col0; c <= col1; c++) {
                int p = c * bytes;
                long x = switch (l.bits) {
                    case 8 -> l.signed ? raw[p] : raw[p] & 0xFF;
                    case 16 -> l.signed ? bb.getShort(p) : bb.getShort(p) & 0xFFFF;
                    default -> l.signed ? bb.getInt(p) : bb.getInt(p) & 0xFFFFFFFFL;
                };
                v[(r - row0) * cols + (c - col0)] = x == l.nodata ? Integer.MIN_VALUE : (int) x;
            }
        }
        return new Window(row0, col0, rows, cols, v, l.x0, l.y0, l.dx, l.dy);
    }

    /** One row, inflated. */
    private byte[] row(Layout l, int r) throws IOException, InterruptedException {
        Path file = rowFile(r);
        byte[] z;
        try {
            z = Files.readAllBytes(file);
        } catch (java.nio.file.NoSuchFileException e) {
            fetchMissing(l, r, r);
            z = Files.readAllBytes(file);
        }
        int expected = l.width * (l.bits / 8);
        try {
            return inflate(z, expected);
        } catch (IOException e) {
            Files.deleteIfExists(file); // a damaged copy: fetched again next time
            throw e;
        }
    }

    private Path rowFile(int r) {
        return cacheDir.resolve("rows").resolve((r / 1000) + "").resolve(r + ".z");
    }

    /** Fetches the rows of [row0, row1] not on disk yet, neighbours together. */
    private void fetchMissing(Layout l, int row0, int row1) throws IOException, InterruptedException {
        int r = row0;
        while (r <= row1) {
            if (Files.exists(rowFile(r))) {
                r++;
                continue;
            }
            int start = r, end = r;
            while (end + 1 <= row1 && end + 1 - start < ROWS_PER_REQUEST && !Files.exists(rowFile(end + 1))
                    && l.offsets[end + 1] == l.offsets[end] + l.counts[end]) end++;
            fetchRun(l, start, end);
            r = end + 1;
        }
    }

    private void fetchRun(Layout l, int start, int end) throws IOException, InterruptedException {
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> running = loading.putIfAbsent(start, mine);
        if (running != null) {
            // Another region is fetching these rows: wait for it.
            try {
                running.get();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                throw c instanceof IOException io ? io : new IOException(String.valueOf(c), c);
            }
        }
        try {
            long from = l.offsets[start], to = l.offsets[end] + l.counts[end];
            byte[] all = range(from, to - from);
            for (int r = start; r <= end; r++) {
                int p = (int) (l.offsets[r] - from);
                byte[] one = java.util.Arrays.copyOfRange(all, p, p + (int) l.counts[r]);
                Path file = rowFile(r);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
                Files.write(tmp, one);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            mine.complete(all);
        } catch (IOException | InterruptedException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(start, mine);
        }
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
            if (off < expected) throw new IOException("short row (" + off + " of " + expected + " bytes)");
        } catch (DataFormatException e) {
            throw new IOException("bad deflate row: " + e.getMessage(), e);
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
            Path file = cacheDir.resolve("layout.bin");
            try {
                if (Files.exists(file)) l = load(file);
            } catch (IOException e) {
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

    /** The first image's tags (TIFF or BigTIFF, little-endian): size, sample type, strips, georeference, no-data value. */
    private Layout readHeader() throws IOException, InterruptedException {
        ByteBuffer head = ByteBuffer.wrap(range(0, 16)).order(ByteOrder.LITTLE_ENDIAN);
        if (head.get(0) != 'I') throw new IOException("not a little-endian TIFF");
        boolean big = head.getShort(2) == 43;
        long ifd = big ? head.getLong(8) : head.getInt(4) & 0xFFFFFFFFL;
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12;
        ByteBuffer cnt = ByteBuffer.wrap(range(ifd, countSize)).order(ByteOrder.LITTLE_ENDIAN);
        long n = big ? cnt.getLong(0) : cnt.getShort(0) & 0xFFFF;
        ByteBuffer e = ByteBuffer.wrap(range(ifd + countSize, n * entrySize)).order(ByteOrder.LITTLE_ENDIAN);
        int width = 0, height = 0, bits = 8, format = 1, compression = 1, predictor = 1, rowsPerStrip = 1;
        long[] offsets = null, counts = null;
        double[] scale = null, tie = null;
        long nodata = Long.MIN_VALUE;
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
            ByteBuffer data;
            if (inline) {
                data = e.slice(valuePos, big ? 8 : 4).order(ByteOrder.LITTLE_ENDIAN);
            } else {
                long at = big ? e.getLong(valuePos) : e.getInt(valuePos) & 0xFFFFFFFFL;
                data = ByteBuffer.wrap(range(at, size * count)).order(ByteOrder.LITTLE_ENDIAN);
            }
            switch (tag) {
                case 256 -> width = (int) number(data, 0, type);
                case 257 -> height = (int) number(data, 0, type);
                case 258 -> bits = (int) number(data, 0, type);
                case 259 -> compression = (int) number(data, 0, type);
                case 278 -> rowsPerStrip = (int) number(data, 0, type);
                case 317 -> predictor = (int) number(data, 0, type);
                case 339 -> format = (int) number(data, 0, type);
                case 273, 279 -> {
                    long[] a = new long[(int) count];
                    for (int k = 0; k < count; k++) a[k] = number(data, k * size, type);
                    if (tag == 273) offsets = a;
                    else counts = a;
                }
                case 33550 -> scale = new double[]{data.getDouble(0), data.getDouble(8)};
                case 33922 -> tie = new double[]{data.getDouble(24), data.getDouble(32)};
                case 42113 -> {
                    byte[] b = new byte[(int) count];
                    data.get(0, b);
                    String text = new String(b, StandardCharsets.US_ASCII).replace("\0", "").trim();
                    if (!text.isEmpty()) nodata = (long) Double.parseDouble(text);
                }
                default -> { }
            }
        }
        if (compression != 8 || predictor != 1 || rowsPerStrip != 1 || offsets == null || counts == null || scale == null || tie == null
                || offsets.length != height || (bits != 8 && bits != 16 && bits != 32)) {
            throw new IOException("unexpected layout (compression " + compression + ", predictor " + predictor + ", rows per strip " + rowsPerStrip + ")");
        }
        return new Layout(width, height, bits, format == 2, tie[0], tie[1], scale[0], scale[1], nodata, offsets, counts);
    }

    private static long number(ByteBuffer b, int pos, int type) {
        return switch (type) {
            case 1, 7 -> b.get(pos) & 0xFF;
            case 3 -> b.getShort(pos) & 0xFFFF;
            case 4 -> b.getInt(pos) & 0xFFFFFFFFL;
            default -> b.getLong(pos);
        };
    }

    private static void save(Layout l, Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeInt(l.width);
            out.writeInt(l.height);
            out.writeInt(l.bits);
            out.writeBoolean(l.signed);
            out.writeDouble(l.x0);
            out.writeDouble(l.y0);
            out.writeDouble(l.dx);
            out.writeDouble(l.dy);
            out.writeLong(l.nodata);
            for (long o : l.offsets) out.writeLong(o);
            for (long c : l.counts) out.writeLong(c);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Layout load(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(raw)))) {
            if (in.readInt() != 1) throw new IOException("unknown layout version");
            int width = in.readInt(), height = in.readInt(), bits = in.readInt();
            boolean signed = in.readBoolean();
            double x0 = in.readDouble(), y0 = in.readDouble(), dx = in.readDouble(), dy = in.readDouble();
            long nodata = in.readLong();
            long[] offsets = new long[height], counts = new long[height];
            for (int i = 0; i < height; i++) offsets[i] = in.readLong();
            for (int i = 0; i < height; i++) counts[i] = in.readLong();
            return new Layout(width, height, bits, signed, x0, y0, dx, dy, nodata, offsets, counts);
        }
    }
}
