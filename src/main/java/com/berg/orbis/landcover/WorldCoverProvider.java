package com.berg.orbis.landcover;

import com.berg.orbis.net.OrbisHttp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * ESA WorldCover 2021: 10 m global land cover (tree cover, shrubland, grassland, cropland, built-up,
 * bare, snow/ice, water, wetland, mangroves, moss) read straight from the cloud-optimised GeoTIFFs on AWS
 * Open Data, one 3x3-degree granule per file (about 20 MB, deflate-compressed 1024x1024 tiles). Only the
 * file header and the tiles a region touches are fetched, with HTTP range requests, and cached on disk, so
 * the whole world is available on demand without any import step. The rasteriser uses it to fill in the
 * land cover wherever OpenStreetMap has no landuse/natural polygon.
 *
 * Licence CC BY 4.0: (c) ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021)
 * processed by the ESA WorldCover consortium.
 */
public final class WorldCoverProvider {

    public static final String DEFAULT_URL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_{tile}_Map.tif";
    public static final String ATTRIBUTION = "ESA WorldCover 2021 v200 (10 m, CC BY 4.0): (c) ESA WorldCover project 2021, contains modified Copernicus Sentinel data (2021) processed by the ESA WorldCover consortium";

    public static final int TREE_COVER = 10, SHRUBLAND = 20, GRASSLAND = 30, CROPLAND = 40, BUILT_UP = 50, BARE = 60,
            SNOW_ICE = 70, WATER = 80, WETLAND = 90, MANGROVES = 95, MOSS_LICHEN = 100;

    private static final int GRANULE_DEGREES = 3;
    private static final int HEADER_BYTES = 512 * 1024;
    private static final int MEMORY_TILES = 96;
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(5);

