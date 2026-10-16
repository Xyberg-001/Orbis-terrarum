package com.berg.orbis.landcover;

import com.berg.orbis.net.OrbisHttp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Whether a forest is coniferous or broadleaved, where OpenStreetMap does not say: Copernicus' CLC+ Backbone 2021 in
 * Europe (10 m, needle-leaved, broadleaved deciduous and evergreen trees; read for each region from the EEA's image
 * service), and Copernicus' global land cover forest type layer everywhere else (CGLS-LC100 2019, 100 m: evergreen or
 * deciduous, needle or broad leaf, mixed; read tile by tile from the 1 GB file on Zenodo). Both CC BY 4.0. In Norway
 * first NIBIO's forest resource map SR16 (16 m, NLOD): spruce, pine or broadleaf for each stand, so a pine forest grows
 * pine and a spruce forest spruce; its image service only draws the classes (three flat colours), so the colours are
 * read back. A failure is not kept (asked again after 10 minutes).
 */
public final class ForestTypes {
    public static final String ATTRIBUTION = "CLC+ Backbone 2021 (Copernicus Land Monitoring Service, EEA) and Copernicus Global Land "
            + "Service Land Cover 100 m 2019 forest type (Buchhorn et al., CC BY 4.0)";

    private static final String CLC = "https://image.discomap.eea.europa.eu/arcgis/rest/services/CLC_plus/CLMS_CLCplus_RASTER_2021_010m_eu/ImageServer"
            + "/exportImage?bbox=%.6f,%.6f,%.6f,%.6f&bboxSR=4326&imageSR=4326&size=%d,%d&format=tiff&pixelType=U8"
            + "&interpolation=RSP_NearestNeighbor&f=image";
    private static final String CGLS = "https://zenodo.org/api/records/3939050/files/"
            + "PROBAV_LC100_global_v3.0.1_2019-nrt_Forest-Type-layer_EPSG-4326.tif/content";
    private static final String SR16 = "https://wms.nibio.no/cgi-bin/sr16?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=SRRTRESLAG&STYLES="
            + "&CRS=EPSG:4326&BBOX=%.7f,%.7f,%.7f,%.7f&WIDTH=%d&HEIGHT=%d&FORMAT=image/png&TRANSPARENT=TRUE";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; forest types)");
    /** CLC+'s area (the EEA's 38 countries and the UK), generously. */
    private static final double EU_SOUTH = 34, EU_NORTH = 72, EU_WEST = -25, EU_EAST = 45;

    /** What the trees of a place are. */
    public enum Leaf { UNKNOWN, CONIFER, BROADLEAF, MIXED }

    /** The kind of tree a stand is made of, where a map says (Norway's SR16). The codes are kept in region rasters. */
    public enum Kind {
        UNKNOWN, SPRUCE, PINE, BROADLEAF;

        private static final Kind[] ALL = values();

        public static Kind of(int code) {
            return code > 0 && code < ALL.length ? ALL[code] : UNKNOWN;
        }
    }

    private final Path clcDir;
    private final Path sr16Dir;
    private final TileTiff world;
    private volatile long failedUntil;

    public ForestTypes(Path cacheDir) {
        this.clcDir = cacheDir.resolve("clc-plus");
        this.sr16Dir = cacheDir.resolve("sr16");
        this.world = new TileTiff(CGLS, cacheDir.resolve("cgls-lc100"));
    }

    /** One region's map, read for sampling column by column. */
    public final class Area {
        private final Grid norway;
        private final Grid europe;
        private final boolean worldReady;

        private Area(Grid norway, Grid europe, boolean worldReady) {
            this.norway = norway;
            this.europe = europe;
            this.worldReady = worldReady;
        }

        /** The stand's kind of tree from SR16 (Norway), read half a 16 m pixel off at random ({@code jx}, {@code jy} in [0, 1)). */
        public Kind kindAt(double lat, double lon, double jx, double jy) {
            if (norway == null) return Kind.UNKNOWN;
            double off = 16.0 / 110_574;
            int col = (int) Math.floor((lon + (jx - 0.5) * off / Math.max(0.2, Math.cos(Math.toRadians(lat))) - norway.west()) / norway.dx());
            int row = (int) Math.floor((norway.north() - lat - (jy - 0.5) * off) / norway.dy());
            return Kind.of(norway.at(col, row));
        }

        /**
         * The trees at a place: Europe's 10 m map, as stands (the most common kind within 30 m, so a mixed wood comes out in
         * patches, not single pixels), else the world's 100 m map read half a pixel off at random ({@code jitter} two numbers
         * in [0, 1)) so its pixels do not show as squares.
         */
        public Leaf at(double lat, double lon, double jx, double jy) {
            switch (kindAt(lat, lon, jx, jy)) {
                case SPRUCE, PINE -> {
                    return Leaf.CONIFER;
                }
                case BROADLEAF -> {
                    return Leaf.BROADLEAF;
                }
                default -> { }
            }
            if (europe != null) {
                Leaf l = europe.stand(lat, lon);
                if (l != Leaf.UNKNOWN) return l;
            }
            if (!worldReady) return Leaf.UNKNOWN;
            try {
                double px = 1 / 1008.0;
                int v = world.at(lat + (jy - 0.5) * px, lon + (jx - 0.5) * px);
                return switch (v) {
                    case 1, 3 -> Leaf.CONIFER;   // evergreen and deciduous needle leaf (larch)
                    case 2, 4 -> Leaf.BROADLEAF; // evergreen and deciduous broad leaf
                    case 5 -> Leaf.MIXED;
                    default -> Leaf.UNKNOWN;
                };
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return Leaf.UNKNOWN;
            }
        }
    }

    /** What a box needs (both maps where they cover it), or null while the sources are failing. */
    public Area area(double south, double west, double north, double east, double metresPerPixel) {
        if (System.currentTimeMillis() < failedUntil) return null;
        Grid europe = null, norway = null;
        boolean worldReady = false;
        try {
            if (com.berg.orbis.config.DataSources.covers("nibio-sr16", south, west, north, east)
                    && south < 71.6 && north > 57.6 && west < 31.5 && east > 4.0) {
                norway = sr16(south, west, north, east, metresPerPixel);
            }
            if (south < EU_NORTH && north > EU_SOUTH && west < EU_EAST && east > EU_WEST) europe = clc(south, west, north, east, metresPerPixel);
            world.prefetch(south, west, north, east);
            worldReady = true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Forest types unavailable (" + e.getMessage() + "); forests keep their mix for 10 minutes");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (europe == null && norway == null) return null;
        }
        return new Area(norway, europe, worldReady);
    }

    // ------------------------------------------------------------------ Norway (SR16)

    /** SR16's tree species, as Kind codes, on a lat/lon lattice of about 8 m (half its pixels), fixed per degree of latitude. */
    private Grid sr16(double south, double west, double north, double east, double metresPerPixel) throws IOException, InterruptedException {
        double dLat = 1 / 13750.0, dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(Math.floor((south + north) / 2))));
        int k = Math.max(1, (int) Math.ceil(metresPerPixel / 8));
        while ((east - west) / (dLon * k) > 2048 || (north - south) / (dLat * k) > 2048) k++;
        dLat *= k;
        dLon *= k;
        south = Math.floor(south / dLat) * dLat;
        north = Math.ceil(north / dLat) * dLat;
        west = Math.floor(west / dLon) * dLon;
        east = Math.ceil(east / dLon) * dLon;
        int w = Math.max(1, (int) Math.round((east - west) / dLon)), h = Math.max(1, (int) Math.round((north - south) / dLat));
        Path file = sr16Dir.resolve(String.format(Locale.ROOT, "%.5f_%.5f_%.5f_%.5f_%dx%d.bin.gz", south, west, north, east, w, h));
        try {
            if (Files.exists(file)) return load(file);
        } catch (IOException e) {
            System.err.println("[orbis] SR16 file " + file.getFileName() + " unreadable, fetching again: " + e.getMessage());
        }
        String url = String.format(Locale.ROOT, SR16, south, west, north, east, w, h); // WMS 1.3.0 in EPSG:4326: lat first
        java.awt.image.BufferedImage img = null;
        IOException last = null;
        for (int attempt = 0; attempt < 3 && img == null; attempt++) {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            try {
                OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 60);
                if (r.status() == 200 && r.body().length > 8 && r.body()[1] == 'P') img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
                else last = new IOException("SR16 HTTP " + r.status());
            } catch (IOException e) {
                last = e;
            }
            if (img == null) Thread.sleep(1500L << attempt);
        }
        if (img == null) throw last != null ? last : new IOException("SR16 image unreadable");
        if (img.getWidth() != w || img.getHeight() != h) throw new IOException("unexpected SR16 image size");
        byte[] v = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 128) continue;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                // The legend's three colours: spruce (gran) green, pine (furu) tan, broadleaf (lauv) yellow.
                Kind kind = near(r, g, b, 82, 176, 56) ? Kind.SPRUCE : near(r, g, b, 205, 170, 101) ? Kind.PINE
                        : near(r, g, b, 255, 220, 130) ? Kind.BROADLEAF : Kind.UNKNOWN;
                v[y * w + x] = (byte) kind.ordinal();
            }
        }
        Grid grid = new Grid(w, h, west, north, (east - west) / w, (north - south) / h, v);
        Files.createDirectories(sr16Dir);
        save(file, grid);
        return grid;
    }

    private static boolean near(int r, int g, int b, int r0, int g0, int b0) {
        return Math.abs(r - r0) + Math.abs(g - g0) + Math.abs(b - b0) <= 18;
    }

    // ------------------------------------------------------------------ Europe (CLC+)

    /** CLC+ classes in a lat/lon grid: 2 needle-leaved, 3 broadleaved deciduous, 4 broadleaved evergreen trees. */
    private record Grid(int w, int h, double west, double north, double dx, double dy, byte[] v) {
        int at(int col, int row) {
            return col < 0 || row < 0 || col >= w || row >= h ? 0 : v[row * w + col] & 0xFF;
        }

        Leaf stand(double lat, double lon) {
            int col = (int) Math.floor((lon - west) / dx), row = (int) Math.floor((north - lat) / dy);
            if (col < 0 || row < 0 || col >= w || row >= h) return Leaf.UNKNOWN;
            int needle = 0, broad = 0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    int c = at(col + i, row + j);
                    if (c == 2) needle++;
                    else if (c == 3 || c == 4) broad++;
                }
            }
            if (needle + broad == 0) return Leaf.UNKNOWN;
            if (needle >= 2 * broad) return Leaf.CONIFER;
            if (broad >= 2 * needle) return Leaf.BROADLEAF;
            return Leaf.MIXED;
        }
    }

    private Grid clc(double south, double west, double north, double east, double metresPerPixel) throws IOException, InterruptedException {
        // Pixels about 10 m on a lattice fixed in lat/lon (per degree of latitude), so neighbouring regions ask for the same
        // pixels and their stands meet; coarser for coarse worlds and big regions (at most 2048 a side).
        double dLat = 1 / 11000.0, dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(Math.floor((south + north) / 2))));
        int k = Math.max(1, (int) Math.ceil(metresPerPixel / 10));
        while ((east - west) / (dLon * k) > 2048 || (north - south) / (dLat * k) > 2048) k++;
        dLat *= k;
        dLon *= k;
        south = Math.floor(south / dLat) * dLat;
        north = Math.ceil(north / dLat) * dLat;
        west = Math.floor(west / dLon) * dLon;
        east = Math.ceil(east / dLon) * dLon;
        int w = Math.max(1, (int) Math.round((east - west) / dLon)), h = Math.max(1, (int) Math.round((north - south) / dLat));
        Path file = clcDir.resolve(String.format(Locale.ROOT, "%.5f_%.5f_%.5f_%.5f_%dx%d.bin.gz", south, west, north, east, w, h));
        try {
            if (Files.exists(file)) return load(file);
        } catch (IOException e) {
            System.err.println("[orbis] Forest type file " + file.getFileName() + " unreadable, fetching again: " + e.getMessage());
        }
        String url = String.format(Locale.ROOT, CLC, west, south, east, north, w, h);
        byte[] body = null;
        IOException last = null;
        for (int attempt = 0; attempt < 3 && body == null; attempt++) {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            try {
                OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 60);
                if (r.status() == 200 && r.body().length > 8 && r.body()[0] == 'I') body = r.body();
                else last = new IOException("CLC+ HTTP " + r.status());
            } catch (IOException e) {
                last = e;
            }
            if (body == null) Thread.sleep(1500L << attempt);
        }
        if (body == null) throw last;
        Grid g = new Grid(w, h, west, north, (east - west) / w, (north - south) / h, pixels(body, w, h));
        Files.createDirectories(clcDir);
        save(file, g);
        return g;
    }

    /** The pixels of an uncompressed 8-bit TIFF (strips or tiles), as the image service sends them. */
    private static byte[] pixels(byte[] bytes, int w, int h) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4), n = b.getShort(ifd) & 0xFFFF;
        int width = 0, height = 0, compression = 1, tileW = 0, tileH = 0, rowsPerStrip = 0;
        long[] offsets = null;
        for (int k = 0; k < n; k++) {
            int p = ifd + 2 + 12 * k;
            int tag = b.getShort(p) & 0xFFFF, type = b.getShort(p + 2) & 0xFFFF, count = b.getInt(p + 4);
            int size = type == 3 ? 2 : 4;
            int at = size * count <= 4 ? p + 8 : b.getInt(p + 8);
            int value = type == 3 ? b.getShort(p + 8) & 0xFFFF : b.getInt(p + 8);
            switch (tag) {
                case 256 -> width = value;
                case 257 -> height = value;
                case 259 -> compression = value;
                case 278 -> rowsPerStrip = value;
                case 322 -> tileW = value;
                case 323 -> tileH = value;
                case 273, 324 -> {
                    offsets = new long[count];
                    for (int q = 0; q < count; q++) offsets[q] = (size == 2 ? b.getShort(at + 2 * q) & 0xFFFF : b.getInt(at + 4 * q)) & 0xFFFFFFFFL;
                }
                default -> { }
            }
        }
        if (compression != 1 || offsets == null || width != w || height != h) throw new IOException("unexpected CLC+ image");
        byte[] out = new byte[w * h];
        boolean tiled = tileW > 0;
        int bw = tiled ? tileW : w, bh = tiled ? tileH : (rowsPerStrip > 0 ? rowsPerStrip : h);
        int across = (w + bw - 1) / bw;
        for (int k = 0; k < offsets.length; k++) {
            int by = tiled ? (k / across) * bh : k * bh, bx = tiled ? (k % across) * bw : 0;
            for (int y = 0; y < bh && by + y < h; y++) {
                for (int x = 0; x < bw && bx + x < w; x++) {
                    long pos = offsets[k] + (long) y * bw + x;
                    if (pos < bytes.length) out[(by + y) * w + bx + x] = bytes[(int) pos];
                }
            }
        }
        return out;
    }

    private static void save(Path file, Grid g) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeInt(g.w());
            out.writeInt(g.h());
            out.writeDouble(g.west());
            out.writeDouble(g.north());
            out.writeDouble(g.dx());
            out.writeDouble(g.dy());
            out.write(g.v());
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Grid load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != 1) throw new IOException("unknown version");
            int w = in.readInt(), h = in.readInt();
            if (w <= 0 || h <= 0 || (long) w * h > 16_000_000) throw new IOException("bad size");
            double west = in.readDouble(), north = in.readDouble(), dx = in.readDouble(), dy = in.readDouble();
            byte[] v = new byte[w * h];
            in.readFully(v);
            return new Grid(w, h, west, north, dx, dy, v);
        }
    }
}
