package com.berg.orbis.osm;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Buildings OpenStreetMap does not have yet, from the Overture Maps Foundation's buildings theme (ODbL): the footprints it
 * took from Google Open Buildings, Microsoft's ML buildings and other sources besides OSM, with their height, floors and
 * roof shape where known. Read from Overture's PMTiles archive (vector tiles to zoom 14) by range requests: the archive's
 * directories once, each zoom-14 tile once (kept on disk as it came: the first release a tile was read from stays, so a
 * world's regions agree). A tile cuts a building at its edge; the pieces are trimmed to their own tile and joined again
 * by the building's id. Only where OSM is thin: a tile's other footprints are used when they are at least 15 % of its
 * buildings (0-5 % in Bergen, Paris, Amsterdam, Kansas City and São Paulo, where they were mostly plazas and slivers
 * along mapped roofs; 20-66 % in Lagos, Nairobi and Delhi). A failure is not kept (asked again after 10 minutes).
 */
public final class OvertureBuildings {
    public static final String ATTRIBUTION = "Overture Maps Foundation buildings (ODbL): footprints from Google Open Buildings, "
            + "Microsoft ML Buildings and others where OpenStreetMap has none";

    private static final String CATALOG = "https://stac.overturemaps.org/catalog.json";
    private static final String ARCHIVE = "https://tiles.overturemaps.org/%s/buildings.pmtiles";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; buildings)");
    private static final int Z = 14;
    /** Ids of these buildings, apart from OSM's (way and relation ids stay far below 2^40). */
    private static final long ID_BASE = 0x4000_0000_0000_0000L;
    /** A tile's footprints from other sources are used when they are at least this share of its buildings. */
    private static final double MIN_OTHER_SHARE = 0.15;

    private final Path cacheDir;
    private volatile Archive archive;
    private volatile long failedUntil;
    private final Map<Long, CachedDir> leaves = new ConcurrentHashMap<>();

    private record Archive(String url, long rootOffset, long rootLength, long leafOffset, long dataOffset, List<long[]> root) {
    }

    private record CachedDir(List<long[]> entries) {
    }

    public OvertureBuildings(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * The buildings not from OSM touching the box (whole, however many tiles they cross), as areas tagged like OSM's
     * buildings, or null when the archive could not be read.
     */
    public List<OsmArea> buildings(double south, double west, double north, double east) {
        if (System.currentTimeMillis() < failedUntil) return null;
        try {
            int x0 = tileX(west), x1 = tileX(east), y0 = tileY(north), y1 = tileY(south);
            Map<String, Piece> byId = new LinkedHashMap<>();
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    byte[] mvt = tile(x, y);
                    if (mvt.length > 0) decode(mvt, x, y, byId);
                }
            }
            List<OsmArea> out = new ArrayList<>();
            for (Map.Entry<String, Piece> e : byId.entrySet()) {
                Piece p = e.getValue();
                if (p.outers.isEmpty() || !p.thinOsm) continue;
                long id = ID_BASE | (stableHash(e.getKey()) & 0x3FFF_FFFF_FFFF_FFFFL);
                out.add(new OsmArea(id, p.outers, p.inners, p.tags));
            }
            return out;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Overture buildings unavailable (" + e.getMessage() + "); only OpenStreetMap's buildings for 10 minutes");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    private static int tileX(double lon) {
        return Math.max(0, Math.min((1 << Z) - 1, (int) Math.floor((lon + 180) / 360 * (1 << Z))));
    }

    private static int tileY(double lat) {
        double r = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        return Math.max(0, Math.min((1 << Z) - 1, (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << Z))));
    }

    // ------------------------------------------------------------------ tiles

    /** One zoom-14 tile's vector tile (inflated), empty where the archive has none. */
    private byte[] tile(int x, int y) throws IOException, InterruptedException {
        Path file = cacheDir.resolve("tiles").resolve(String.valueOf(x)).resolve(y + ".mvt.gz");
        byte[] gz;
        if (Files.exists(file)) {
            gz = Files.readAllBytes(file);
        } else {
            Archive a = archive();
            long[] where = find(a, tileId(Z, x, y));
            gz = where == null ? new byte[0] : range(a.url, a.dataOffset + where[0], where[1]);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, gz);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        if (gz.length == 0) return gz;
        try {
            return gunzip(gz);
        } catch (IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /** {offset, length} of a tile's data, or null. */
    private long[] find(Archive a, long id) throws IOException, InterruptedException {
        List<long[]> dir = a.root;
        for (int depth = 0; depth < 4; depth++) {
            long[] e = null;
            int lo = 0, hi = dir.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (dir.get(mid)[0] <= id) {
                    e = dir.get(mid);
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (e == null) return null;
            long tileId = e[0], run = e[1], length = e[2], offset = e[3];
            if (run == 0) {
                dir = leaf(a, offset, length);
                continue;
            }
            return id < tileId + run ? new long[]{offset, length} : null;
        }
        return null;
    }

    private List<long[]> leaf(Archive a, long offset, long length) throws IOException, InterruptedException {
        CachedDir c = leaves.get(offset);
        if (c != null) return c.entries;
        Path file = cacheDir.resolve("dirs").resolve(offset + ".gz");
        byte[] gz;
        if (Files.exists(file) && Files.size(file) == length) {
            gz = Files.readAllBytes(file);
        } else {
            gz = range(a.url, a.leafOffset + offset, length);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, gz);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        List<long[]> entries = directory(gunzip(gz));
        leaves.put(offset, new CachedDir(entries));
        return entries;
    }

    /** The archive this installation reads: the release named in release.txt, else Overture's latest (then kept). */
    private Archive archive() throws IOException, InterruptedException {
        Archive a = archive;
        if (a != null) return a;
        synchronized (this) {
            if (archive != null) return archive;
            Path rel = cacheDir.resolve("release.txt");
            String release = Files.exists(rel) ? Files.readString(rel).trim() : "";
            if (release.isEmpty()) {
                OrbisHttp.Response r = OrbisHttp.get(CATALOG, HEADERS, 30);
                if (r.status() != 200) throw new IOException("Overture catalogue HTTP " + r.status());
                JsonObject cat = JsonParser.parseString(r.text()).getAsJsonObject();
                if (!cat.has("latest")) throw new IOException("no latest release in Overture's catalogue");
                release = cat.get("latest").getAsString();
                if (!release.matches("[0-9A-Za-z.\\-]+")) throw new IOException("odd release name");
                Files.createDirectories(cacheDir);
                Files.writeString(rel, release);
            }
            String url = String.format(ARCHIVE, release);
            byte[] h = range(url, 0, 127);
            if (h.length < 127 || !new String(h, 0, 7, StandardCharsets.US_ASCII).equals("PMTiles") || h[7] != 3) {
                throw new IOException("not a PMTiles v3 archive");
            }
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            long rootOffset = b.getLong(8), rootLength = b.getLong(16), leafOffset = b.getLong(40), dataOffset = b.getLong(56);
            if (h[97] != 2 || h[98] != 2) throw new IOException("unexpected compression");
            List<long[]> root = directory(gunzip(range(url, rootOffset, rootLength)));
            archive = new Archive(url, rootOffset, rootLength, leafOffset, dataOffset, root);
            return archive;
        }
    }

    /** A PMTiles directory: {tile id, run length, length, offset} per entry. */
    private static List<long[]> directory(byte[] b) {
        int[] p = {0};
        int n = (int) varint(b, p);
        long[][] e = new long[n][4];
        long last = 0;
        for (int i = 0; i < n; i++) {
            last += varint(b, p);
            e[i][0] = last;
        }
        for (int i = 0; i < n; i++) e[i][1] = varint(b, p);
        for (int i = 0; i < n; i++) e[i][2] = varint(b, p);
        for (int i = 0; i < n; i++) {
            long v = varint(b, p);
            e[i][3] = v == 0 && i > 0 ? e[i - 1][3] + e[i - 1][2] : v - 1;
        }
        return List.of(e);
    }

    private static long varint(byte[] b, int[] p) {
        long v = 0;
        int shift = 0;
        while (true) {
            int c = b[p[0]++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if (c < 0x80) return v;
            shift += 7;
        }
    }

    /** PMTiles' tile id: the tiles of all coarser zooms, then the position along the zoom's Hilbert curve. */
    static long tileId(int z, int x, int y) {
        long acc = 0;
        for (int i = 0; i < z; i++) acc += 1L << (2 * i);
        long n = 1L << z, d = 0;
        long tx = x, ty = y;
        for (long s = n >> 1; s > 0; s >>= 1) {
            long rx = (tx & s) > 0 ? 1 : 0, ry = (ty & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    tx = s - 1 - tx;
                    ty = s - 1 - ty;
                }
                long t = tx;
                tx = ty;
                ty = t;
            }
        }
        return acc + d;
    }

    private static byte[] range(String url, long offset, long length) throws IOException, InterruptedException {
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

    private static byte[] gunzip(byte[] b) throws IOException {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(b))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length * 4);
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ vector tiles

    /** One building's rings so far (pieces from several tiles) and its tags. */
    private static final class Piece {
        final List<List<LatLon>> outers = new ArrayList<>();
        final List<List<LatLon>> inners = new ArrayList<>();
        final Map<String, String> tags;
        /** Whether a tile it lies in has thin OSM coverage (any one: a building across a tile edge stays whole). */
        boolean thinOsm;

        Piece(Map<String, String> tags) {
            this.tags = tags;
        }
    }

    /** The "building" layer's features not from OSM, trimmed to the tile, added to their building by id. */
    private static void decode(byte[] mvt, int tx, int ty, Map<String, Piece> byId) {
        Pb tile = new Pb(mvt, 0, mvt.length);
        while (tile.next()) {
            if (tile.field != 3 || tile.wire != 2) {
                tile.skip();
                continue;
            }
            Pb layer = tile.message();
            String name = null;
            int extent = 4096;
            List<String> keys = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            List<int[]> featureSpans = new ArrayList<>();
            while (layer.next()) {
                switch (layer.field) {
                    case 1 -> name = layer.string();
                    case 2 -> featureSpans.add(layer.span());
                    case 3 -> keys.add(layer.string());
                    case 4 -> values.add(value(layer.message()));
                    case 5 -> extent = (int) layer.varint();
                    default -> layer.skip();
                }
            }
            if (!"building".equals(name)) continue;
            int osm = 0, other = 0;
            List<Object[]> found = new ArrayList<>();
            for (int[] span : featureSpans) {
                Pb f = new Pb(mvt, span[0], span[1]);
                long[] tags = null, geometry = null;
                int type = 0;
                while (f.next()) {
                    switch (f.field) {
                        case 2 -> tags = f.packed();
                        case 3 -> type = (int) f.varint();
                        case 4 -> geometry = f.packed();
                        default -> f.skip();
                    }
                }
                if (type != 3 || geometry == null || tags == null) continue;
                Map<String, Object> props = new HashMap<>();
                for (int i = 0; i + 1 < tags.length; i += 2) {
                    int k = (int) tags[i], v = (int) tags[i + 1];
                    if (k < keys.size() && v < values.size()) props.put(keys.get(k), values.get(v));
                }
                Object source = props.get("@geometry_source");
                if (source == null || "OpenStreetMap".equals(source)) {
                    osm++;
                    continue;
                }
                other++;
                if (Boolean.TRUE.equals(props.get("is_underground"))) continue;
                if (props.get("id") == null) continue;
                found.add(new Object[]{props, geometry});
            }
            boolean thin = other > 0 && other >= MIN_OTHER_SHARE * (osm + other);
            for (Object[] f : found) {
                @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) f[0];
                Piece piece = byId.computeIfAbsent(props.get("id").toString(), k -> new Piece(osmTags(props)));
                piece.thinOsm |= thin;
                addRings((long[]) f[1], extent, tx, ty, piece);
            }
        }
    }

    /** Overture's properties as the OSM tags the building code reads. */
    private static Map<String, String> osmTags(Map<String, Object> p) {
        Map<String, String> t = new HashMap<>();
        Object cls = p.get("class");
        t.put("building", cls instanceof String s && !s.isBlank() ? s : "yes");
        if (p.get("height") instanceof Number n && n.doubleValue() > 0) t.put("height", String.valueOf(n.doubleValue()));
        if (p.get("num_floors") instanceof Number n && n.intValue() > 0) t.put("building:levels", String.valueOf(n.intValue()));
        if (p.get("roof_shape") instanceof String s && !s.isBlank()) t.put("roof:shape", s);
        if (p.get("roof_height") instanceof Number n && n.doubleValue() > 0) t.put("roof:height", String.valueOf(n.doubleValue()));
        if (p.get("min_height") instanceof Number n && n.doubleValue() > 0) t.put("min_height", String.valueOf(n.doubleValue()));
        if (p.get("@name") instanceof String s && !s.isBlank()) t.put("name", s);
        t.put("source", "overture:" + p.get("@geometry_source"));
        return t;
    }

    private static Object value(Pb v) {
        Object out = null;
        while (v.next()) {
            switch (v.field) {
                case 1 -> out = v.string();
                case 2 -> out = (double) Float.intBitsToFloat(v.fixed32());
                case 3 -> out = Double.longBitsToDouble(v.fixed64());
                case 4, 5 -> out = (double) v.varint();
                case 6 -> {
                    long z = v.varint();
                    out = (double) ((z >>> 1) ^ -(z & 1));
                }
                case 7 -> out = v.varint() != 0;
                default -> v.skip();
            }
        }
        return out;
    }

    /** Decodes a polygon's rings (tile units), trims each to the tile and adds it to the piece in lat/lon. */
    private static void addRings(long[] g, int extent, int tx, int ty, Piece piece) {
        int x = 0, y = 0, i = 0;
        List<double[]> ring = null;
        while (i < g.length) {
            int cmd = (int) (g[i] & 7), count = (int) (g[i] >>> 3);
            i++;
            if (cmd == 7) { // ClosePath
                if (ring != null && ring.size() >= 3) finishRing(ring, extent, tx, ty, piece);
                ring = null;
                continue;
            }
            for (int k = 0; k < count && i + 1 < g.length; k++) {
                x += zigzag(g[i++]);
                y += zigzag(g[i++]);
                if (cmd == 1) ring = new ArrayList<>();
                if (ring != null) ring.add(new double[]{x, y});
            }
        }
    }

    private static void finishRing(List<double[]> ring, int extent, int tx, int ty, Piece piece) {
        // Exterior rings run clockwise in tile coordinates (y down): positive area.
        double area = 0;
        for (int k = 0; k < ring.size(); k++) {
            double[] a = ring.get(k), b = ring.get((k + 1) % ring.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        List<double[]> clipped = clip(ring, extent);
        if (clipped.size() < 3) return;
        List<LatLon> ll = new ArrayList<>(clipped.size());
        double n = 1 << Z;
        for (double[] p : clipped) {
            double fx = (tx + p[0] / extent) / n, fy = (ty + p[1] / extent) / n;
            double lon = fx * 360 - 180;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * fy))));
            ll.add(new LatLon(lat, lon));
        }
        if (area > 0) piece.outers.add(ll);
        else piece.inners.add(ll);
    }

    /** Sutherland-Hodgman against the tile square [0, extent]: the piece of the building inside this tile. */
    private static List<double[]> clip(List<double[]> in, int extent) {
        List<double[]> out = in;
        for (int edge = 0; edge < 4; edge++) {
            List<double[]> next = new ArrayList<>();
            int m = out.size();
            for (int k = 0; k < m; k++) {
                double[] a = out.get(k), b = out.get((k + 1) % m);
                boolean ai = inside(a, edge, extent), bi = inside(b, edge, extent);
                if (ai) next.add(a);
                if (ai != bi) next.add(cross(a, b, edge, extent));
            }
            out = next;
            if (out.size() < 3) return out;
        }
        return out;
    }

    private static boolean inside(double[] p, int edge, int extent) {
        return switch (edge) {
            case 0 -> p[0] >= 0;
            case 1 -> p[0] <= extent;
            case 2 -> p[1] >= 0;
            default -> p[1] <= extent;
        };
    }

    private static double[] cross(double[] a, double[] b, int edge, int extent) {
        double t = switch (edge) {
            case 0 -> (0 - a[0]) / (b[0] - a[0]);
            case 1 -> (extent - a[0]) / (b[0] - a[0]);
            case 2 -> (0 - a[1]) / (b[1] - a[1]);
            default -> (extent - a[1]) / (b[1] - a[1]);
        };
        return new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
    }

    private static int zigzag(long v) {
        return (int) ((v >>> 1) ^ -(v & 1));
    }

    /** A minimal protobuf reader over a slice of bytes. */
    private static final class Pb {
        final byte[] b;
        int pos;
        final int end;
        int field, wire;

        Pb(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean next() {
            if (pos >= end) return false;
            long key = varint();
            field = (int) (key >>> 3);
            wire = (int) (key & 7);
            return true;
        }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                int c = b[pos++] & 0xFF;
                v |= (long) (c & 0x7F) << shift;
                if (c < 0x80) return v;
                shift += 7;
            }
        }

        int fixed32() {
            int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
            pos += 4;
            return v;
        }

        long fixed64() {
            long lo = fixed32() & 0xFFFFFFFFL, hi = fixed32() & 0xFFFFFFFFL;
            return lo | hi << 32;
        }

        int[] span() {
            int len = (int) varint();
            int[] s = {pos, pos + len};
            pos += len;
            return s;
        }

        Pb message() {
            int[] s = span();
            return new Pb(b, s[0], s[1]);
        }

        String string() {
            int[] s = span();
            return new String(b, s[0], s[1] - s[0], StandardCharsets.UTF_8);
        }

        long[] packed() {
            int[] s = span();
            Pb p = new Pb(b, s[0], s[1]);
            long[] out = new long[16];
            int n = 0;
            while (p.pos < p.end) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                out[n++] = p.varint();
            }
            return java.util.Arrays.copyOf(out, n);
        }

        void skip() {
            switch (wire) {
                case 0 -> varint();
                case 1 -> pos += 8;
                case 2 -> pos += (int) varint();
                case 5 -> pos += 4;
                default -> pos = end;
            }
        }
    }
}
