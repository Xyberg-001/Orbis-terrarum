package com.berg.orbis.water;

import com.berg.orbis.net.OrbisHttp;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Quebec's lake surveys (Géobase des bathymétries de lacs du Québec, GBLQ, Ministère de l'Environnement, CC BY 4.0): the
 * depth contours (isobathes) and the deepest point of about 2,200 lakes. Where each lake lies comes with the mod
 * ({@code orbis-quebec-lakes.tsv.gz}, made by {@code tools/quebec_lakes.py} from the 41 MB index); a lake's survey is
 * fetched the first time a world reaches it (one zip of a few MB per lake, from the province's open-data storage), read
 * (a GeoPackage, its contours in Quebec Lambert) and kept as soundings, a few kB, the zip thrown away. The newest survey of
 * a lake is used. A failure is not kept (asked again after 10 minutes).
 */
public final class QuebecLakes {
    public static final String ATTRIBUTION = "Géobase des bathymétries de lacs du Québec (GBLQ), Gouvernement du Québec, CC BY 4.0";

    private static final String BASE = "https://stqc380donopppdtce01.blob.core.windows.net/donnees-ouvertes/Bathymetrie/Lacs/Donnees_gpkg/";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; lake depths)");
    /** The province, generously. */
    private static final double SOUTH = 44.9, WEST = -79.8, NORTH = 63.0, EAST = -57.0;

    private record Lake(String id, String file, double south, double west, double north, double east) {
    }

    private static volatile List<Lake> index;

    private final Path cacheDir;
    private volatile long failedUntil;

    public QuebecLakes(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public static boolean covers(double south, double west, double north, double east) {
        return south < NORTH && north > SOUTH && west < EAST && east > WEST;
    }

    /** The surveyed lakes' soundings in the box (their contours' points and deepest points), or null when a survey could not be read. */
    public List<NveLakes.Sounding> soundings(double south, double west, double north, double east) {
        List<NveLakes.Sounding> out = new ArrayList<>();
        if (!covers(south, west, north, east)) return out;
        List<Lake> lakes = index();
        if (lakes == null) return out;
        for (Lake l : lakes) {
            if (l.south > north || l.north < south || l.west > east || l.east < west) continue;
            List<NveLakes.Sounding> s = lake(l);
            if (s == null) return null;
            out.addAll(s);
        }
        return out;
    }

    private static List<Lake> index() {
        List<Lake> l = index;
        if (l != null) return l;
        synchronized (QuebecLakes.class) {
            if (index != null) return index;
            List<Lake> out = new ArrayList<>();
            try (InputStream raw = QuebecLakes.class.getResourceAsStream("/orbis-quebec-lakes.tsv.gz")) {
                if (raw == null) return null;
                BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = line.split("\t");
                    if (f.length < 6 || f[0].startsWith("#")) continue;
                    out.add(new Lake(f[0], f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5])));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Quebec lake index unreadable: " + e);
                return null;
            }
            index = out;
            return out;
        }
    }

    /** One lake's soundings, read once and kept; null when they cannot be had now. */
    private List<NveLakes.Sounding> lake(Lake l) {
        Path kept = cacheDir.resolve(l.id + ".bin.gz");
        try {
            if (Files.exists(kept)) return load(kept);
        } catch (IOException e) {
            System.err.println("[orbis] Quebec lake " + l.id + " unreadable, fetching again: " + e.getMessage());
        }
        if (System.currentTimeMillis() < failedUntil) return null;
        Path gpkg = cacheDir.resolve(l.id + ".gpkg.part");
        try {
            if (com.berg.orbis.OrbisMod.stopping()) throw new IOException("stopping");
            OrbisHttp.Response r = OrbisHttp.get(BASE + l.file, HEADERS, 300);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            Files.createDirectories(cacheDir);
            boolean found = false;
            try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(r.body()))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gpkg")) {
                        Files.copy(zin, gpkg, StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) throw new IOException("no GeoPackage in the zip");
            List<NveLakes.Sounding> s = read(gpkg);
            save(kept, s);
            return load(kept); // as kept (floats), so a lake read now and one read after a restart are the same
        } catch (IOException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Quebec lake survey " + l.id + " unavailable (" + e.getMessage() + "); its bed is estimated for 10 minutes");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            try {
                Files.deleteIfExists(gpkg);
            } catch (IOException ignored) {
            }
        }
    }

    /** The newest survey's contours (every vertex, in order) and deepest points, as lat/lon soundings. */
    static List<NveLakes.Sounding> read(Path gpkg) throws IOException {
        List<NveLakes.Sounding> out = new ArrayList<>();
        try (SqliteTables db = new SqliteTables(gpkg)) {
            List<SqliteTables.Table> tables = db.tables();
            SqliteTables.Table iso = newest(tables, "iso_"), fos = newest(tables, "fos_");
            for (SqliteTables.Table t : new SqliteTables.Table[]{iso, fos}) {
                if (t == null) continue;
                int shape = t.column("Shape"), depth = t.column("PROFONDEUR_M");
                if (shape < 0) shape = t.column("geom");
                if (shape < 0 || depth < 0) continue;
                int sc = shape, dc = depth;
                db.rows(t.rootPage(), row -> {
                    if (sc >= row.length || dc >= row.length || !(row[sc] instanceof byte[] g) || !(row[dc] instanceof Number d)) return;
                    double metres = Math.abs(d.doubleValue());
                    for (double[] p : points(g)) out.add(new NveLakes.Sounding(p[0], p[1], metres));
                });
            }
        }
        return out;
    }

    /** The table of a kind ("iso_", "fos_") from the latest survey year in its name. */
    private static SqliteTables.Table newest(List<SqliteTables.Table> tables, String prefix) {
        SqliteTables.Table best = null;
        int bestYear = -1;
        for (SqliteTables.Table t : tables) {
            if (!t.name().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) continue;
            int year = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(19|20)(\\d\\d)_").matcher(t.name() + "_");
            if (m.find()) year = Integer.parseInt(m.group(1) + m.group(2));
            if (year > bestYear) {
                bestYear = year;
                best = t;
            }
        }
        return best;
    }

    /** The vertices of a GeoPackage geometry (points, lines, multi-lines), as lat/lon. */
    static List<double[]> points(byte[] g) {
        List<double[]> out = new ArrayList<>();
        if (g.length < 8 || g[0] != 'G' || g[1] != 'P') return out;
        int flags = g[3] & 0xFF;
        ByteOrder headerOrder = (flags & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int srs = ByteBuffer.wrap(g, 4, 4).order(headerOrder).getInt();
        int env = (flags >> 1) & 7;
        int at = 8 + (env == 1 ? 32 : env == 2 || env == 3 ? 48 : env == 4 ? 64 : 0);
        wkb(ByteBuffer.wrap(g), at, srs, out);
        return out;
    }

    /** Reads one WKB geometry at {@code at}; returns where it ends. */
    private static int wkb(ByteBuffer b, int at, int srs, List<double[]> out) {
        b.order(b.get(at) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        long rawType = b.getInt(at + 1) & 0xFFFFFFFFL;
        boolean ewkbZ = (rawType & 0x80000000L) != 0, ewkbM = (rawType & 0x40000000L) != 0;
        int type = (int) (rawType & 0x0FFFFFFF);
        int dims = 2;
        if (type > 3000) {
            dims = 4;
            type -= 3000;
        } else if (type > 2000 || type > 1000) {
            dims = 3;
            type -= type > 2000 ? 2000 : 1000;
        }
        if (ewkbZ) dims++;
        if (ewkbM) dims++;
        int p = at + 5;
        switch (type) {
            case 1 -> {
                out.add(toLatLon(b.getDouble(p), b.getDouble(p + 8), srs));
                return p + 8 * dims;
            }
            case 2 -> {
                int n = b.getInt(p);
                p += 4;
                for (int i = 0; i < n; i++) {
                    out.add(toLatLon(b.getDouble(p), b.getDouble(p + 8), srs));
                    p += 8 * dims;
                }
                return p;
            }
            case 4, 5, 6, 7 -> {
                int n = b.getInt(p);
                p += 4;
                for (int i = 0; i < n; i++) {
                    ByteOrder keep = b.order();
                    p = wkb(b, p, srs, out);
                    b.order(keep);
                }
                return p;
            }
            case 3 -> {
                int rings = b.getInt(p);
                p += 4;
                for (int r = 0; r < rings; r++) {
                    int n = b.getInt(p);
                    p += 4;
                    for (int i = 0; i < n; i++) {
                        out.add(toLatLon(b.getDouble(p), b.getDouble(p + 8), srs));
                        p += 8 * dims;
                    }
                }
                return p;
            }
            default -> {
                return b.limit();
            }
        }
    }

    private static double[] toLatLon(double x, double y, int srs) {
        if (srs == 4326 || srs == 4269) return new double[]{y, x};
        return quebecLambert(x, y);
    }

    // NAD83 / Quebec Lambert (EPSG:32198): Lambert conformal conic, standard parallels 60° and 46°, origin 44° N 68.5° W, GRS80.
    private static final double A = 6378137.0, F = 1 / 298.257222101, E = Math.sqrt(2 * F - F * F);
    private static final double PHI1 = Math.toRadians(60), PHI2 = Math.toRadians(46), PHI0 = Math.toRadians(44), LAM0 = Math.toRadians(-68.5);
    private static final double N_, F_, RHO0;

    static {
        double m1 = m(PHI1), m2 = m(PHI2), t1 = t(PHI1), t2 = t(PHI2), t0 = t(PHI0);
        N_ = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        F_ = m1 / (N_ * Math.pow(t1, N_));
        RHO0 = A * F_ * Math.pow(t0, N_);
    }

    private static double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - E * E * s * s);
    }

    private static double t(double phi) {
        double s = Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - E * s) / (1 + E * s), E / 2);
    }

    /** Quebec Lambert x, y (metres) to lat, lon (degrees). */
    static double[] quebecLambert(double x, double y) {
        double dy = RHO0 - y;
        double rho = Math.signum(N_) * Math.sqrt(x * x + dy * dy);
        double t = Math.pow(rho / (A * F_), 1 / N_);
        double theta = Math.atan2(x, dy);
        double lam = theta / N_ + LAM0;
        double phi = Math.PI / 2 - 2 * Math.atan(t);
        for (int i = 0; i < 8; i++) {
            double s = Math.sin(phi);
            phi = Math.PI / 2 - 2 * Math.atan(t * Math.pow((1 - E * s) / (1 + E * s), E / 2));
        }
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lam)};
    }

    private static void save(Path file, List<NveLakes.Sounding> s) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(2);
            out.writeInt(s.size());
            for (NveLakes.Sounding p : s) {
                out.writeFloat((float) p.lat()); // about a metre, as the contours were drawn
                out.writeFloat((float) p.lon());
                out.writeFloat((float) p.depthM());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<NveLakes.Sounding> load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != 2) throw new IOException("unknown version");
            int n = in.readInt();
            if (n < 0 || n > 20_000_000) throw new IOException("bad count");
            List<NveLakes.Sounding> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new NveLakes.Sounding(in.readFloat(), in.readFloat(), in.readFloat()));
            return out;
        }
    }
}
