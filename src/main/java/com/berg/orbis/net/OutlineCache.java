package com.berg.orbis.net;

import com.berg.orbis.OrbisMod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Area outlines from Nominatim, kept in memory and on disk ({@code config/orbisterrarum/outline-cache}) for 90
 * days. Boundaries rarely change, a country's outline takes Nominatim half a minute to build (Russia: 31 s), and
 * Nominatim's usage policy asks clients to cache. The area preview and {@code /orbis pregen area} share it, so a
 * sweep started after a preview uses exactly the outline that was previewed. Delete the folder to fetch afresh.
 */
public final class OutlineCache {
    private static final int MAGIC = 0x4F524230; // "ORB0"
    private static final long MAX_AGE_MS = 90L * 24 * 3600 * 1000;
    private static final Map<String, AreaOutline> MEMORY = new ConcurrentHashMap<>();

    private OutlineCache() {}

    /** Cache key of a place-name search: case and spacing do not matter. */
    public static String searchKey(String query) {
        return "search:" + query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Cache key of a reverse lookup (to about a metre). */
    public static String reverseKey(double lat, double lon, int zoom) {
        return String.format(Locale.ROOT, "reverse:z%d:%.5f,%.5f", zoom, lat, lon);
    }

    private static Path dir() {
        String override = System.getProperty("orbis.outlineCache");
        if (override != null) return Path.of(override);
        Path config = OrbisMod.configDir();
        return config == null ? null : config.resolve("outline-cache");
    }

    private static Path file(String key) {
        Path dir = dir();
        if (dir == null) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(h, 0, 10) + ".bin.gz");
        } catch (Exception e) {
            return null;
        }
    }

    /** The cached outline for {@code key}, or null. */
    public static AreaOutline get(String key) {
        AreaOutline m = MEMORY.get(key);
        if (m != null) return m;
        Path f = file(key);
        if (f == null || !Files.isRegularFile(f)) return null;
        try {
            if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > MAX_AGE_MS) return null;
            try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
                if (in.readInt() != MAGIC) return null;
                if (!in.readUTF().equals(key)) return null;
                String name = in.readUTF();
                double s = in.readDouble(), w = in.readDouble(), n = in.readDouble(), e = in.readDouble();
                int polys = in.readInt();
                List<double[][]> list = new ArrayList<>(polys);
                for (int p = 0; p < polys; p++) {
                    double[][] ring = new double[in.readInt()][];
                    for (int i = 0; i < ring.length; i++) ring[i] = new double[]{in.readDouble(), in.readDouble()};
                    list.add(ring);
                }
                AreaOutline o = new AreaOutline(name, list, s, w, n, e);
                MEMORY.put(key, o);
                return o;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Ignoring an unreadable cached outline " + f.getFileName() + ": " + e);
            return null;
        }
    }

    /** Remembers {@code o} under {@code key}; a failure to write only costs the next lookup its speed. */
    public static void put(String key, AreaOutline o) {
        MEMORY.put(key, o);
        Path f = file(key);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
                out.writeInt(MAGIC);
                out.writeUTF(key);
                out.writeUTF(o.name());
                out.writeDouble(o.south());
                out.writeDouble(o.west());
                out.writeDouble(o.north());
                out.writeDouble(o.east());
                out.writeInt(o.polygons().size());
                for (double[][] ring : o.polygons()) {
                    out.writeInt(ring.length);
                    for (double[] p : ring) {
                        out.writeDouble(p[0]);
                        out.writeDouble(p[1]);
                    }
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not cache the outline of " + o.name() + ": " + e);
        }
    }
}
