package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Europe's radar now: EUMETNET OPERA's maximum reflectivity composite (CIRRUS, CC BY 4.0), the radars of some thirty
 * countries (Austria and Italy are not among them) on one 1 km grid every five minutes, from the Open Radar Data
 * service's public bucket. Each map is a cloud-optimised GeoTIFF in 512-pixel tiles: its tile table is read once per map
 * (16 kB) and only the tile under the place is fetched (30-150 kB). The reflectivity becomes a rain rate by
 * Marshall-Palmer (Z = 200 R^1.6), as OPERA's own rain-rate product does. No echo is NaN in the map; outside every
 * radar's reach the fill value.
 */
final class OperaRadar {
    public static final String ATTRIBUTION = "EUMETNET OPERA radar composite (CC BY 4.0)";
    private static final String URL = "https://s3.waw3-1.cloudferro.com/openradar-24h/%1$tY/%1$tm/%1$td/OPERA/COMP/OPERA@%1$tY%1$tm%1$tdT%1$tH%1$tM@0@DBZH.tiff";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    private static final long STEP_MS = 5 * 60_000L, LOOK_AGAIN_MS = 4 * 60_000L;
    /** How far back the newest map is looked for (it is usually posted within five minutes). */
    private static final int STEPS_BACK = 6;
    private static final int WIDTH = 3800, HEIGHT = 4400, TILE = 512, ACROSS = (WIDTH + TILE - 1) / TILE;
    private static final double X0 = -500.0002714332659, Y0 = 499.9999123872258, CELL = 1000;

    // Lambert azimuthal equal-area on the WGS84 ellipsoid (EPSG method 9820), centred 55 N 10 E.
    private static final double A = 6378137.0, F = 1 / 298.257223563, E2 = 2 * F - F * F, E = Math.sqrt(E2);
    private static final double LAT0 = Math.toRadians(55), LON0 = Math.toRadians(10), FE = 1_950_000, FN = -2_100_000;
    private static final double QP = q(Math.PI / 2), RQ = A * Math.sqrt(QP / 2), BETA0 = Math.asin(q(LAT0) / QP);
    private static final double D = A * (Math.cos(LAT0) / Math.sqrt(1 - E2 * Math.sin(LAT0) * Math.sin(LAT0))) / (RQ * Math.cos(BETA0));

    /** The newest map found: its address, tile table, and when it was looked for. */
    private String mapUrl;
    private long[] offsets, counts;
    private long lookedAt;
    private final Map<String, Double> answers = new HashMap<>();

    /** Whether a place is on the composite's grid at all (its coverage inside it is the map's to say). */
    static boolean covers(double lat, double lon) {
        if (lat < 31 || lat > 72 || lon < -40 || lon > 60) return false;
        double[] px = pixel(lat, lon);
        return px[0] >= 0 && px[1] >= 0 && px[0] < WIDTH && px[1] < HEIGHT;
    }

