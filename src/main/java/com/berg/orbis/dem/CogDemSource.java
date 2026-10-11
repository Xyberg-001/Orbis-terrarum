package com.berg.orbis.dem;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A lidar surface model kept as cloud-optimised GeoTIFFs in a national map grid and found through a STAC catalogue
 * (Switzerland's swissSURFACE3D, Canada's HRDEM mosaics). A place is projected into the grid, the catalogue says
 * which files cover it (asked once per 0.02 degree cell and kept), and only the 512 x 512 blocks of the file under
 * the place are read with HTTP range requests (kept on disk as they came, LZW or deflate). Files of any size work:
 * Canada's mosaics are 500 000 pixels across, so their block tables are read a row at a time, never whole.
 */
public final class CogDemSource implements DemSource {

    private static final double CELL = 0.02;
    private static final int HEADER_BYTES = 65536;

    private final OrbisConfig.ElevationSource src;
    private final Path cacheDir;
    private final Map<String, CompletableFuture<List<String>>> cells = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Cog>> files = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<float[]>> loading = new ConcurrentHashMap<>();
    private final Map<String, float[]> blocks = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > 40; // 1 MB each
        }
    });
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "Orbis-cog");
        t.setDaemon(true);
        return t;
    });
    private volatile long disabledUntil;
    private volatile boolean announced;

    public CogDemSource(OrbisConfig.ElevationSource src, Path cacheDir) {
        this.src = src;
        this.cacheDir = cacheDir.resolve(src.name.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    @Override
    public double nativeResolutionMeters() {
        return src.resolutionMeters;
    }

    /** Inside the country's outline, per 0.01 degree cell (the outline test is slow for Canada, and trees ask per column). */
    private final Map<Long, Boolean> inside = new ConcurrentHashMap<>();

    @Override
    public boolean hasCoverage(double lat, double lon) {
        if (lat < src.south || lat > src.north || lon < src.west || lon > src.east || unavailable()) return false;
        long key = (long) Math.floor(lat * 100) * 100_000L + (long) Math.floor(lon * 100);
        return inside.computeIfAbsent(key, k -> {
            double s = Math.floor(lat * 100) / 100, w = Math.floor(lon * 100) / 100;
            return com.berg.orbis.config.DataSources.covers(src.name, s, w, s + 0.01, w + 0.01);
        });
    }

    private boolean unavailable() {
        return System.currentTimeMillis() < disabledUntil || com.berg.orbis.OrbisMod.stopping();
    }

    @Override
    public double sampleMeters(double lat, double lon) {
        if (!hasCoverage(lat, lon)) return Double.NaN;
        double[] xy = NationalGrids.project(src.crs, lat, lon);
        if (xy == null) return Double.NaN;
        try {
            for (String href : cell(lat, lon).join()) {
                Cog c = file(href).join();
                if (c == null || !c.contains(xy[0], xy[1])) continue;
                double v = c.sample(xy[0], xy[1]);
                if (Double.isNaN(v)) continue;
                v *= src.valueScale;
                if (!announced) {
                    announced = true;
                    System.out.println("[orbis] Surface model '" + src.name + "' is live (" + src.resolutionMeters + " m)");
                }
                return v;
            }
        } catch (RuntimeException e) {
            fail(e);
        }
        return Double.NaN;
    }

    /** Loads every block under the given lat/lon boxes (south, west, north, east) in parallel and waits for them. */
    @Override
    public void prefetch(List<double[]> boxes) {
        if (unavailable()) return;
        List<CompletableFuture<?>> jobs = new ArrayList<>();
        for (double[] b : boxes) {
            if (b[2] < src.south || b[0] > src.north || b[3] < src.west || b[1] > src.east) continue;
            if (!com.berg.orbis.config.DataSources.covers(src.name, b[0], b[1], b[2], b[3])) continue;
            double[] p1 = NationalGrids.project(src.crs, b[0], b[1]), p2 = NationalGrids.project(src.crs, b[2], b[3]);
            double[] p3 = NationalGrids.project(src.crs, b[0], b[3]), p4 = NationalGrids.project(src.crs, b[2], b[1]);
            if (p1 == null) continue;
            double minX = Math.min(Math.min(p1[0], p2[0]), Math.min(p3[0], p4[0])), maxX = Math.max(Math.max(p1[0], p2[0]), Math.max(p3[0], p4[0]));
            double minY = Math.min(Math.min(p1[1], p2[1]), Math.min(p3[1], p4[1])), maxY = Math.max(Math.max(p1[1], p2[1]), Math.max(p3[1], p4[1]));
            for (double lat = Math.floor(b[0] / CELL) * CELL; lat <= b[2]; lat += CELL) {
                for (double lon = Math.floor(b[1] / CELL) * CELL; lon <= b[3]; lon += CELL) {
                    List<String> hrefs;
                    try {
                        hrefs = cell(lat + CELL / 2, lon + CELL / 2).join();
                    } catch (RuntimeException e) {
                        continue;
                    }
                    for (String href : hrefs) {
                        Cog c;
                        try {
                            c = file(href).join();
                        } catch (RuntimeException e) {
                            continue;
                        }
                        if (c == null) continue;
                        int c0 = Math.max(0, (int) Math.floor((minX - c.x0) / c.scale) / c.tileW), c1 = Math.min(c.across - 1, (int) Math.floor((maxX - c.x0) / c.scale) / c.tileW);
                        int r0 = Math.max(0, (int) Math.floor((c.y0 - maxY) / c.scale) / c.tileH), r1 = Math.min(c.down - 1, (int) Math.floor((c.y0 - minY) / c.scale) / c.tileH);
                        for (int r = r0; r <= r1; r++) {
                            for (int col = c0; col <= c1; col++) {
                                int rr = r, cc = col;
                                jobs.add(block(c, rr, cc));
                            }
                        }
                    }
                }
            }
        }
        for (CompletableFuture<?> f : jobs) {
            try {
                f.join();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void fail(Throwable e) {
        if (System.currentTimeMillis() >= disabledUntil) {
            System.err.println("[orbis] Surface model '" + src.name + "' unavailable (" + e + "); falling back for 3 minutes");
        }
        disabledUntil = System.currentTimeMillis() + 3 * 60_000L;
    }

    // ------------------------------------------------------------------ the catalogue

    /** The files the catalogue lists for the 0.02 degree cell around a place (asked once, then kept on disk). */
    private CompletableFuture<List<String>> cell(double lat, double lon) {
        long cy = (long) Math.floor(lat / CELL), cx = (long) Math.floor(lon / CELL);
        String key = cy + "_" + cx;
        return cells.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Path f = cacheDir.resolve("stac").resolve(k + ".txt");
            try {
                if (Files.exists(f)) return Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(s -> !s.isBlank()).toList();
                double s = cy * CELL, w = cx * CELL;
                String url = src.stacSearch.replace("{west}", fmt(w)).replace("{south}", fmt(s)).replace("{east}", fmt(w + CELL)).replace("{north}", fmt(s + CELL));
                OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 45);
                if (r.status() != 200) throw new IOException("catalogue HTTP " + r.status());
                List<String> hrefs = new ArrayList<>();
                JsonArray feats = JsonParser.parseString(r.text()).getAsJsonObject().getAsJsonArray("features");
                for (JsonElement fe : feats) {
                    JsonObject assets = fe.getAsJsonObject().getAsJsonObject("assets");
                    if (assets == null) continue;
                    for (var a : assets.entrySet()) {
                        String href = a.getValue().getAsJsonObject().get("href").getAsString();
                        if (a.getKey().equals(src.stacAsset) || href.endsWith(src.stacAsset)) hrefs.add(href);
                    }
                }
                Files.createDirectories(f.getParent());
                Files.write(f, hrefs, StandardCharsets.UTF_8);
                return hrefs;
            } catch (IOException | InterruptedException | RuntimeException e) {
                cells.remove(k);
                fail(e);
                return List.<String>of();
            }
        }, POOL));
    }

    // ------------------------------------------------------------------ the files

    private CompletableFuture<Cog> file(String href) {
        return files.computeIfAbsent(href, h -> CompletableFuture.supplyAsync(() -> {
            try {
                return openFile(h, cacheDir.resolve("files").resolve(Integer.toHexString(h.hashCode())));
            } catch (IOException | InterruptedException | RuntimeException e) {
                files.remove(h);
                fail(e);
                return null;
            }
        }, POOL));
    }

    private CompletableFuture<float[]> block(Cog c, int row, int col) {
        String key = c.dir.getFileName() + "/" + row + "_" + col;
        float[] have = blocks.get(key);
        if (have != null) return CompletableFuture.completedFuture(have);
        return loading.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                float[] b = c.readBlock(row, col);
                blocks.put(k, b);
                return b;
            } catch (IOException | InterruptedException | RuntimeException e) {
                fail(e);
                return null;
            } finally {
                loading.remove(k);
            }
        }, POOL));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /** One cloud-optimised GeoTIFF: its full-resolution image, tiled, float32 (or 16/32-bit integers). */
    final class Cog {
        final String url;
        final Path dir;
        int width, height, tileW, tileH, across, down, compression = 1, predictor = 1, bits = 32, format = 3;
        double x0, y0, scale, noData = Double.NaN;
        boolean little = true, big;
        long offsetsPos, countsPos;
        int offsetType, countType;
        long[] offsets, counts; // whole tables when they came with the header
        final Map<Integer, long[][]> tableRows = new ConcurrentHashMap<>();

        private Cog(String url, Path dir) {
            this.url = url;
            this.dir = dir;
        }

        boolean contains(double x, double y) {
            return x >= x0 && x < x0 + width * scale && y <= y0 && y > y0 - height * scale;
        }

        double sample(double x, double y) {
            int px = (int) Math.floor((x - x0) / scale), py = (int) Math.floor((y0 - y) / scale);
            if (px < 0 || py < 0 || px >= width || py >= height) return Double.NaN;
            float[] b = block(this, py / tileH, px / tileW).join();
            if (b == null) return Double.NaN;
            float v = b[(py % tileH) * tileW + (px % tileW)];
            if (Float.isNaN(v) || v < -1000 || v > 9000 || (!Double.isNaN(noData) && Math.abs(v - noData) < 0.01)) return Double.NaN;
            return v;
        }

        float[] readBlock(int row, int col) throws IOException, InterruptedException {
            Path cached = dir.resolve(row + "_" + col + ".bin");
            byte[] raw;
            if (Files.exists(cached)) {
                raw = Files.readAllBytes(cached);
            } else {
                long[] oc = entry(row * across + col, row);
                if (oc[1] == 0) {
                    raw = new byte[0]; // a block with no data at all (GDAL leaves them out)
                } else {
                    raw = fetchRange(url, oc[0], oc[1]);
                }
                Files.createDirectories(dir);
                DemTileProvider.writeCached(cached, raw);
            }
            float[] out = new float[tileW * tileH];
            if (raw.length == 0) {
                java.util.Arrays.fill(out, Float.NaN);
                return out;
            }
            byte[] data = decompress(raw, tileW * tileH * (bits / 8));
            unpredict(data);
            ByteBuffer bb = ByteBuffer.wrap(data).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            int n = Math.min(out.length, data.length / (bits / 8));
            for (int i = 0; i < n; i++) {
                out[i] = format == 3 ? (bits == 32 ? bb.getFloat(i * 4) : (float) bb.getDouble(i * 8))
                        : bits == 16 ? (format == 2 ? bb.getShort(i * 2) : bb.getShort(i * 2) & 0xFFFF) : bb.getInt(i * 4);
            }
            for (int i = n; i < out.length; i++) out[i] = Float.NaN;
            return out;
        }

        /** {offset, byte count} of a block: from the whole tables, or from the row of them it is in (one read each). */
        private long[] entry(int index, int row) throws IOException, InterruptedException {
            if (offsets != null) return new long[]{offsets[index], counts[index]};
            long[][] r = tableRows.get(row);
            if (r == null) {
                int first = row * across;
                long[] o = readTable(offsetsPos, offsetType, first, across), c = readTable(countsPos, countType, first, across);
                r = new long[][]{o, c};
                tableRows.put(row, r);
            }
            int i = index - row * across;
            return new long[]{r[0][i], r[1][i]};
        }

        private long[] readTable(long pos, int type, int first, int n) throws IOException, InterruptedException {
            int size = type == 16 ? 8 : 4;
            byte[] b = fetchRange(url, pos + (long) first * size, (long) n * size);
            ByteBuffer bb = ByteBuffer.wrap(b).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            long[] out = new long[n];
            for (int i = 0; i < n; i++) out[i] = size == 8 ? bb.getLong(i * 8) : bb.getInt(i * 4) & 0xFFFFFFFFL;
            return out;
        }

        private byte[] decompress(byte[] raw, int expected) throws IOException {
            return switch (compression) {
                case 1 -> raw;
                case 5 -> lzw(raw, expected);
                case 8, 32946 -> {
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
                        throw new IOException(e);
                    } finally {
                        inf.end();
                    }
                    yield out;
                }
                default -> throw new IOException("compression " + compression + " not supported");
            };
        }

        /** Undoes the TIFF predictor: 2 (horizontal differences of samples) or 3 (floating point, bytes split). */
        private void unpredict(byte[] d) {
            int bps = bits / 8;
            if (predictor == 2 && bps == 2) {
                ByteBuffer bb = ByteBuffer.wrap(d).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                for (int y = 0; y < tileH; y++) {
                    for (int x = 1; x < tileW; x++) {
                        int i = (y * tileW + x) * 2;
                        bb.putShort(i, (short) (bb.getShort(i) + bb.getShort(i - 2)));
                    }
                }
            } else if (predictor == 2 && bps == 4) {
                ByteBuffer bb = ByteBuffer.wrap(d).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                for (int y = 0; y < tileH; y++) {
                    for (int x = 1; x < tileW; x++) {
                        int i = (y * tileW + x) * 4;
                        bb.putInt(i, bb.getInt(i) + bb.getInt(i - 4));
                    }
                }
            } else if (predictor == 3) {
                int rowBytes = tileW * bps;
                byte[] tmp = new byte[rowBytes];
                for (int y = 0; y < tileH; y++) {
                    int base = y * rowBytes;
                    if (base + rowBytes > d.length) break;
                    for (int i = 1; i < rowBytes; i++) d[base + i] += d[base + i - 1];
                    // Bytes were stored by significance (most significant first, all samples), always big-endian order.
                    for (int x = 0; x < tileW; x++) {
                        for (int k = 0; k < bps; k++) {
                            int b = little ? bps - 1 - k : k;
                            tmp[x * bps + b] = d[base + k * tileW + x];
                        }
                    }
                    System.arraycopy(tmp, 0, d, base, rowBytes);
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading files

    /** Reads a file's header: the first image (full resolution in a COG), its tiling, encoding and place in the grid. */
    Cog openFile(String url, Path dir) throws IOException, InterruptedException {
        Cog c = new Cog(url, dir);
        byte[] h = fetchRange(url, 0, HEADER_BYTES);
        c.little = h[0] == 'I';
        ByteBuffer bb = ByteBuffer.wrap(h).order(c.little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int magic = bb.getShort(2) & 0xFFFF;
        c.big = magic == 43;
        long ifd = c.big ? bb.getLong(8) : bb.getInt(4) & 0xFFFFFFFFL;
        if (ifd + 16 > h.length) throw new IOException("first image directory beyond the header read");
        int n = (int) (c.big ? bb.getLong((int) ifd) : bb.getShort((int) ifd) & 0xFFFF);
        int p = (int) ifd + (c.big ? 8 : 2);
        double[] tie = null, pixel = null;
        for (int i = 0; i < n; i++) {
            int tag = bb.getShort(p) & 0xFFFF, type = bb.getShort(p + 2) & 0xFFFF;
            long count = c.big ? bb.getLong(p + 4) : bb.getInt(p + 4) & 0xFFFFFFFFL;
            int valuePos = p + (c.big ? 12 : 8), inline = c.big ? 8 : 4;
            int size = switch (type) {
                case 3 -> 2;
                case 4, 11 -> 4;
                case 5, 12, 16 -> 8;
                default -> 1;
            };
            long dataPos = (long) size * count <= inline ? valuePos : (c.big ? bb.getLong(valuePos) : bb.getInt(valuePos) & 0xFFFFFFFFL);
            switch (tag) {
                case 256 -> c.width = (int) number(bb, valuePos, type);
                case 257 -> c.height = (int) number(bb, valuePos, type);
                case 258 -> c.bits = (int) number(bb, valuePos, type);
                case 259 -> c.compression = (int) number(bb, valuePos, type);
                case 317 -> c.predictor = (int) number(bb, valuePos, type);
                case 322 -> c.tileW = (int) number(bb, valuePos, type);
                case 323 -> c.tileH = (int) number(bb, valuePos, type);
                case 339 -> c.format = (int) number(bb, valuePos, type);
                case 324 -> {
                    c.offsetsPos = dataPos;
                    c.offsetType = type;
                }
                case 325 -> {
                    c.countsPos = dataPos;
                    c.countType = type;
                }
                case 33550 -> pixel = doubles(bb, dataPos, (int) count, h.length);
                case 33922 -> tie = doubles(bb, dataPos, (int) count, h.length);
                case 42113 -> {
                    if (dataPos + count <= h.length) {
                        String s = new String(h, (int) dataPos, (int) count, StandardCharsets.US_ASCII).trim().replace("\0", "");
                        try {
                            c.noData = Double.parseDouble(s);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                default -> { }
            }
            p += c.big ? 20 : 12;
        }
        if (c.tileW <= 0 || c.tileH <= 0) throw new IOException("not a tiled GeoTIFF");
        if (tie == null || pixel == null) throw new IOException("no georeference");
        c.scale = pixel[0];
        c.x0 = tie[3] - tie[0] * c.scale;
        c.y0 = tie[4] + tie[1] * c.scale;
        c.across = (c.width + c.tileW - 1) / c.tileW;
        c.down = (c.height + c.tileH - 1) / c.tileH;
        // Small files bring their block tables with the header; big ones are read a row at a time.
        int tiles = c.across * c.down, os = c.offsetType == 16 ? 8 : 4, cs = c.countType == 16 ? 8 : 4;
        if (c.offsetsPos + (long) tiles * os <= h.length && c.countsPos + (long) tiles * cs <= h.length) {
            c.offsets = new long[tiles];
            c.counts = new long[tiles];
            for (int i = 0; i < tiles; i++) {
                c.offsets[i] = os == 8 ? bb.getLong((int) c.offsetsPos + i * 8) : bb.getInt((int) c.offsetsPos + i * 4) & 0xFFFFFFFFL;
                c.counts[i] = cs == 8 ? bb.getLong((int) c.countsPos + i * 8) : bb.getInt((int) c.countsPos + i * 4) & 0xFFFFFFFFL;
            }
        }
        return c;
    }

    private static long number(ByteBuffer bb, int pos, int type) {
        return switch (type) {
            case 3 -> bb.getShort(pos) & 0xFFFF;
            case 16 -> bb.getLong(pos);
            default -> bb.getInt(pos) & 0xFFFFFFFFL;
        };
    }

    private static double[] doubles(ByteBuffer bb, long pos, int count, int limit) {
        if (pos + count * 8L > limit) return null;
        double[] d = new double[count];
        for (int i = 0; i < count; i++) d[i] = bb.getDouble((int) pos + i * 8);
        return d;
    }

    private static byte[] fetchRange(String url, long offset, long length) throws IOException, InterruptedException {
        OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0",
                "Range", "bytes=" + offset + "-" + (offset + length - 1)), 45);
        if (r.status() == 206) return r.body();
        if (r.status() == 200 && r.body().length >= offset + length) {
            return java.util.Arrays.copyOfRange(r.body(), (int) offset, (int) (offset + length));
        }
        throw new IOException("HTTP " + r.status() + " for a range of " + url);
    }

    /** TIFF LZW (MSB-first codes, 9 to 12 bits, with the early change). */
    static byte[] lzw(byte[] in, int expected) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) table[i] = new byte[]{(byte) i};
        int next = 258, width = 9;
        long bitPos = 0, totalBits = (long) in.length * 8;
        byte[] prev = null;
        while (bitPos + width <= totalBits) {
            int code = 0;
            for (int i = 0; i < width; i++) {
                long b = bitPos + i;
                code = (code << 1) | ((in[(int) (b >> 3)] >> (7 - (b & 7))) & 1);
            }
            bitPos += width;
            if (code == 257) break; // end of information
            if (code == 256) { // clear
                next = 258;
                width = 9;
                prev = null;
                continue;
            }
            byte[] entry;
            if (code < next && table[code] != null) {
                entry = table[code];
                if (prev != null && next < 4096) {
                    byte[] e = java.util.Arrays.copyOf(prev, prev.length + 1);
                    e[prev.length] = entry[0];
                    table[next++] = e;
                }
            } else {
                if (prev == null) break; // corrupt
                entry = java.util.Arrays.copyOf(prev, prev.length + 1);
                entry[prev.length] = prev[0];
                if (next < 4096) table[next++] = entry;
            }
            out.write(entry, 0, entry.length);
            prev = entry;
            if (next + 1 >= (1 << width) && width < 12) width++;
        }
        return out.toByteArray();
    }
}
