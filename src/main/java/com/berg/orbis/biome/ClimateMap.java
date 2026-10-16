package com.berg.orbis.biome;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;

/**
 * The real climate of every place on land, shipped with the mod ({@code orbis-climate.bin.gz}, made by
 * {@code tools/climate_pack.py}): Beck et al.'s Köppen-Geiger classes for 1991-2020 (CC BY 4.0) at 0.05°, and from CHELSA
 * v2.1 (1981-2010, CC0) at 0.1° the mean annual and coldest-quarter temperatures reduced to sea level, the annual
 * precipitation and each cell's mean elevation. Read once (about 25 MB in memory: the climate classes are kept as runs per
 * row, as most of each row is sea or one class). Over the sea, and on coasts where a
 * cell has no land, the nearest land cell within a few cells answers.
 */
public final class ClimateMap {
    public static final String ATTRIBUTION = "Köppen-Geiger classes: Beck et al. 2023 (CC BY 4.0); temperature and precipitation: "
            + "CHELSA v2.1, Karger et al. (CC0)";

    /** Köppen-Geiger classes in Beck's numbering (1 Af ... 30 EF). */
    public static final int AF = 1, AM = 2, AW = 3, BWH = 4, BWK = 5, BSH = 6, BSK = 7, CSA = 8, CSB = 9, CSC = 10, CWA = 11, CWB = 12,
            CWC = 13, CFA = 14, CFB = 15, CFC = 16, DSA = 17, DSB = 18, DSC = 19, DSD = 20, DWA = 21, DWB = 22, DWC = 23, DWD = 24,
            DFA = 25, DFB = 26, DFC = 27, DFD = 28, ET = 29, EF = 30;

    private static volatile ClimateMap loaded;
    private static volatile boolean missing;

    private record Grid(int w, int h, double west, double north, double step, byte[] v) {
        int at(int col, int row) {
            if (row < 0 || row >= h) return 255;
            return v[row * w + Math.floorMod(col, w)] & 0xFF;
        }
    }

    /** A byte grid kept as runs per row: {start column, class} pairs, each run lasting to the next one's start. */
    private record Runs(int w, int h, double west, double north, double step, char[][] rows) {
        static Runs of(Grid g) {
            char[][] rows = new char[g.h][];
            char[] buf = new char[256];
            for (int r = 0; r < g.h; r++) {
                int n = 0, prev = -1;
                for (int c = 0; c < g.w; c++) {
                    int v = g.v[r * g.w + c] & 0xFF;
                    if (v == prev) continue;
                    if (n + 2 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[n++] = (char) c;
                    buf[n++] = (char) v;
                    prev = v;
                }
                rows[r] = java.util.Arrays.copyOf(buf, n);
            }
            return new Runs(g.w, g.h, g.west, g.north, g.step, rows);
        }

        int at(int col, int row) {
            if (row < 0 || row >= h) return 0;
            col = Math.floorMod(col, w);
            char[] runs = rows[row];
            int lo = 0, hi = runs.length / 2 - 1, found = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (runs[mid * 2] <= col) {
                    found = runs[mid * 2 + 1];
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }
    }

    private final Runs koppen;
    private final Grid meanT, coldT, precip, elev;

    private ClimateMap(Runs koppen, Grid meanT, Grid coldT, Grid precip, Grid elev) {
        this.koppen = koppen;
        this.meanT = meanT;
        this.coldT = coldT;
        this.precip = precip;
        this.elev = elev;
    }

    /** The pack shipped with the mod (read once), or null when it is missing. */
    public static ClimateMap get() {
        ClimateMap m = loaded;
        if (m != null || missing) return m;
        synchronized (ClimateMap.class) {
            if (loaded != null || missing) return loaded;
            try (InputStream raw = ClimateMap.class.getResourceAsStream("/orbis-climate.bin.gz")) {
                if (raw == null) {
                    missing = true;
                    return null;
                }
                DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw), 1 << 16));
                byte[] head = new byte[8];
                in.readFully(head);
                if (head[0] != 'O' || head[1] != 'R' || head[2] != 'C' || head[3] != 'L') throw new IOException("not a climate pack");
                Grid[] g = new Grid[5];
                for (int i = 0; i < 5; i++) {
                    byte[] h = new byte[32];
                    in.readFully(h);
                    ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                    int w = b.getInt(0), rows = b.getInt(4);
                    double west = b.getDouble(8), north = b.getDouble(16), step = b.getDouble(24);
                    if (w <= 0 || rows <= 0 || (long) w * rows > 40_000_000L) throw new IOException("bad grid");
                    byte[] v = new byte[w * rows];
                    in.readFully(v);
                    g[i] = new Grid(w, rows, west, north, step, v);
                }
                loaded = new ClimateMap(Runs.of(g[0]), g[1], g[2], g[3], g[4]);
                return loaded;
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Climate pack unreadable: " + e);
                missing = true;
                return null;
            }
        }
    }

    /** The Köppen-Geiger class at a place (the nearest land within 0.25° over water), or 0. */
    public int koppen(double lat, double lon) {
        int col = (int) Math.floor((lon - koppen.west()) / koppen.step()), row = (int) Math.floor((koppen.north() - lat) / koppen.step());
        int v = koppen.at(col, row);
        if (v > 0 && v <= 30) return v;
        for (int r = 1; r <= 5; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int u = koppen.at(col + dx, row + dz);
                    if (u > 0 && u <= 30) return u;
                }
            }
        }
        return 0;
    }

    /** Mean annual temperature at sea level (°C), NaN where unknown. */
    public double seaLevelMeanC(double lat, double lon) {
        double v = bilinear(meanT, lat, lon);
        return Double.isNaN(v) ? v : v / 2 - 40;
    }

    /** Mean temperature of the coldest quarter at sea level (°C), NaN where unknown. */
    public double seaLevelColdC(double lat, double lon) {
        double v = bilinear(coldT, lat, lon);
        return Double.isNaN(v) ? v : v / 2 - 40;
    }

    /** Annual precipitation (mm), NaN where unknown. */
    public double precipitationMm(double lat, double lon) {
        double v = bilinear(precip, lat, lon);
        return Double.isNaN(v) ? v : Math.pow(10, v / 40) - 1;
    }

    /** Mean elevation of the climate cell's land (m), NaN where unknown. */
    public double cellElevationM(double lat, double lon) {
        double v = bilinear(elev, lat, lon);
        return Double.isNaN(v) ? v : v * 25;
    }

    /** Bilinear over the cells that have a value; the nearest one within 3 cells where none of the four has. */
    private static double bilinear(Grid g, double lat, double lon) {
        double fx = (lon - g.west) / g.step - 0.5, fy = (g.north - lat) / g.step - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0, sum = 0, wsum = 0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                int v = g.at(x0 + i, y0 + j);
                if (v == 255) continue;
                double w = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
                sum += v * w;
                wsum += w;
            }
        }
        if (wsum > 1e-6) return sum / wsum;
        int cx = (int) Math.floor(fx + 0.5), cy = (int) Math.floor(fy + 0.5);
        for (int r = 1; r <= 3; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int v = g.at(cx + dx, cy + dz);
                    if (v != 255) return v;
                }
            }
        }
        return Double.NaN;
    }
}