    private final Path cacheDir;
    private final String urlTemplate;
    private final ConcurrentHashMap<String, Granule> granules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> failures = new ConcurrentHashMap<>();
    private final Map<String, byte[]> tiles = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MEMORY_TILES;
        }
    });

    public WorldCoverProvider(Path cacheDir, String urlTemplate) {
        this.cacheDir = cacheDir;
        this.urlTemplate = urlTemplate == null || urlTemplate.isBlank() ? DEFAULT_URL : urlTemplate;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create WorldCover cache dir: " + cacheDir, e);
        }
    }

    /** The WorldCover class code at a point (10, 20, ... 100), or 0 where there is no data (open sea) or it cannot be fetched. */
    public int classAt(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -60 || lat > 84) return 0; // the product's extent
        int glat = Math.floorDiv((int) Math.floor(lat), GRANULE_DEGREES) * GRANULE_DEGREES;
        int glon = Math.floorDiv((int) Math.floor(lon), GRANULE_DEGREES) * GRANULE_DEGREES;
        String name = granuleName(glat, glon);
        Instant failed = failures.get(name);
        if (failed != null && Duration.between(failed, Instant.now()).compareTo(FAILURE_BACKOFF) < 0) return 0;
        Granule g;
        try {
            g = granules.computeIfAbsent(name, n -> loadGranule(n));
        } catch (RuntimeException e) {
            failures.put(name, Instant.now());
            System.err.println("[orbis] WorldCover granule " + name + " unavailable: " + e.getMessage() + " -- land cover comes from OSM alone for 5 minutes");
            return 0;
        }
        if (g == Granule.MISSING) return 0;
        double pixel = (double) GRANULE_DEGREES / g.width;
        int col = (int) ((lon - glon) / pixel);
        int row = (int) ((glat + GRANULE_DEGREES - lat) / pixel);
        if (col < 0 || row < 0 || col >= g.width || row >= g.height) return 0;
        int tx = col / g.tileW, ty = row / g.tileH;
        int index = ty * g.tilesAcross + tx;
        byte[] tile;
        try {
            tile = tile(g, index);
        } catch (RuntimeException e) {
            failures.put(name, Instant.now());
            System.err.println("[orbis] WorldCover tile " + name + "/" + index + " unavailable: " + e.getMessage());
            return 0;
        }
        if (tile == null) return 0;
        return tile[(row - ty * g.tileH) * g.tileW + (col - tx * g.tileW)] & 0xFF;
    }

    static String granuleName(int glat, int glon) {
        return String.format(Locale.ROOT, "%s%02d%s%03d", glat < 0 ? "S" : "N", Math.abs(glat), glon < 0 ? "W" : "E", Math.abs(glon));
    }

    // ------------------------------------------------------------------ granules (one GeoTIFF each)

    /** The parsed header of one 3-degree GeoTIFF: image and tile geometry plus where every tile's bytes are. */
    static final class Granule {
        static final Granule MISSING = new Granule();
        String name;
        int width, height, tileW, tileH, tilesAcross, compression = 1, predictor = 1;
        long[] offsets, counts;
        Path dir;
    }

    private Granule loadGranule(String name) {
        Path dir = cacheDir.resolve(name);
        Path index = dir.resolve("index.bin");
        Path missing = dir.resolve("missing");
        try {
            if (Files.exists(missing)) return Granule.MISSING;
            if (Files.exists(index)) {
                Granule g = readIndex(index);
                g.name = name;
                g.dir = dir;
                return g;
            }
            String url = urlTemplate.replace("{tile}", name);
            byte[] head = fetchRange(url, 0, HEADER_BYTES);
            if (head == null) {
                Files.createDirectories(dir);
                Files.write(missing, new byte[0]);
                return Granule.MISSING;
            }
            Granule g = parseHeader(head, url);
            g.name = name;
            g.dir = dir;
            Files.createDirectories(dir);
            writeIndex(index, g);
            return g;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** Bytes [offset, offset+length) of the file, or null when the file does not exist (an ocean granule). */
    private static byte[] fetchRange(String url, long offset, long length) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                OrbisHttp.Response resp = OrbisHttp.get(url, Map.of("Range", "bytes=" + offset + "-" + (offset + length - 1),
                        "User-Agent", "Orbis-Minecraft-Mod/1.0"), 40);
                if (resp.status() == 404 || resp.status() == 403) return null;
                if (resp.status() == 206) return resp.body();
                if (resp.status() == 200) {
                    // The server ignored the range and sent the whole file.
                    byte[] all = resp.body();
                    if (offset >= all.length) return new byte[0];
                    int n = (int) Math.min(length, all.length - offset);
                    return Arrays.copyOfRange(all, (int) offset, (int) offset + n);
                }
                last = new IOException("HTTP " + resp.status() + " for " + url);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1500L << attempt);
        }
        throw last;
    }

    /** Minimal TIFF/BigTIFF reader for the first image directory of a tiled single-band file. */
    private static Granule parseHeader(byte[] head, String url) throws IOException, InterruptedException {
        ByteBuffer b = ByteBuffer.wrap(head);
        b.order(head[0] == 0x49 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN); // 'I' = Intel byte order
        int magic = b.getShort(2) & 0xFFFF;
        boolean big = magic == 43;
        if (magic != 42 && !big) throw new IOException("Not a TIFF file: " + url);
        long ifd = big ? b.getLong(8) : (b.getInt(4) & 0xFFFFFFFFL);
        Granule g = new Granule();
        long entries = big ? b.getLong((int) ifd) : (b.getShort((int) ifd) & 0xFFFF);
        long pos = ifd + (big ? 8 : 2);
        int entrySize = big ? 20 : 12;
        for (long i = 0; i < entries; i++, pos += entrySize) {
            int p = (int) pos;
            int tag = b.getShort(p) & 0xFFFF;
            int type = b.getShort(p + 2) & 0xFFFF;
            long count = big ? b.getLong(p + 4) : (b.getInt(p + 4) & 0xFFFFFFFFL);
            int valuePos = p + (big ? 12 : 8);
            int typeSize = switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11 -> 4;
                case 5, 10, 12, 16, 17, 18 -> 8;
                default -> 1;
            };
            long total = count * typeSize;
            switch (tag) {
                case 256 -> g.width = (int) readNumber(b, valuePos, type);
                case 257 -> g.height = (int) readNumber(b, valuePos, type);
                case 322 -> g.tileW = (int) readNumber(b, valuePos, type);
                case 323 -> g.tileH = (int) readNumber(b, valuePos, type);
                case 259 -> g.compression = (int) readNumber(b, valuePos, type);
                case 317 -> g.predictor = (int) readNumber(b, valuePos, type);
                case 324, 325 -> {
                    // Tile offsets / byte counts: inline when small, else an array elsewhere in the file (usually
                    // inside the header block; fetched separately when not).
                    long[] arr = new long[(int) count];
                    ByteBuffer src = b;
                    int start;
                    if (total <= (big ? 8 : 4)) {
                        start = valuePos;
                    } else {
                        long off = big ? b.getLong(valuePos) : (b.getInt(valuePos) & 0xFFFFFFFFL);
                        if (off + total <= head.length) {
                            start = (int) off;
                        } else {
                            byte[] arrBytes = fetchRange(url, off, total);
                            if (arrBytes == null) throw new IOException("Tile table missing in " + url);
                            src = ByteBuffer.wrap(arrBytes).order(b.order());
                            start = 0;
                        }
                    }
                    for (int k = 0; k < count; k++) arr[k] = readNumber(src, start + k * typeSize, type);
                    if (tag == 324) g.offsets = arr;
                    else g.counts = arr;
                }
                default -> { }
            }
        }
        if (g.width <= 0 || g.tileW <= 0 || g.offsets == null || g.counts == null) throw new IOException("Not a tiled TIFF: " + url);
        g.tilesAcross = (g.width + g.tileW - 1) / g.tileW;
        return g;
    }

    private static long readNumber(ByteBuffer b, int pos, int type) {
        return switch (type) {
            case 1, 7 -> b.get(pos) & 0xFF;
            case 3 -> b.getShort(pos) & 0xFFFF;
            case 4 -> b.getInt(pos) & 0xFFFFFFFFL;
            case 16 -> b.getLong(pos);
            case 12 -> (long) b.getDouble(pos);
            default -> b.getInt(pos) & 0xFFFFFFFFL;
        };
    }

    private static void writeIndex(Path index, Granule g) throws IOException {
        Path tmp = index.resolveSibling("index.tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
            out.writeInt(0x57C0BE21);
            out.writeInt(g.width);
            out.writeInt(g.height);
            out.writeInt(g.tileW);
            out.writeInt(g.tileH);
            out.writeInt(g.compression);
            out.writeInt(g.predictor);
            out.writeInt(g.offsets.length);
            for (int i = 0; i < g.offsets.length; i++) {
                out.writeLong(g.offsets[i]);
                out.writeLong(g.counts[i]);
            }
        }
        Files.move(tmp, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Granule readIndex(Path index) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(index))) {
            if (in.readInt() != 0x57C0BE21) throw new IOException("bad index");
            Granule g = new Granule();
            g.width = in.readInt();
            g.height = in.readInt();
            g.tileW = in.readInt();
            g.tileH = in.readInt();
            g.compression = in.readInt();
            g.predictor = in.readInt();
            int n = in.readInt();
            g.offsets = new long[n];
            g.counts = new long[n];
            for (int i = 0; i < n; i++) {
                g.offsets[i] = in.readLong();
                g.counts[i] = in.readLong();
            }
            g.tilesAcross = (g.width + g.tileW - 1) / g.tileW;
            return g;
        }
    }

    // ------------------------------------------------------------------ tiles

    /** One decoded tile of class codes (1024x1024 in the ESA files); null when the tile holds no data. */
    private byte[] tile(Granule g, int index) {
        String key = g.name + "/" + index;
        byte[] cached = tiles.get(key);
        if (cached != null) return cached;
        synchronized (g) {
            cached = tiles.get(key);
            if (cached != null) return cached;
            if (index < 0 || index >= g.offsets.length || g.counts[index] == 0) return null; // sparse tile: no data
            try {
                Path file = g.dir.resolve("t" + index + ".bin");
                byte[] raw;
                if (Files.exists(file)) {
                    raw = Files.readAllBytes(file);
                } else {
                    raw = fetchRange(urlTemplate.replace("{tile}", g.name), g.offsets[index], g.counts[index]);
                    if (raw == null) return null;
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(tmp, raw);
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                byte[] decoded = decode(raw, g);
                tiles.put(key, decoded);
                return decoded;
            } catch (IOException | InterruptedException | DataFormatException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    private static byte[] decode(byte[] raw, Granule g) throws IOException, DataFormatException {
        int size = g.tileW * g.tileH;
        byte[] out;
        if (g.compression == 8 || g.compression == 32946) {
            Inflater inf = new Inflater();
            inf.setInput(raw);
            out = new byte[size];
            int n = 0;
            while (n < size && !inf.finished()) {
                int k = inf.inflate(out, n, size - n);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += k;
            }
            inf.end();
        } else if (g.compression == 1) {
            out = raw.length == size ? raw : Arrays.copyOf(raw, size);
        } else {
            throw new IOException("WorldCover tile compression " + g.compression + " not supported");
        }
        if (g.predictor == 2) {
            for (int row = 0; row < g.tileH; row++) {
                int base = row * g.tileW;
                for (int col = 1; col < g.tileW; col++) out[base + col] += out[base + col - 1];
            }
        }
        return out;
    }
}
