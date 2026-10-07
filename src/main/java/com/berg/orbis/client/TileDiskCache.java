package com.berg.orbis.client;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The area preview's map tiles on disk, kept the way a browser keeps them: a tile is used as is for as long as
 * Esri's Cache-Control allows (one day), after that it is revalidated with its ETag (a "not modified" answer has
 * no body), and when the network is down an older copy is shown rather than nothing. The folder is trimmed to
 * {@link #MAX_BYTES}, least recently used first. Plain Java apart from the HTTP helper, so it can be tested alone.
 */
public final class TileDiskCache {
    public static final long MAX_BYTES = 300L << 20;
    private static final long DEFAULT_MAX_AGE_S = 86_400;
    private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");
    private static final AtomicBoolean TRIMMING = new AtomicBoolean();

    private final Path dir;
    private final String userAgent;

    /** The cache folder. */
    public Path dir() {
        return dir;
    }

    public TileDiskCache(Path dir, String userAgent) {
        this.dir = dir;
        this.userAgent = userAgent;
    }

    /** Where a tile lives: {service}/{z}/{x}/{y}.img, with its ETag and lifetime in {y}.meta. */
    private Path file(String service, int z, int x, int y) {
        return dir.resolve(service.replace('/', '_')).resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".img");
    }

    /**
     * The tile's bytes: from disk while fresh, else from Esri (revalidating a stale copy). Null when the tile does
     * not exist at that zoom; an exception when it cannot be had at all (offline and never cached).
     */
    public byte[] fetch(String service, int z, int x, int y) throws IOException, InterruptedException {
        String url = "https://server.arcgisonline.com/ArcGIS/rest/services/" + service + "/MapServer/tile/" + z + "/" + y + "/" + x;
        return fetch(url, service, z, x, y, Map.of(), Long.MAX_VALUE);
    }

    /**
     * Like {@link #fetch(String, int, int, int)} for any tile server: {@code url} is the tile's address, kept under
     * {@code service}; extra request headers, and a cap on how long a copy counts as fresh whatever the server says
     * (OpenFreeMap's versioned tiles say ten years, but the address changes weekly with the map).
     */
    public byte[] fetch(String url, String service, int z, int x, int y, Map<String, String> extraHeaders, long maxAgeCapS)
            throws IOException, InterruptedException {
        Path img = file(service, z, x, y), meta = img.resolveSibling(y + ".meta");
        byte[] cached = null;
        String etag = null;
        long maxAge = DEFAULT_MAX_AGE_S;
        if (Files.isRegularFile(img)) {
            try {
                cached = Files.readAllBytes(img);
                if (Files.isRegularFile(meta)) {
                    String[] m = Files.readString(meta, StandardCharsets.UTF_8).split("\n");
                    if (m.length > 0 && !m[0].isEmpty()) etag = m[0];
                    if (m.length > 1) maxAge = Long.parseLong(m[1].trim());
                }
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(img).toMillis();
                if (age < Math.min(maxAge, maxAgeCapS) * 1000) return cached;
            } catch (IOException | RuntimeException e) {
                cached = null;
            }
        }
        Map<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("User-Agent", userAgent);
        if (cached != null && etag != null) headers.put("If-None-Match", etag);
        OrbisHttp.Response r;
        try {
            r = OrbisHttp.get(url, headers, 20);
        } catch (IOException e) {
            if (cached != null) return cached; // offline: an older map beats a grey square
            throw e;
        }
        if (r.status() == 304 && cached != null) {
            touch(img);
            return cached;
        }
        if (r.status() == 404) return null;
        if (r.status() != 200) {
            if (cached != null) return cached;
            throw new IOException("HTTP " + r.status());
        }
        String cc = r.header("Cache-Control");
        Matcher m = cc == null ? null : MAX_AGE.matcher(cc);
        long newMaxAge = m != null && m.find() ? Long.parseLong(m.group(1)) : DEFAULT_MAX_AGE_S;
        if (cc == null || !cc.contains("no-store")) store(img, meta, r.body(), r.header("ETag"), newMaxAge);
        return r.body();
    }

    private static void touch(Path img) {
        try {
            Files.setLastModifiedTime(img, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private static void store(Path img, Path meta, byte[] body, String etag, long maxAge) {
        try {
            Files.createDirectories(img.getParent());
            Path tmp = img.resolveSibling(img.getFileName() + ".tmp" + Thread.currentThread().threadId());
            Files.write(tmp, body);
            Files.move(tmp, img, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(meta, (etag == null ? "" : etag) + "\n" + maxAge, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // Only costs the next preview a download.
        }
    }

    /** Deletes the least recently used tiles until the folder is under MAX_BYTES (background thread, one at a time). */
    public void trimLater() {
        if (!TRIMMING.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                trim(MAX_BYTES);
            } finally {
                TRIMMING.set(false);
            }
        }, "Orbis map tile cache trim");
        t.setDaemon(true);
        t.start();
    }

    /** Trims to {@code limit} bytes now; returns the bytes left. */
    public long trim(long limit) {
        if (!Files.isDirectory(dir)) return 0;
        record Entry(Path img, long size, long used) {}
        List<Entry> all = new ArrayList<>();
        long total = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".img")) continue;
                try {
                    long size = Files.size(p);
                    all.add(new Entry(p, size, Files.getLastModifiedTime(p).toMillis()));
                    total += size;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException | RuntimeException e) {
            return total;
        }
        if (total <= limit) return total;
        all.sort((a, b) -> Long.compare(a.used(), b.used()));
        long target = limit * 5 / 6;
        for (Entry e : all) {
            if (total <= target) break;
            try {
                Files.deleteIfExists(e.img());
                String name = e.img().getFileName().toString();
                Files.deleteIfExists(e.img().resolveSibling(name.substring(0, name.length() - 4) + ".meta"));
                total -= e.size();
            } catch (IOException ignored) {
            }
        }
        return total;
    }
}
