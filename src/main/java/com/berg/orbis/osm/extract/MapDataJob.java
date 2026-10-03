package com.berg.orbis.osm.extract;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A world's map data from Geofabrik, so generation reads the map from disk instead of the public Overpass servers
 * (two to six minutes a region in Nepal, with timeouts): the smallest region file that holds the world's area is
 * downloaded, only that area is imported at full detail ({@link ExtractImporter}, memory by area), and the big file
 * is deleted afterwards unless the player keeps such files. One job at a time, on its own thread; the world goes on
 * generating from the online servers until the import is done, then the store is picked up by itself.
 */
public final class MapDataJob {

    /** What would be downloaded for an area: the region, its file size, the area, and the name of the store. */
    public record Plan(Geofabrik.Region region, long bytes, double[] box, String storeName) {
        public String sizeText() {
            return bytes < 0 ? "unknown size" : bytes >= 1L << 30
                    ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30))
                    : String.format(Locale.ROOT, "%,d MB", bytes >> 20);
        }
    }

    public interface Listener {
        /** A progress line, and how far along (0..1), or -1 when it cannot say. */
        void progress(String line, float fraction);

        void finished(String message, boolean ok);
    }

    /** No region file this big is fetched (a continent); the online servers do better there. */
    private static final long MAX_BYTES = 8L << 30;
    /** A half-downloaded file older than this is started again (Geofabrik has published a newer one since). */
    private static final long PART_MAX_AGE_MS = 12L * 3600 * 1000;

    private static volatile Thread running;
    private static volatile boolean stop;

    private MapDataJob() {
    }

    public static boolean busy() {
        Thread t = running;
        return t != null && t.isAlive();
    }

    public static void stop() {
        stop = true;
    }

    /**
     * Whether this area at this scale would gain from a download, and what it would be: null when a store on disk
     * covers it already, when the scale is coarse (8 m per block or more: the whole-country "map" import is for
     * those, run by hand), or when no single region holds the area (across a border). Asks the network.
     */
    public static Plan plan(Path dataDir, double[] box, double metersPerBlock) throws IOException, InterruptedException {
        if (metersPerBlock >= 8.0) return null;
        LocalExtractStore store = LocalExtractStore.get(dataDir.resolve("extracts"));
        store.rescan();
        for (LocalExtractStore.Extract e : store.extracts()) {
            if (e.usableAt(metersPerBlock) && e.covers(box[0], box[1], box[2], box[3])) return null;
        }
        List<Geofabrik.Region> all = Geofabrik.regions(dataDir.resolve("extracts-src"));
        Geofabrik.Region region = Geofabrik.smallestContaining(all, box);
        if (region == null) return null;
        long bytes = OrbisHttp.size(region.url(), Geofabrik.HEADERS);
        if (bytes > MAX_BYTES) return null;
        String name = String.format(Locale.ROOT, "%s-area-%d_%d", region.id(), Math.round(box[0] * 100), Math.round(box[1] * 100));
        return new Plan(region, bytes, box.clone(), name);
    }

    /** The area box {south, west, north, east} around a spawn: about 4,000 blocks each way, at least 8 km, at most 40 km. */
    public static double[] boxAround(double lat, double lon, double metersPerBlock) {
        double km = Math.max(8, Math.min(40, 4000 * metersPerBlock / 1000));
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lon - dLon, lat + dLat, lon + dLon};
    }

    /** Starts the download and import; false when one is running already. */
    public static synchronized boolean start(Path dataDir, Plan plan, boolean keepFile, Listener listener) {
        if (busy()) return false;
        stop = false;
        Thread t = new Thread(() -> run(dataDir, plan, keepFile, listener), "Orbis-map-data");
        t.setDaemon(true);
        running = t;
        t.start();
        return true;
    }

    private static void run(Path dataDir, Plan plan, boolean keepFile, Listener listener) {
        String place = plan.region().name();
        Path src = dataDir.resolve("extracts-src");
        Path pbf = src.resolve(plan.region().id() + "-latest.osm.pbf");
        try {
            boolean have = Files.isRegularFile(pbf) && System.currentTimeMillis() - Files.getLastModifiedTime(pbf).toMillis() < 30L * 24 * 3600 * 1000;
            if (!have) {
                Path part = pbf.resolveSibling(pbf.getFileName() + ".part");
                if (Files.exists(part) && System.currentTimeMillis() - Files.getLastModifiedTime(part).toMillis() > PART_MAX_AGE_MS) Files.delete(part);
                long started = System.currentTimeMillis();
                long[] from = {-1};
                OrbisHttp.download(plan.region().url(), pbf, Geofabrik.HEADERS, (done, total) -> {
                    if (from[0] < 0) from[0] = done; // a continued download starts where the part ended
                    float f = total > 0 ? (float) done / total : -1f;
                    // Time left at the speed so far (Geofabrik can be slow from far away: 165 KB/s from India).
                    double secs = (System.currentTimeMillis() - started) / 1000.0;
                    double rate = secs > 3 ? (done - from[0]) / secs : 0;
                    String left = total > 0 && rate > 0 ? ", " + duration((total - done) / rate) + " left" : "";
                    listener.progress(total > 0
                            ? String.format(Locale.ROOT, "Downloading %s: %,d of %,d MB%s", place, done >> 20, total >> 20, left)
                            : String.format(Locale.ROOT, "Downloading %s: %,d MB", place, done >> 20), f);
                }, () -> stop);
            }
            listener.progress("Keeping this world's area...", -1f);
            ExtractImporter.Summary s = ExtractImporter.importFile(pbf, dataDir.resolve("extracts"), ExtractImporter.Profile.FULL,
                    plan.storeName(), plan.box(), msg -> listener.progress(msg, -1f));
            LocalExtractStore.get(dataDir.resolve("extracts")).rescan();
            long kept = folderBytes(s.dir());
            if (!keepFile) Files.deleteIfExists(pbf);
            listener.finished(String.format(Locale.ROOT, "Map data for %s is ready (%,d MB on disk%s): the world now reads the map from your disk.",
                    place, kept >> 20, keepFile ? "" : "; the downloaded file was deleted"), true);
        } catch (java.util.concurrent.CancellationException e) {
            listener.finished("The map data download stopped; it continues next time.", false);
        } catch (Throwable e) {
            listener.finished("Map data for " + place + " could not be fetched (" + e.getMessage()
                    + "); the online map servers are used instead.", false);
        } finally {
            running = null;
        }
    }

    private static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 90) return s + " s";
        if (s < 5400) return Math.round(s / 60.0) + " min";
        return String.format(Locale.ROOT, "%.1f h", s / 3600.0);
    }

    private static long folderBytes(Path dir) {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
