package com.berg.orbis.geology;

import com.berg.orbis.net.OrbisHttp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/**
 * The ground between the grass and the rock, worldwide at 250 m: ISRIC SoilGrids 2.0's most probable soil group and the
 * texture of the subsoil (sand, clay and stones at 30 to 60 cm, CC BY 4.0), and Shangguan et al.'s depth to bedrock
 * (ISRIC 2017, ODbL): how deep the soil goes where rock lies within 2 m, how deep the loose ground goes where it does not.
 * The soil groups come by tiles of a quarter degree (four small WCS answers each); the depths row by row from two
 * whole-planet files. Failures are not kept (asked again after 10 minutes).
 */
public final class Soils {
    public static final String ATTRIBUTION = "SoilGrids 2.0 (ISRIC, CC BY 4.0) soil groups and texture; depth to bedrock from "
            + "Shangguan et al. 2017 (ISRIC, Open Database License)";

    private static final String WCS = "https://maps.isric.org/mapserv?map=/map/%s.map&SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage"
            + "&COVERAGEID=%s&FORMAT=image/tiff&SUBSET=long(%.4f,%.4f)&SUBSET=lat(%.4f,%.4f)"
            + "&SUBSETTINGCRS=http://www.opengis.net/def/crs/EPSG/0/4326&OUTPUTCRS=http://www.opengis.net/def/crs/EPSG/0/4326";
    private static final String DEPTH_BASE = "https://files.isric.org/soilgrids/former/2017-03-10/data/";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; soil)");
    private static final double TILE = 0.25;
    /** SoilGrids' coverage: no soil map past these latitudes. */
    private static final double NORTH = 84, SOUTH = -56;
    /** Loose ground deeper than this many metres is drawn this deep (the deepest basins hold hundreds). */
    public static final double MAX_DEPTH_M = 30;

    /**
     * What a column's ground is made of, from its soil group and texture. The codes are kept in region rasters: new ones go last.
     */
    public enum Profile {
        UNKNOWN, LOAM, PODZOL, PEAT, SANDY, CLAY, WET, RED, GRAVELLY, DRY, FROZEN, VOLCANIC, ALLUVIAL;

        private static final Profile[] ALL = values();

        /** The profile of a packed soil code (see {@link #pack}). */
        public static Profile of(int code) {
            code &= 0x0F;
            return code > 0 && code < ALL.length ? ALL[code] : UNKNOWN;
        }
    }

    /** The subsoil's texture (30 to 60 cm), which the deeper loose ground follows. Codes kept in region rasters: new ones go last. */
    public enum Texture {
        UNKNOWN, LOAM, SANDY, CLAY, GRAVELLY;

        private static final Texture[] ALL = values();

        /** The texture of a packed soil code. */
        public static Texture of(int code) {
            code = (code >> 4) & 0x0F;
            return code > 0 && code < ALL.length ? ALL[code] : UNKNOWN;
        }
    }

    /** A profile and a texture in one byte (profile in the low four bits). */
    public static int pack(Profile p, Texture t) {
        return p.ordinal() | t.ordinal() << 4;
    }

    // The 30 reference soil groups in SoilGrids' MostProbable map, in its order (alphabetical, 0 = Acrisols; 0 is also
    // what the map answers at sea, so it is read as unknown).
    private static final Profile[] GROUPS = {
            Profile.UNKNOWN,  // Acrisols (and no data)
            null,             // Albeluvisols: by texture
            Profile.RED,      // Alisols
            Profile.VOLCANIC, // Andosols
            Profile.SANDY,    // Arenosols
            Profile.DRY,      // Calcisols
            null,             // Cambisols
            null,             // Chernozems
            Profile.FROZEN,   // Cryosols
            Profile.DRY,      // Durisols
            Profile.RED,      // Ferralsols
            Profile.ALLUVIAL, // Fluvisols
            Profile.WET,      // Gleysols
            Profile.DRY,      // Gypsisols
            Profile.PEAT,     // Histosols
            null,             // Kastanozems
            Profile.GRAVELLY, // Leptosols
            Profile.RED,      // Lixisols
            null,             // Luvisols
            Profile.RED,      // Nitisols
            null,             // Phaeozems
            Profile.WET,      // Planosols
            Profile.RED,      // Plinthosols
            Profile.PODZOL,   // Podzols
            null,             // Regosols
            Profile.DRY,      // Solonchaks
            Profile.DRY,      // Solonetz
            Profile.WET,      // Stagnosols
            null,             // Umbrisols
            Profile.CLAY,     // Vertisols
    };

    private final Path tileDir;
    private final RowTiff soilDepth;
    private final RowTiff rockDepth;
    private volatile long failedUntil;
    private final Map<String, Tile> tiles = java.util.Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tile> e) {
            return size() > 48;
        }
    });

    public Soils(Path tileDir, Path depthDir) {
        this.tileDir = tileDir;
        this.soilDepth = new RowTiff(DEPTH_BASE + "BDRICM_M_250m_ll.tif", depthDir.resolve("bdricm"));
        this.rockDepth = new RowTiff(DEPTH_BASE + "BDTICM_M_250m_ll.tif", depthDir.resolve("bdticm"));
    }

    public static boolean covers(double south, double north) {
        return north > SOUTH && south < NORTH;
    }

    /** The soil map of one box, read for sampling column by column. */
    public final class Area {
        private final Tile[] tilesHere;
        private final RowTiff.Window soil, rock;

        private Area(Tile[] tilesHere, RowTiff.Window soil, RowTiff.Window rock) {
            this.tilesHere = tilesHere;
            this.soil = soil;
            this.rock = rock;
        }

        public boolean hasProfiles() {
            return tilesHere != null;
        }

        public boolean hasDepth() {
            return soil != null;
        }

        /** The ground's make-up at a place, packed ({@link #pack}; 0 where the map has nothing). */
        public int soilAt(double lat, double lon) {
            if (tilesHere == null) return 0;
            for (Tile t : tilesHere) {
                if (t != null && t.contains(lat, lon)) return t.soilAt(lat, lon);
            }
            return 0;
        }

        /**
         * Metres of loose ground over the rock at a place (soil, and where no rock lies within 2 m the sediment under it,
         * up to {@link #MAX_DEPTH_M}), or NaN where unknown. Bilinear between the 250 m pixels.
         */
        public double depthAt(double lat, double lon) {
            if (soil == null) return Double.NaN;
            double s = bilinear(soil, lat, lon);
            if (Double.isNaN(s)) return Double.NaN;
            s /= 100.0;
            if (s >= 1.95 && rock != null) {
                double total = bilinear(rock, lat, lon);
                if (!Double.isNaN(total)) return Math.max(s, Math.min(MAX_DEPTH_M, total / 100.0));
            }
            return s;
        }
    }

    private static double bilinear(RowTiff.Window w, double lat, double lon) {
        double fx = (lon - w.x0()) / w.dx() - 0.5 - w.col0();
        double fy = (w.y0() - lat) / w.dy() - 0.5 - w.row0();
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        double sum = 0, wsum = 0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                int x = x0 + i, y = y0 + j;
                if (x < 0 || y < 0 || x >= w.cols() || y >= w.rows()) continue;
                int v = w.v()[y * w.cols() + x];
                if (v == Integer.MIN_VALUE) continue;
                double wt = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
                sum += v * wt;
                wsum += wt;
            }
        }
        return wsum > 1e-9 ? sum / wsum : Double.NaN;
    }

    /**
     * Reads what a box needs (the soil tiles touching it and the depth rows across it), or null when nothing could be
     * read. A part that fails is left out (the ground there is drawn as before).
     */
    public Area area(double south, double west, double north, double east) {
        if (!covers(south, north)) return null;
        if (System.currentTimeMillis() < failedUntil) return null;
        Tile[] t = null;
        RowTiff.Window soil = null, rock = null;
        try {
            int i0 = (int) Math.floor(south / TILE), i1 = (int) Math.floor(north / TILE);
            int j0 = (int) Math.floor(west / TILE), j1 = (int) Math.floor(east / TILE);
            t = new Tile[(i1 - i0 + 1) * (j1 - j0 + 1)];
            int k = 0;
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) t[k++] = tile(i, j);
            soil = soilDepth.window(south, west, north, east);
            if (soil != null && anyDeep(soil)) rock = rockDepth.window(south, west, north, east);
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Soil map unavailable (" + e.getMessage() + "); the ground is drawn as before for 10 minutes");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
        return new Area(t, soil, rock);
    }

    private static boolean anyDeep(RowTiff.Window w) {
        for (int v : w.v()) if (v != Integer.MIN_VALUE && v >= 195) return true;
        return false;
    }

    // ------------------------------------------------------------------ soil tiles

    /** One quarter-degree tile: the soil group and subsoil texture grids. */
    private static final class Tile {
        final double south, west;
        final Grid group, sand, clay, stones;

        Tile(double south, double west, Grid group, Grid sand, Grid clay, Grid stones) {
            this.south = south;
            this.west = west;
            this.group = group;
            this.sand = sand;
            this.clay = clay;
            this.stones = stones;
        }

        boolean contains(double lat, double lon) {
            return lat >= south && lat < south + TILE && lon >= west && lon < west + TILE;
        }

        int soilAt(double lat, double lon) {
            int g = group.at(lat, lon);
            int sa = sand.at(lat, lon), cl = clay.at(lat, lon), st = stones.at(lat, lon);
            // By the subsoil's texture (per mille); none at sea, on lakes and ice, and in towns.
            Texture t = sa <= 0 && cl <= 0 ? Texture.UNKNOWN : st >= 350 ? Texture.GRAVELLY : sa >= 650 ? Texture.SANDY
                    : cl >= 400 ? Texture.CLAY : Texture.LOAM;
            Profile p;
            if (g <= 0) {
                // 0 is Acrisols where there is soil (red earths of the warm, wet latitudes) and no data elsewhere: the map
                // answers 0 along glacier edges in Norway too.
                p = t != Texture.UNKNOWN && Math.abs(lat) < 38 ? Profile.RED : Profile.UNKNOWN;
            } else {
                p = g < GROUPS.length ? GROUPS[g] : Profile.UNKNOWN;
                if (p == null) p = switch (t) {
                    case GRAVELLY -> Profile.GRAVELLY;
                    case SANDY -> Profile.SANDY;
                    case CLAY -> Profile.CLAY;
                    default -> Profile.LOAM;
                };
            }
            return p == Profile.UNKNOWN ? 0 : pack(p, t);
        }
    }

    /** A small grid in lat/lon (a WCS answer), -1 where it has no value. */
    private record Grid(int w, int h, double x0, double y0, double dx, double dy, short[] v) {
        int at(double lat, double lon) {
            int col = (int) Math.floor((lon - x0) / dx), row = (int) Math.floor((y0 - lat) / dy);
            if (col < 0 || row < 0 || col >= w || row >= h) return -1;
            return v[row * w + col];
        }
    }

    private Tile tile(int i, int j) throws IOException, InterruptedException {
        String key = i + "_" + j;
        Tile t = tiles.get(key);
        if (t != null) return t;
        double south = i * TILE, west = j * TILE;
        Path file = tileDir.resolve(String.format(Locale.ROOT, "%.2f_%.2f.bin.gz", south, west));
        try {
            if (Files.exists(file)) t = load(file, south, west);
        } catch (IOException e) {
            System.err.println("[orbis] Soil tile " + file.getFileName() + " unreadable, fetching again: " + e.getMessage());
        }
        if (t == null) {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            Grid group = fetch("wrb", "MostProbable", south, west);
            Grid sand = fetch("sand", "sand_30-60cm_mean", south, west);
            Grid clay = fetch("clay", "clay_30-60cm_mean", south, west);
            Grid stones = fetch("cfvo", "cfvo_30-60cm_mean", south, west);
            t = new Tile(south, west, group, sand, clay, stones);
            Files.createDirectories(tileDir);
            save(file, t);
        }
        tiles.put(key, t);
        return t;
    }

    private static Grid fetch(String map, String coverage, double south, double west) throws IOException, InterruptedException {
        String url = String.format(Locale.ROOT, WCS, map, coverage, west, west + TILE, south, south + TILE);
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 60);
                if (r.status() == 200 && r.body().length > 8 && r.body()[0] == 'I') return decode(r.body());
                last = new IOException("HTTP " + r.status() + " for " + coverage);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1500L << attempt);
        }
        throw last;
    }

    /** A WCS GeoTIFF answer: little-endian, tiles or strips, deflate or none, horizontal predictor or none, 8 or 16 bits. */
    static Grid decode(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(2) != 42) throw new IOException("not a TIFF");
        int ifd = b.getInt(4);
        int n = b.getShort(ifd) & 0xFFFF;
        int width = 0, height = 0, bits = 8, format = 1, compression = 1, predictor = 1, tileW = 0, tileH = 0, rowsPerStrip = 0;
        long[] offsets = null, counts = null;
        double[] scale = null, tie = null;
        for (int k = 0; k < n; k++) {
            int p = ifd + 2 + 12 * k;
            int tag = b.getShort(p) & 0xFFFF, type = b.getShort(p + 2) & 0xFFFF, count = b.getInt(p + 4);
            int size = type == 3 ? 2 : type == 12 ? 8 : type == 1 || type == 2 ? 1 : 4;
            int at = size * count <= 4 ? p + 8 : b.getInt(p + 8);
            switch (tag) {
                case 256 -> width = num(b, at, type);
                case 257 -> height = num(b, at, type);
                case 258 -> bits = num(b, at, type);
                case 259 -> compression = num(b, at, type);
                case 278 -> rowsPerStrip = num(b, at, type);
                case 317 -> predictor = num(b, at, type);
                case 322 -> tileW = num(b, at, type);
                case 323 -> tileH = num(b, at, type);
                case 339 -> format = num(b, at, type);
                case 273, 324, 279, 325 -> {
                    long[] a = new long[count];
                    for (int q = 0; q < count; q++) a[q] = num(b, at + q * size, type) & 0xFFFFFFFFL;
                    if (tag == 273 || tag == 324) offsets = a;
                    else counts = a;
                }
                case 33550 -> scale = new double[]{b.getDouble(at), b.getDouble(at + 8)};
                case 33922 -> tie = new double[]{b.getDouble(at + 24), b.getDouble(at + 32)};
                default -> { }
            }
        }
        if (offsets == null || counts == null || scale == null || tie == null || (bits != 8 && bits != 16)) throw new IOException("unexpected TIFF");
        boolean tiled = tileW > 0;
        int bw = tiled ? tileW : width, bh = tiled ? tileH : (rowsPerStrip > 0 ? Math.min(rowsPerStrip, height) : height);
        int across = (width + bw - 1) / bw;
        int bytesPer = bits / 8;
        short[] v = new short[width * height];
        for (int k = 0; k < offsets.length; k++) {
            byte[] raw = java.util.Arrays.copyOfRange(bytes, (int) offsets[k], (int) (offsets[k] + counts[k]));
            byte[] data = compression == 8 ? inflate(raw, bw * bh * bytesPer) : raw;
            ByteBuffer d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int by = tiled ? (k / across) * bh : k * bh, bx = tiled ? (k % across) * bw : 0;
            for (int y = 0; y < bh; y++) {
                int prev = 0;
                for (int x = 0; x < bw; x++) {
                    int pos = (y * bw + x) * bytesPer;
                    if (pos + bytesPer > data.length) break;
                    int val = bits == 8 ? (format == 2 ? d.get(pos) : d.get(pos) & 0xFF) : (format == 2 ? d.getShort(pos) : d.getShort(pos) & 0xFFFF);
                    if (predictor == 2) {
                        val = bits == 8 ? (format == 2 ? (byte) (prev + val) : (prev + val) & 0xFF) : (format == 2 ? (short) (prev + val) : (prev + val) & 0xFFFF);
                        prev = val;
                    }
                    int gx = bx + x, gy = by + y;
                    if (gx < width && gy < height) v[gy * width + gx] = (short) val;
                }
            }
        }
        return new Grid(width, height, tie[0], tie[1], scale[0], scale[1], v);
    }

    private static int num(ByteBuffer b, int at, int type) {
        return type == 3 ? b.getShort(at) & 0xFFFF : type == 1 ? b.get(at) & 0xFF : b.getInt(at);
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

    private static void save(Path file, Tile t) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(1);
            for (Grid g : new Grid[]{t.group, t.sand, t.clay, t.stones}) {
                out.writeInt(g.w());
                out.writeInt(g.h());
                out.writeDouble(g.x0());
                out.writeDouble(g.y0());
                out.writeDouble(g.dx());
                out.writeDouble(g.dy());
                for (short s : g.v()) out.writeShort(s);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Tile load(Path file, double south, double west) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != 1) throw new IOException("unknown tile version");
            Grid[] g = new Grid[4];
            for (int k = 0; k < 4; k++) {
                int w = in.readInt(), h = in.readInt();
                if (w < 0 || h < 0 || (long) w * h > 4_000_000) throw new IOException("bad grid size");
                double x0 = in.readDouble(), y0 = in.readDouble(), dx = in.readDouble(), dy = in.readDouble();
                short[] v = new short[w * h];
                for (int q = 0; q < v.length; q++) v[q] = in.readShort();
                g[k] = new Grid(w, h, x0, y0, dx, dy, v);
            }
            return new Tile(south, west, g[0], g[1], g[2], g[3]);
        }
    }
}