    /** The rain rate in mm/h at a place now: 0 without an echo, NaN outside the radars' reach or with no fresh map. */
    synchronized double rateMmH(double lat, double lon) throws IOException, InterruptedException {
        if (!covers(lat, lon) || !newest()) return Double.NaN;
        double[] px = pixel(lat, lon);
        int x = (int) px[0], y = (int) px[1];
        String key = mapUrl + " " + x + "," + y;
        Double hit = answers.get(key);
        if (hit != null) return hit;
        int t = (y / TILE) * ACROSS + x / TILE;
        if (t >= offsets.length || counts[t] <= 0) return Double.NaN;
        Map<String, String> h = new HashMap<>(HEADERS);
        h.put("Range", "bytes=" + offsets[t] + "-" + (offsets[t] + counts[t] - 1));
        OrbisHttp.Response resp = OrbisHttp.get(mapUrl, h, 45);
        if ((resp.status() != 206 && resp.status() != 200) || resp.body() == null) return Double.NaN;
        byte[] raw = resp.status() == 200 ? java.util.Arrays.copyOfRange(resp.body(), (int) offsets[t], (int) (offsets[t] + counts[t])) : resp.body();
        // Two float bands per pixel (reflectivity, quality), 512 x 512.
        byte[] tile = inflate(raw, TILE * TILE * 8);
        int i = ((y % TILE) * TILE + (x % TILE)) * 8;
        float dbz = ByteBuffer.wrap(tile, i, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        double rate;
        if (Float.isNaN(dbz)) rate = 0; // inside a radar's reach, no echo
        else if (dbz < -1e5) rate = Double.NaN; // outside every radar's reach
        else rate = Math.pow(Math.pow(10, dbz / 10.0) / 200.0, 1 / 1.6);
        if (answers.size() > 200) answers.clear();
        answers.put(key, rate);
        return rate;
    }

    /** Finds the newest map and reads its tile table; false when none of the last half hour can be had. */
    private boolean newest() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (now - lookedAt < LOOK_AGAIN_MS) return mapUrl != null;
        lookedAt = now;
        long slot = now / STEP_MS * STEP_MS;
        for (int k = 0; k <= STEPS_BACK; k++) {
            ZonedDateTime t = Instant.ofEpochMilli(slot - k * STEP_MS).atZone(ZoneOffset.UTC);
            String url = String.format(Locale.ROOT, URL, t);
            if (url.equals(mapUrl)) return true; // still the newest
            Map<String, String> h = new HashMap<>(HEADERS);
            h.put("Range", "bytes=0-16383");
            OrbisHttp.Response resp = OrbisHttp.get(url, h, 30);
            if (resp.status() != 206 && resp.status() != 200 || resp.body() == null) continue;
            if (readTable(resp.body())) {
                mapUrl = url;
                answers.clear();
                return true;
            }
        }
        mapUrl = null;
        return false;
    }

    /** The first image's tile offsets and byte counts (TIFF tags 324 and 325, in the first 16 kB). */
    private boolean readTable(byte[] head) {
        if (head.length < 16 || head[0] != 'I' || head[1] != 'I' || head[2] != 42) return false;
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        if (ifd + 2 > head.length) return false;
        int n = b.getShort(ifd) & 0xffff;
        long[] offs = null, cnts = null;
        for (int k = 0; k < n; k++) {
            int e = ifd + 2 + 12 * k;
            if (e + 12 > head.length) return false;
            int tag = b.getShort(e) & 0xffff, type = b.getShort(e + 2) & 0xffff, count = b.getInt(e + 4), at = b.getInt(e + 8);
            if (tag != 324 && tag != 325) continue;
            if (type != 4 || at + 4L * count > head.length) return false;
            long[] v = new long[count];
            for (int i = 0; i < count; i++) v[i] = b.getInt(at + 4 * i) & 0xffffffffL;
            if (tag == 324) offs = v;
            else cnts = v;
        }
        if (offs == null || cnts == null || offs.length != cnts.length) return false;
        offsets = offs;
        counts = cnts;
        return true;
    }

    private static byte[] inflate(byte[] raw, int expected) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(raw);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inf.finished()) {
                int got = inf.inflate(out, n, expected - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n != expected) throw new IOException("OPERA tile inflated to " + n + " of " + expected + " bytes");
            return out;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
    }

    /** The place's pixel (column, row) on the composite, as fractions. */
    static double[] pixel(double latDeg, double lonDeg) {
        double beta = Math.asin(q(Math.toRadians(latDeg)) / QP), dl = Math.toRadians(lonDeg) - LON0;
        double bb = RQ * Math.sqrt(2 / (1 + Math.sin(BETA0) * Math.sin(beta) + Math.cos(BETA0) * Math.cos(beta) * Math.cos(dl)));
        double x = FE + bb * D * Math.cos(beta) * Math.sin(dl);
        double y = FN + (bb / D) * (Math.cos(BETA0) * Math.sin(beta) - Math.sin(BETA0) * Math.cos(beta) * Math.cos(dl));
        return new double[]{(x - X0) / CELL, (Y0 - y) / CELL};
    }

    private static double q(double phi) {
        double s = Math.sin(phi);
        return (1 - E2) * (s / (1 - E2 * s * s) - (1 / (2 * E)) * Math.log((1 - E * s) / (1 + E * s)));
    }
}
