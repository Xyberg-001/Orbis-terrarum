package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * The United States' radar now: NOAA's Multi-Radar/Multi-Sensor system (MRMS, public domain), every radar of the
 * country merged on a 1 km grid every two minutes. The surface precipitation rate for the lower 48 states, Alaska,
 * Hawaii, Puerto Rico with the Virgin Islands, and Guam, and for the lower 48 the lightning of the last five minutes
 * (cloud-to-ground strikes from the National Lightning Detection Network, as a density). Each map is one GRIB2 file
 * of 20-400 kB, its grid packed as a PNG image; it is fetched again when five minutes old and read row by row, so
 * only two rows of it are ever unpacked at once.
 */
final class MrmsRadar {
    private static final String BASE = "https://mrms.ncep.noaa.gov/2D/";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final long FRESH_MS = 5 * 60_000L, TOO_OLD_MS = 30 * 60_000L;
    /** The lightning counts within this many grid cells (0.01 degrees, about a kilometre) of the place: thunder is heard about that far. */
    private static final int THUNDER_CELLS = 12;

    /** The regions, by the box of their grid: the lower 48 first. */
    private record Region(String dir, double south, double west, double north, double east) {
        boolean holds(double lat, double lon) {
            return lat >= south && lat <= north && lon >= west && lon <= east;
        }
    }

    private static final Region[] REGIONS = {
            new Region("", 20.0, -130.0, 55.0, -60.0),
            new Region("ALASKA/", 50.0, -176.0, 72.0, -126.0),
            new Region("HAWAII/", 15.0, -164.0, 26.0, -151.0),
            new Region("CARIB/", 10.0, -90.0, 25.0, -60.0),
            new Region("GUAM/", 9.0, 140.0, 18.0, 150.0),
    };

    /** A downloaded map: its grid and packing, the PNG, and when it was fetched. */
    private record Grid(int ni, int nj, double lat1, double lon1, double di, double dj, double ref, double scale, byte[] png, long fetchedAt) {
    }

    private final Map<String, Grid> grids = new HashMap<>();

    /** Whether MRMS covers the place at all. */
    static boolean covers(double lat, double lon) {
        return region(lat, lon) != null;
    }

    private static Region region(double lat, double lon) {
        for (Region r : REGIONS) if (r.holds(lat, lon)) return r;
        return null;
    }

    /** The precipitation rate in mm/h at a place now; NaN off the radar (out of range, or no fresh map). */
    synchronized double rateMmH(double lat, double lon) throws IOException, InterruptedException {
        Region r = region(lat, lon);
        if (r == null) return Double.NaN;
        // A Caribbean place inside the lower 48's box (the Florida Keys' south) has no data there: try the next region.
        for (Region each : REGIONS) {
            if (!each.holds(lat, lon)) continue;
            Grid g = grid(each.dir + "PrecipRate");
            if (g == null) continue;
            double v = max(g, lat, lon, 0);
            if (!Double.isNaN(v) && v > -1) return Math.max(0, v); // -3 is "no coverage"
        }
        return Double.NaN;
    }

    /** Whether lightning struck within a dozen kilometres in the last five minutes (the lower 48 only; false elsewhere). */
    synchronized boolean lightning(double lat, double lon) throws IOException, InterruptedException {
        if (!REGIONS[0].holds(lat, lon)) return false;
        Grid g = grid("NLDN_CG_005min_AvgDensity");
        if (g == null) return false;
        double v = max(g, lat, lon, THUNDER_CELLS);
        return !Double.isNaN(v) && v > 0;
    }

