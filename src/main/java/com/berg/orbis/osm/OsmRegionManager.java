package com.berg.orbis.osm;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.RegionRaster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams OSM data in as players explore. The world is divided into square
 * regions (default 512 x 512 blocks); each is fetched from Overpass (or the
 * disk cache), rasterised once, and kept in a bounded LRU cache.
 *
 * Downloads are scheduled by priority, not arrival order: a region that a
 * chunk is actually waiting for (priority 0) is always taken before
 * speculative prefetches (priority = distance from the player), so walking
 * into a new region never queues behind the ring around the last one.
 * Several downloads run in parallel (per-endpoint limits live in
 * {@link OsmDataProvider}).
 *
 * Chunk generation never blocks a worker thread on this class: the
 * generator chains onto {@link #future} instead (see RealWorldChunkGenerator).
 */
public final class OsmRegionManager {

    public static final int PRIORITY_DEMAND = 0;
    public static final int PRIORITY_SPAWN = 5;

    /** Threads for the road-database fetches that run beside the map-data fetches. */
    private static final java.util.concurrent.ExecutorService NVDB_POOL = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Orbis-NVDB");
        t.setDaemon(true);
        return t;
    });

    private static final class Request {
        final int rx, rz;
        final CompletableFuture<RegionRaster> future = new CompletableFuture<>();
        volatile int priority;
        /** Not before this time (a region being built again after terrain downloads failed). */
        volatile long retryAt;
        int retries;
        volatile boolean taken;

        Request(int rx, int rz, int priority) {
            this.rx = rx;
            this.rz = rz;
            this.priority = priority;
        }
    }

    private final OrbisConfig cfg;
    private final CoordinateMapper mapper;
    private final OsmDataProvider provider;
    private final FeatureRasterizer rasterizer;
    private final int size;

    private final Map<Long, RegionRaster> cache;
    private final ConcurrentHashMap<Long, Request> pending = new ConcurrentHashMap<>();
    private final Object queueLock = new Object();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger loaded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong bytesInCache = new AtomicLong();
    private volatile boolean shutdown;
    /** Last known player region, used as a tie-break so nearer prefetches go first. */
    private volatile int focusRx, focusRz;
    /** Last known player block positions (x, z). */
    private volatile int[][] players = new int[0][];

    public OsmRegionManager(OrbisConfig cfg, CoordinateMapper mapper, OsmDataProvider provider, FeatureRasterizer rasterizer) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.provider = provider;
        this.rasterizer = rasterizer;
        this.size = cfg.regionSizeBlocks;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, RegionRaster> eldest) {
                if (size() > cfg.regionCacheSize) {
                    bytesInCache.addAndGet(-eldest.getValue().approxBytes());
                    return true;
                }
                return false;
            }
        });
        int threads = Math.max(2, cfg.overpassConcurrentRequests + 1);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(this::workerLoop, "Orbis-OSM-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    /** The raw OSM source (extracts, cache, Overpass), for one-off queries such as the landmark list. */
    public OsmDataProvider provider() {
        return provider;
    }

    public int regionCoord(int block) {
        return Math.floorDiv(block, size);
    }

    public int regionSize() {
        return size;
    }

    // ------------------------------------------------------------------ public API

    /** Future for a region; completes with an (possibly empty) raster, never exceptionally. */
    public CompletableFuture<RegionRaster> future(int rx, int rz, int priority) {
        long key = key(rx, rz);
        RegionRaster cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        Request req = pending.compute(key, (k, existing) -> {
            if (existing != null) {
                if (priority < existing.priority) existing.priority = priority;
                return existing;
            }
            return new Request(rx, rz, priority);
        });
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        return req.future;
    }

    public CompletableFuture<RegionRaster> futureForBlock(int blockX, int blockZ) {
        return future(regionCoord(blockX), regionCoord(blockZ), PRIORITY_DEMAND);
    }

    /** Raster if already decoded, else null (never blocks). */
    public RegionRaster getIfLoaded(int rx, int rz) {
        return cache.get(key(rx, rz));
    }

    public RegionRaster getIfLoadedForBlock(int blockX, int blockZ) {
        return getIfLoaded(regionCoord(blockX), regionCoord(blockZ));
    }

    /** Blocking fetch; with wait=false returns null when not loaded yet (but starts loading). */
    public RegionRaster get(int rx, int rz, boolean wait) {
        CompletableFuture<RegionRaster> f = future(rx, rz, PRIORITY_DEMAND);
        return wait ? f.join() : f.getNow(null);
    }

    /** Raster covering the given block column; blocks only when waitForOsm is on. Use from non-worldgen threads only. */
    public RegionRaster getForBlock(int blockX, int blockZ) {
        return get(regionCoord(blockX), regionCoord(blockZ), cfg.waitForOsm);
    }

    public boolean isLoaded(int rx, int rz) {
        return cache.containsKey(key(rx, rz));
    }

    /** Queues the ring(s) around a region; nearer rings get higher priority. */
    public void prefetchAround(int rx, int rz, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = rx + dx, nz = rz + dz;
                if (isLoaded(nx, nz)) continue;
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                future(nx, nz, 10 * ring);
            }
        }
    }

    /**
     * Called every couple of seconds with the players' positions: keeps the
     * regions around and ahead of each player queued so they are usually
     * decoded before the first chunk needs them.
     */
    public void updatePlayers(List<int[]> blockPositions) {
        players = blockPositions.toArray(new int[0][]);
        if (blockPositions.isEmpty()) return;
        int[] first = blockPositions.get(0);
        focusRx = regionCoord(first[0]);
        focusRz = regionCoord(first[1]);
        for (int[] p : blockPositions) {
            int rx = regionCoord(p[0]), rz = regionCoord(p[1]);
            future(rx, rz, PRIORITY_DEMAND);
            prefetchAround(rx, rz, cfg.regionPrefetchRadius);
        }
    }

    /**
     * Downloads the regions vanilla's spawn search can touch (it spirals
     * +-256 blocks around the origin) and the ring around them. Meant to
     * run on a background thread while the player is still in the main menu.
     */
    public void prefetchSpawnArea() {
        List<CompletableFuture<RegionRaster>> all = queueSpawnArea();
        try {
            CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
            System.out.println("[orbis] Spawn area OSM data ready: " + stats());
        } catch (RuntimeException e) {
            System.err.println("[orbis] Spawn prefetch failed: " + e);
        }
    }

    /** The four regions that meet at the origin: vanilla's spawn search and the spawn chunks never leave them. */
    private static final int[][] CORE_SPAWN_REGIONS = {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}};
    /** Ring around them, prefetched too so the first steps out of spawn are instant. */
    private static final int SPAWN_RING_MIN = -2, SPAWN_RING_MAX = 1;

    /** Queues the spawn regions (core first) without waiting; safe to call repeatedly. */
    public List<CompletableFuture<RegionRaster>> queueSpawnArea() {
        List<CompletableFuture<RegionRaster>> all = new ArrayList<>();
        for (int[] rc : CORE_SPAWN_REGIONS) all.add(future(rc[0], rc[1], PRIORITY_SPAWN));
        for (int rz = SPAWN_RING_MIN; rz <= SPAWN_RING_MAX; rz++) {
            for (int rx = SPAWN_RING_MIN; rx <= SPAWN_RING_MAX; rx++) all.add(future(rx, rz, PRIORITY_SPAWN + 1));
        }
        return all;
    }

    /** True once the four regions around the origin are decoded: world creation can proceed without freezing. */
    public boolean spawnCoreReady() {
        for (int[] rc : CORE_SPAWN_REGIONS) {
            if (!isLoaded(rc[0], rc[1])) return false;
        }
        return true;
    }

    /** How many of the 16 spawn-area regions are decoded. */
    public int spawnRegionsReady() {
        int n = 0;
        for (int rz = SPAWN_RING_MIN; rz <= SPAWN_RING_MAX; rz++) {
            for (int rx = SPAWN_RING_MIN; rx <= SPAWN_RING_MAX; rx++) if (isLoaded(rx, rz)) n++;
        }
        return n;
    }

    public int spawnRegionsTotal() {
        return (SPAWN_RING_MAX - SPAWN_RING_MIN + 1) * (SPAWN_RING_MAX - SPAWN_RING_MIN + 1);
    }

    public int fetchFailures() {
        return failed.get();
    }

    public String stats() {
        return "regions cached=" + cache.size() + " (~" + (bytesInCache.get() / (1024 * 1024)) + " MB), queued=" + pending.size()
                + ", loaded total=" + loaded.get() + ", fetch failures=" + failed.get();
    }

    /**
     * Chebyshev distance in blocks from the nearest known player, or 0 when
     * no player position is known yet (world creation, spawn search): then
     * everything counts as "near" and gets full detail.
     */
    public int nearestPlayerDistance(int blockX, int blockZ) {
        int[][] ps = players;
        if (ps.length == 0) return 0;
        int best = Integer.MAX_VALUE;
        for (int[] p : ps) {
            int d = Math.max(Math.abs(p[0] - blockX), Math.abs(p[1] - blockZ));
            if (d < best) best = d;
        }
        return best;
    }

    public void shutdown() {
        shutdown = true;
        provider.close(); // abort in-flight retries so a retired model stops competing for Overpass
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        // Nothing will ever complete these; give waiters an empty raster instead of a hang.
        for (Request r : pending.values()) {
            r.future.complete(new RegionRaster(r.rx, r.rz, size, cfg.regionMarginBlocks));
        }
        pending.clear();
    }

    // ------------------------------------------------------------------ workers

    private volatile java.util.function.LongSupplier terrainFailures;

    /**
     * The terrain provider's failure count. A region built while terrain tiles could not be downloaded has
     * sea-level heights for the roads, bridges, tunnels and buildings on those tiles, and the region is cached for
     * the session: so such a region is thrown away and built again 30 s later (as long as a pre-generation runs,
     * and up to four times otherwise, so exploring during an outage cannot hang on it).
     */
    /** Regions waiting to be built again because terrain downloads failed while they were prepared. */
    public int terrainRetries() {
        int n = 0;
        for (Request r : pending.values()) if (r.retries > 0) n++;
        return n;
    }

    public void setTerrainFailures(java.util.function.LongSupplier failures) {
        this.terrainFailures = failures;
    }

    private void workerLoop() {
        while (!shutdown) {
            Request req = takeNext();
            if (req == null) continue;
            RegionRaster raster;
            java.util.function.LongSupplier failures = terrainFailures;
            long failuresBefore = failures == null ? 0 : failures.getAsLong();
            try {
                raster = fetchAndRasterize(req.rx, req.rz);
            } catch (Throwable t) {
                System.err.println("[orbis] Region " + req.rx + "," + req.rz + " failed unexpectedly: " + t);
                t.printStackTrace();
                raster = new RegionRaster(req.rx, req.rz, size, cfg.regionMarginBlocks);
            }
            if (failures != null && failures.getAsLong() != failuresBefore && !shutdown && !com.berg.orbis.OrbisMod.stopping()
                    && (com.berg.orbis.worldgen.PregenTask.isRunning() || req.retries < 4)) {
                req.retries++;
                req.retryAt = System.currentTimeMillis() + 30_000;
                req.taken = false;
                System.err.println("[orbis] Region " + req.rx + "," + req.rz + " was prepared while terrain tiles could not be downloaded; preparing it again in 30 s (attempt " + (req.retries + 1) + ")");
                continue;
            }
            long key = key(req.rx, req.rz);
            cache.put(key, raster);
            bytesInCache.addAndGet(raster.approxBytes());
            pending.remove(key);
            req.future.complete(raster);
        }
    }

    private Request takeNext() {
        synchronized (queueLock) {
            while (!shutdown) {
                Request best = null;
                long bestScore = Long.MAX_VALUE;
                for (Request r : pending.values()) {
                    if (r.taken || r.retryAt > System.currentTimeMillis()) continue;
                    long dist = Math.max(Math.abs(r.rx - focusRx), Math.abs(r.rz - focusRz));
                    long score = (long) r.priority * 1000 + dist;
                    if (score < bestScore) {
                        bestScore = score;
                        best = r;
                    }
                }
                if (best != null) {
                    best.taken = true;
                    return best;
                }
                try {
                    queueLock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    private RegionRaster fetchAndRasterize(int rx, int rz) {
        int minX = rx * size - cfg.regionMarginBlocks;
        int maxX = (rx + 1) * size + cfg.regionMarginBlocks;
        int minZ = rz * size - cfg.regionMarginBlocks;
        int maxZ = (rz + 1) * size + cfg.regionMarginBlocks;
        double[] sw = mapper.toLatLonExact(minX, maxZ);
        double[] ne = mapper.toLatLonExact(maxX, minZ);
        double south = Math.min(sw[0], ne[0]), north = Math.max(sw[0], ne[0]);
        double west = Math.min(sw[1], ne[1]), east = Math.max(sw[1], ne[1]);

        // Road widths from the Norwegian road database are fetched alongside the map data, not after it. The
        // raster bbox is the region plus margin, the same box the rasteriser derives.
        CompletableFuture<List<com.berg.orbis.osm.NvdbRoads.Segment>> nvdbFuture = null;
        {
            double[] ra = mapper.toLatLonExact(minX, maxZ);
            double[] rb = mapper.toLatLonExact(maxX, minZ);
            double rs = Math.min(ra[0], rb[0]), rn = Math.max(ra[0], rb[0]), rw = Math.min(ra[1], rb[1]), re = Math.max(ra[1], rb[1]);
            if (rasterizer.wantsNvdb(rs, rw, rn, re)) {
                com.berg.orbis.osm.NvdbRoads.Store store = rasterizer.nvdbStore();
                nvdbFuture = CompletableFuture.supplyAsync(() -> store.get(rs, rw, rn, re), NVDB_POOL);
            }
        }

        OsmData data = null;
        int attempt = 0;
        long start = System.currentTimeMillis();
        long deadline = start + cfg.osmMaxWaitMinutes * 60_000L;
        while (!shutdown) {
            try {
                data = provider.getData(south, west, north, east);
                break;
            } catch (IOException e) {
                attempt++;
                failed.incrementAndGet();
                System.err.println("[orbis] Region " + rx + "," + rz + " OSM fetch failed (" + e.getMessage() + ")");
                if (!cfg.waitForOsm || System.currentTimeMillis() > deadline) {
                    System.err.println("[orbis] Giving up on OSM data for region " + rx + "," + rz
                            + " -- it will generate as terrain only. Delete the world region and restart to retry.");
                    break;
                }
                try {
                    Thread.sleep(Math.min(60_000L, 10_000L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                System.err.println("[orbis] Region " + rx + "," + rz + " OSM parse failed: " + e);
                e.printStackTrace();
                break;
            }
        }
        long fetched = System.currentTimeMillis();
        List<com.berg.orbis.osm.NvdbRoads.Segment> nvdb = null;
        if (nvdbFuture != null) {
            try {
                nvdb = nvdbFuture.get(90, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[orbis] Region " + rx + "," + rz + ": NVDB widths not ready (" + e.getClass().getSimpleName() + "); road classes decide");
                nvdb = List.of();
            }
        }
        RegionRaster raster;
        try {
            raster = rasterizer.rasterize(rx, rz, data, nvdb);
        } catch (RuntimeException e) {
            System.err.println("[orbis] Region " + rx + "," + rz + " rasterisation failed: " + e);
            e.printStackTrace();
            raster = new RegionRaster(rx, rz, size, cfg.regionMarginBlocks);
        }
        loaded.incrementAndGet();
        if (cfg.debugLogging || (fetched - start) > 2000) {
            System.out.println("[orbis] Region " + rx + "," + rz + " ready: fetch " + (fetched - start) + " ms, raster "
                    + (System.currentTimeMillis() - fetched) + " ms, " + raster.buildings.size() + " buildings, "
                    + raster.roads.size() + " roads, " + raster.waters.size() + " water bodies"
                    + (raster.hasCoastline ? ", coastline" : "") + " | " + stats());
        }
        return raster;
    }

    private static long key(int rx, int rz) {
        return (((long) rx) << 32) ^ (rz & 0xffffffffL);
    }
}