    private Grid grid(String product) throws IOException, InterruptedException {
        Grid g = grids.get(product);
        long now = System.currentTimeMillis();
        if (g != null && now - g.fetchedAt < FRESH_MS) return g;
        String name = product.substring(product.lastIndexOf('/') + 1);
        String url = BASE + product + "/MRMS_" + name + ".latest.grib2.gz";
        OrbisHttp.Response resp;
        try {
            resp = OrbisHttp.get(url, HEADERS, 60);
        } catch (IOException e) {
            // The "latest" file is replaced every two minutes, and a download caught in the swap is cut short: once more.
            Thread.sleep(1500);
            resp = OrbisHttp.get(url, HEADERS, 60);
        }
        if (resp.status() != 200 || resp.body() == null) {
            grids.remove(product);
            return null;
        }
        byte[] grib;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(resp.body()))) {
            grib = in.readAllBytes();
        }
        g = parse(grib, now);
        if (g == null) grids.remove(product);
        else grids.put(product, g);
        return g;
    }

    /**
     * The GRIB2 message's grid (template 3.0, a regular lat/lon grid scanned from the north-west corner), packing
     * (template 5.41: the values as a PNG, value = (R + X * 2^E) / 10^D) and PNG; null when it is not that, or older
     * than half an hour.
     */
    static Grid parse(byte[] b, long now) {
        if (b.length < 16 || b[0] != 'G' || b[1] != 'R' || b[2] != 'I' || b[3] != 'B' || b[7] != 2) return null;
        ByteBuffer buf = ByteBuffer.wrap(b);
        int p = 16;
        int ni = 0, nj = 0;
        double lat1 = 0, lon1 = 0, di = 0, dj = 0, ref = 0, scale = 1;
        byte[] png = null;
        boolean template = true;
        while (p + 5 <= b.length && !(b[p] == '7' && b[p + 1] == '7' && b[p + 2] == '7' && b[p + 3] == '7')) {
            int len = buf.getInt(p), num = b[p + 4];
            if (len <= 0 || p + len > b.length) return null;
            switch (num) {
                case 1 -> {
                    LocalDateTime t = LocalDateTime.of(buf.getShort(p + 12), b[p + 14], b[p + 15], b[p + 16], b[p + 17], b[p + 18]);
                    if (now - t.toInstant(ZoneOffset.UTC).toEpochMilli() > TOO_OLD_MS) return null;
                }
                case 3 -> {
                    template &= buf.getShort(p + 12) == 0;
                    ni = buf.getInt(p + 30);
                    nj = buf.getInt(p + 34);
                    lat1 = buf.getInt(p + 46) / 1e6;
                    lon1 = (buf.getInt(p + 50) & 0xffffffffL) / 1e6;
                    di = buf.getInt(p + 63) / 1e6;
                    dj = buf.getInt(p + 67) / 1e6;
                    template &= (b[p + 71] & 0xff) == 0; // west to east, north to south
                }
                case 5 -> {
                    template &= buf.getShort(p + 9) == 41;
                    ref = buf.getFloat(p + 11);
                    int e = signed(buf.getShort(p + 15)), d = signed(buf.getShort(p + 17));
                    scale = Math.pow(2, e) / Math.pow(10, d);
                    ref = ref / Math.pow(10, d);
                }
                case 7 -> {
                    png = new byte[len - 5];
                    System.arraycopy(b, p + 5, png, 0, png.length);
                }
                default -> {
                }
            }
            p += len;
        }
        if (!template || png == null || ni <= 0 || nj <= 0) return null;
        if (lon1 > 180) lon1 -= 360;
        return new Grid(ni, nj, lat1, lon1, di, dj, ref, scale, png, now);
    }

    /** GRIB2's sign-and-magnitude 16-bit integers. */
    private static int signed(short v) {
        return (v & 0x8000) != 0 ? -(v & 0x7fff) : v;
    }

    /** The greatest value within {@code cells} of the place (0: the place's own cell); NaN off the grid or unreadable. */
    static double max(Grid g, double lat, double lon, int cells) {
        int col = (int) Math.round((lon - g.lon1) / g.di), row = (int) Math.round((g.lat1 - lat) / g.dj);
        int r0 = Math.max(0, row - cells), r1 = Math.min(g.nj - 1, row + cells);
        int c0 = Math.max(0, col - cells), c1 = Math.min(g.ni - 1, col + cells);
        if (r0 > r1 || c0 > c1) return Double.NaN;
        try {
            int[] best = {Integer.MIN_VALUE};
            boolean read = rows(g.png, r1, (r, line, bytes) -> {
                if (r < r0) return;
                for (int c = c0; c <= c1; c++) {
                    int x = bytes == 1 ? line[c] & 0xff : (line[2 * c] & 0xff) << 8 | (line[2 * c + 1] & 0xff);
                    best[0] = Math.max(best[0], x);
                }
            });
            if (!read || best[0] == Integer.MIN_VALUE) return Double.NaN;
            return g.ref + best[0] * g.scale;
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
    }

    interface RowSink {
        void row(int r, byte[] line, int bytesPerPixel);
    }

    /**
     * Reads a PNG (greyscale, RGB or RGBA, 8 or 16 bits, not interlaced) row by row up to row {@code last}, undoing each
     * row's filter against the one before; the sink gets the bytes per pixel. False when it is not such an image.
     */
    static boolean rows(byte[] png, int last, RowSink sink) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(png);
        if (png.length < 33 || buf.getLong(0) != 0x89504E470D0A1A0AL) return false;
        int width = 0, depth = 0, type = -1, interlace = 0;
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream(png.length);
        for (int p = 8; p + 8 <= png.length; ) {
            int len = buf.getInt(p);
            int chunk = buf.getInt(p + 4);
            if (len < 0 || p + 12 + len > png.length) break;
            if (chunk == 0x49484452) { // IHDR
                width = buf.getInt(p + 8);
                depth = png[p + 16] & 0xff;
                type = png[p + 17] & 0xff;
                interlace = png[p + 20] & 0xff;
            } else if (chunk == 0x49444154) { // IDAT
                idat.write(png, p + 8, len);
            } else if (chunk == 0x49454E44) { // IEND
                break;
            }
            p += 12 + len;
        }
        int channels = type == 0 ? 1 : type == 2 ? 3 : type == 6 ? 4 : 0;
        if (channels == 0 || interlace != 0 || (depth != 8 && depth != 16) || width <= 0) return false;
        int bpp = channels * depth / 8, stride = width * bpp;
        byte[] prev = new byte[stride], line = new byte[stride];
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(idat.toByteArray()), new java.util.zip.Inflater(), 1 << 16)) {
            for (int r = 0; r <= last; r++) {
                int filter = in.read();
                if (filter < 0 || in.readNBytes(line, 0, stride) != stride) return false;
                unfilter(filter, line, prev, bpp);
                sink.row(r, line, bpp);
                byte[] t = prev;
                prev = line;
                line = t;
            }
        }
        return true;
    }

    private static void unfilter(int filter, byte[] line, byte[] prev, int bpp) {
        int n = line.length;
        switch (filter) {
            case 0 -> {
            }
            case 1 -> {
                for (int i = bpp; i < n; i++) line[i] += line[i - bpp];
            }
            case 2 -> {
                for (int i = 0; i < n; i++) line[i] += prev[i];
            }
            case 3 -> {
                for (int i = 0; i < n; i++) {
                    int a = i >= bpp ? line[i - bpp] & 0xff : 0;
                    line[i] += (byte) ((a + (prev[i] & 0xff)) >>> 1);
                }
            }
            case 4 -> {
                for (int i = 0; i < n; i++) {
                    int a = i >= bpp ? line[i - bpp] & 0xff : 0, up = prev[i] & 0xff, c = i >= bpp ? prev[i - bpp] & 0xff : 0;
                    int pp = a + up - c, pa = Math.abs(pp - a), pb = Math.abs(pp - up), pc = Math.abs(pp - c);
                    line[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? up : c);
                }
            }
            default -> throw new IllegalStateException("PNG filter " + filter);
        }
    }
}
