package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.mixin.ChunkMapAccessor;
import com.berg.orbis.mixin.IOWorkerAccessor;
import com.berg.orbis.mixin.SimpleRegionStorageAccessor;
import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.osm.OsmRegionManager;
import com.berg.orbis.osm.extract.LocalExtractStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-generates chunks so a world can be explored (or uploaded to a server)
 * without waiting for downloads. Two shapes:
 * <ul>
 *   <li>a radius around a point, nearest chunks first;</li>
 *   <li>an area outline (a country, region or city from the geocoder), swept
 *   chunk row by chunk row from north to south like a scanner. Open-sea
 *   chunks are skipped, progress is written to
 *   {@code config/orbisterrarum/pregen/<name>.json} every ten seconds, and
 *   running the same command again after a stop, crash or restart resumes
 *   at the last completed row.</li>
 * </ul>
 * Chunk requests are issued from the task's own thread: asked from the
 * server thread, vanilla's chunk request blocks that thread until the chunk
 * is done, which would serialise everything; asked from another thread it is
 * queued and every worker thread generates in parallel. A semaphore keeps a
 * few dozen chunks in flight; they are saved when they unload. Progress is
 * reported in chat every ten seconds. One task at a time; {@code /orbis
 * pregen stop} ends it.
 */
public final class PregenTask {

    /**
     * Chunk requests outstanding at once. Generating a chunk is a chain of small steps handed between the
     * server thread and the worker pool, so one request rarely keeps a core busy; a sample of a sweep with 32
     * showed 3 of 11 workers in use. A few hundred keeps the pool fed; memory and the disk queue throttle it.
     */
    private static final int MAX_IN_FLIGHT = 128;
    /**
     * The area is walked in square tiles of this many chunks a side, tile-rows in serpentine order, rather than
     * in rows of the full width. A finished chunk drags a ring of neighbours through the early generation
     * stages; those neighbours are the next chunks in a tile, still resident, but in a 900-chunk-wide row they
     * had long been unloaded, written as stubs and read back (more than half of all worker time was that
     * churn).
     */
    private static final int TILE = 16;
    /**
     * Finished chunks whose tickets are kept a while longer (released oldest first), so the neighbourhood of
     * the chunks still to come stays loaded instead of being evicted and re-read from disk.
     */
    private static final int HOLD_TICKETS = 256;
    /**
     * A full-status request raises the load level of its neighbours up to 13 chunks away, so requests
     * spread along a row keep a band of thousands of chunks resident; in a 4064-block-tall dimension that
     * is gigabytes. New requests wait while more than this many chunks are loaded.
     */
    private static final int MAX_LOADED_CHUNKS = 3000;
    /**
     * Chunks that have left memory but are not on disk yet. Generation runs at hundreds of chunks a second;
     * the region-file writer manages a few to a few hundred (one write per chunk, each one synchronous when
     * the game's "sync chunk writes" option is on, which is the default). Everything in between waits in
     * memory: a sweep of a million chunks once filled a 16 GB heap that way while a few hundred finished
     * chunks had reached the disk. New requests wait while more than this many chunks are queued.
     */
    private static final int MAX_PENDING_WRITES = 2000;
    /** Old-generation heap still in use after the last collection, as a fraction of the maximum: above this, wait. */
    private static final double MAX_HEAP_FRACTION = 0.75;
    /** Chunks loaded before the task started (the players' view areas); the limit above is added to it. */
    private final int baselineLoaded;
    /** The loaded-chunk limit for this world: MAX_LOADED_CHUNKS was sized for 254-section chunks; a fitted world's chunks are smaller. */
    private final int maxLoaded;
    /** The world model the sweep runs against. */
    private WorldModel model;
    /** The region cache size before the sweep widened it, or -1. */
    private int cacheSizeBefore = -1;
    /** Chunks whose five elevation samples are all below this many metres are open sea and skipped. */
    private static final double DEEP_SEA_M = -40;
    private static volatile PregenTask active;

    private record Pending(int row, ChunkPos pos, CompletableFuture<?> future) {
    }

    /**
     * Keeps a requested chunk loaded until it is done. A plain chunk request from another thread only holds a
     * one-tick ticket; as soon as the server ticks, the ticket expires, the generation is cancelled, the
     * request fails and the half-generated chunk is thrown away with only a stub written to disk. (A sweep of
     * Bergen once "generated" 1.9 million chunks that way and left 47 000 stubs.) This ticket does not expire
     * and is not persisted; step() removes it once the chunk is done, and the chunk then unloads and is saved.
     */
    private static final TicketType PREGEN_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    private final ServerLevel level;
    private final String label;
    // radius mode
    private final long[] chunks;
    // area mode
    private final AreaSweep sweep;
    private final Path progressFile;
    private final int startRow;
    private boolean mainlandOnly = true;
    private long estimatedChunks;
    private final ConcurrentHashMap<Integer, AtomicInteger> outstanding = new ConcurrentHashMap<>();
    private final AtomicLong skippedSea = new AtomicLong();
    private volatile int producerRow;
    private int completedRow;
    // both modes
    private final Semaphore slots = new Semaphore(MAX_IN_FLIGHT);
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    /** Finished chunks still holding their ticket, oldest first (server thread only). */
    private final java.util.ArrayDeque<ChunkPos> held = new java.util.ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean producerDone, stopRequested;
    /** Why the producer is paused right now, or null. */
    private volatile String waiting;
    /** Rows whose chunks have all left memory (their writes are queued), and of those the rows the disk has. */
    private volatile int unloadedRow, flushedRow;
    private CompletableFuture<Void> barrier, finalBarrier;
    private int barrierRow;
    private long lastBarrier = System.currentTimeMillis();
    private volatile long producerFinishedAt;
    private final MemoryPoolMXBean oldGen = findOldGen();
    private Thread producer;
    private int done, failed;
    private final long startedAt = System.currentTimeMillis();
    private long lastReport = System.currentTimeMillis();
    /** For the rate over the last report interval, which is what a player wants to see (the run average hides a stall). */
    private long rateWindowAt = System.currentTimeMillis();
    private int rateWindowDone;
    private double rateNow;
    private long lastSave = System.currentTimeMillis();

    private PregenTask(ServerLevel level, String label, long[] chunks) {
        this.level = level;
        this.label = label;
        this.chunks = chunks;
        this.sweep = null;
        this.progressFile = null;
        this.startRow = 0;
        this.baselineLoaded = level.getChunkSource().getLoadedChunksCount();
        this.maxLoaded = loadedLimitFor(level);
    }

    private PregenTask(ServerLevel level, String label, AreaSweep sweep, Path progressFile, int startRow) {
        this.level = level;
        this.label = label;
        this.chunks = null;
        this.sweep = sweep;
        this.progressFile = progressFile;
        this.startRow = startRow;
        this.completedRow = startRow - 1;
        this.unloadedRow = startRow - 1;
        this.flushedRow = startRow - 1;
        this.producerRow = startRow;
        this.baselineLoaded = level.getChunkSource().getLoadedChunksCount();
        this.maxLoaded = loadedLimitFor(level);
    }

    /** A hint for the start message when the players' own view areas already hold many chunks. */
    private String loadNote() {
        if (baselineLoaded < 3000) return "";
        return String.format(Locale.ROOT, "\n  %,d chunks are loaded around the players already (render distance); the sweep adds up to %,d more. "
                + "A render distance of 8-12 during the sweep leaves more memory for it.", baselineLoaded, maxLoaded);
    }

    // ---- radius ----------------------------------------------------------------------

    /** The most blocks a radius sweep may reach (about 2.8 million chunks). */
    private static final int MAX_RADIUS_BLOCKS = 15_000;

    /**
     * A radius sweep of {@code km} real kilometres: at 1:2 that is half as many blocks. (It used to take the
     * number as thousands of blocks, which covered twice the distance asked for at 1:2.)
     */
    public static Component startKm(ServerLevel level, int centerX, int centerZ, double km) {
        WorldModel model = OrbisMod.model();
        double mpb = model == null ? 1.0 : model.cfg().metersPerBlock;
        int radiusBlocks = (int) Math.round(km * 1000 / mpb);
        if (radiusBlocks > MAX_RADIUS_BLOCKS) {
            return Component.literal(String.format(Locale.ROOT, "Too large: at 1 block = %s m the limit is %.1f km. Use /orbis pregen area <place> for bigger areas.",
                    mpb == Math.rint(mpb) ? String.valueOf((long) mpb) : String.valueOf(mpb), MAX_RADIUS_BLOCKS * mpb / 1000));
        }
        return start(level, centerX, centerZ, radiusBlocks);
    }

    /** Starts a radius task; returns a message for the player. */
    public static synchronized Component start(ServerLevel level, int centerX, int centerZ, int radiusBlocks) {
        if (active != null) return Component.literal("A pre-generation is already running (" + active.label + "); /orbis pregen stop first.\n" + active.progress());
        int cx = centerX >> 4, cz = centerZ >> 4, r = (radiusBlocks >> 4) + 1;
        List<long[]> list = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                list.add(new long[]{cx + dx, cz + dz, (long) dx * dx + (long) dz * dz});
            }
        }
        list.sort((a, b) -> Long.compare(a[2], b[2]));
        long[] packed = new long[list.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = pack((int) list.get(i)[0], (int) list.get(i)[1]);

        // Queue the map data for the whole area now, nearest regions first, below player demand.
        WorldModel model = OrbisMod.model();
        int queuedRegions = 0;
        if (model != null && model.regions() != null) {
            OsmRegionManager regions = model.regions();
            int rr = regions.regionCoord(radiusBlocks) + 1;
            int rcx = regions.regionCoord(centerX), rcz = regions.regionCoord(centerZ);
            for (int dz = -rr; dz <= rr; dz++) {
                for (int dx = -rr; dx <= rr; dx++) {
                    if (dx * dx + dz * dz > (rr + 1) * (rr + 1)) continue;
                    regions.future(rcx + dx, rcz + dz, 20 + Math.max(Math.abs(dx), Math.abs(dz)));
                    queuedRegions++;
                }
            }
        }
        double mpbLabel = model == null ? 1.0 : model.cfg().metersPerBlock;
        String label = String.format(Locale.ROOT, "%.1f km around %d, %d", radiusBlocks * mpbLabel / 1000.0, centerX, centerZ);
        PregenTask t = new PregenTask(level, label, packed);
        t.model = model;
        t.widenRegionCache(2 * radiusBlocks);
        t.startRadiusProducer();
        active = t;
        double estMb = packed.length * 12 / 1024.0;
        return Component.literal(String.format(Locale.ROOT,
                "Pre-generating %s: %,d chunks (%d map regions queued). Rough world size afterwards: %.0f MB. Progress every 30 s; /orbis pregen stop to end.%s",
                label, packed.length, queuedRegions, estMb, syncNote(level)));
    }

    private void startRadiusProducer() {
        producer = new Thread(() -> {
            try {
                for (long p : chunks) {
                    if (stopRequested) break;
                    submit((int) (p >> 32), (int) p);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                producerFinishedAt = System.currentTimeMillis();
                producerDone = true;
            }
        }, "Orbis-Pregen");
        producer.setDaemon(true);
        producer.start();
    }

    // ---- area -------------------------------------------------------------------------

    /** Starts (or resumes) an area sweep; returns a message for the player. */
    public static synchronized Component startArea(ServerLevel level, AreaOutline outline, boolean mainlandOnly) {
        if (active != null) return Component.literal("A pre-generation is already running (" + active.label + "); /orbis pregen stop first.\n" + active.progress());
        WorldModel model = OrbisMod.model();
        if (model == null) return Component.literal("The world model is not ready yet.");
        AreaSweep sweep = AreaSweep.of(outline, model.mapper());
        lastSweep = sweep;
        if (sweep.rowCount() == 0) return Component.literal("The outline of " + outline.name() + " is empty.");
        String slug = slug(outline.name());
        // Progress belongs to the world: a new world must not resume where another one stopped (it once skipped
        // its first 126 rows that way). The file used to live in the config folder; the first world to run a
        // sweep with this version adopts it.
        String fileName = slug + (mainlandOnly ? "" : "-all") + ".json";
        Path file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen").resolve(fileName);
        Path legacy = OrbisMod.configDir().resolve("pregen").resolve(fileName);
        if (!Files.exists(file) && Files.exists(legacy)) {
            try {
                Files.createDirectories(file.getParent());
                Files.move(legacy, file);
            } catch (IOException e) {
                System.err.println("[orbis] Could not move the pregen progress file into the world: " + e);
            }
        }
        int startRow = sweep.firstRow();
        String resumed = "";
        JsonObject saved = readProgress(file);
        if (saved != null && matches(saved, model, mainlandOnly)) {
            int completed = saved.get("completedRow").getAsInt();
            if (completed >= sweep.firstRow() && completed < sweep.lastRow()) {
                startRow = completed + 1;
                resumed = String.format(Locale.ROOT, " Resuming at row %,d of %,d (%,d chunks were done before).",
                        startRow - sweep.firstRow() + 1, sweep.rowCount(), saved.has("done") ? saved.get("done").getAsLong() : 0);
            }
        }
        String label = outline.name().split(",")[0] + (mainlandOnly ? " (largest part)" : " (all parts)");
        PregenTask t = new PregenTask(level, label, sweep, file, startRow);
        t.mainlandOnly = mainlandOnly;
        t.model = model;
        // One pass over the rows for the estimate and the width (a country at 1:1 has a quarter of a million).
        long estimate = 0;
        int spanMin = Integer.MAX_VALUE, spanMax = Integer.MIN_VALUE;
        for (int row = sweep.firstRow(); row <= sweep.lastRow(); row++) {
            for (int[] range : sweep.rowRanges(row)) {
                estimate += range[1] - range[0] + 1;
                spanMin = Math.min(spanMin, range[0]);
                spanMax = Math.max(spanMax, range[1]);
            }
        }
        t.estimatedChunks = estimate;
        if (spanMin <= spanMax) t.widenRegionCache((spanMax - spanMin + 1) * 16);
        t.startAreaProducer(model);
        active = t;

        String extractNote;
        LocalExtractStore store = LocalExtractStore.get(OrbisMod.configDir().resolve("extracts"));
        double midLat = (outline.south() + outline.north()) / 2, midLon = (outline.west() + outline.east()) / 2;
        LocalExtractStore.Extract ex = store.covering(midLat, midLon, midLat, midLon, model.cfg().metersPerBlock);
        if (model.regions() == null) {
            extractNote = " Map data is off for this world (terrain only).";
        } else if (ex != null) {
            boolean whole = ex.covers(outline.south(), outline.west(), outline.north(), outline.east());
            extractNote = " Map data comes from the local extract '" + ex.name() + "'"
                    + (whole ? "." : String.format(Locale.ROOT, " (it covers lat %.1f..%.1f, lon %.1f..%.1f; the few regions outside that come from Overpass).",
                    ex.south(), ex.north(), ex.west(), ex.east()));
        } else {
            extractNote = " WARNING: no local extract covers this area, so every map region will be fetched from Overpass one by one -- slow, and the public servers throttle. Import the country first: see README, 'Country map worlds'.";
        }
        double km2 = outline.totalKm2();
        return Component.literal(String.format(Locale.ROOT,
                "Pre-generating %s\n  about %,d km², %,d chunk rows north to south, up to %,d chunks before open sea is skipped.%s\n %s%s\n  Progress every 30 s; /orbis pregen stop to end (it resumes later).",
                label, (long) km2, sweep.rowCount(), t.estimatedChunks, resumed.isEmpty() ? "" : "\n " + resumed, extractNote, t.loadNote() + syncNote(level)));
    }

    /** The game's "sync chunk writes" option makes every chunk write a separate synchronous disk write. */
    private static String syncNote(ServerLevel level) {
        String pause = level.getServer().isSingleplayer()
                ? "\n  The game will not pause while the sweep runs (Escape menu, switching windows): a paused game stops unloading and saving chunks."
                : "";
        if (!level.getServer().forceSynchronousWrites()) return pause;
        return pause + "\n  Sync chunk writes is ON (options.txt: syncChunkWrites:true), so every chunk is a separate synchronous disk write and the "
                + "sweep is paced by the disk (it waits whenever more than " + String.format(Locale.ROOT, "%,d", MAX_PENDING_WRITES)
                + " chunks are queued for it). For a much faster sweep set syncChunkWrites:false and restart the game.";
    }

    private void startAreaProducer(WorldModel model) {
        producer = new Thread(() -> {
            try {
                int lastRow = sweep.lastRow();
                for (int tileRow = Math.floorDiv(startRow, TILE) * TILE; tileRow <= lastRow && !stopRequested; tileRow += TILE) {
                    int r0 = Math.max(tileRow, startRow), r1 = Math.min(tileRow + TILE - 1, lastRow);
                    producerRow = r0; // every row below this one is fully queued
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                    for (int row = r0; row <= r1; row++) {
                        for (int[] range : sweep.rowRanges(row)) {
                            minX = Math.min(minX, range[0]);
                            maxX = Math.max(maxX, range[1]);
                        }
                    }
                    if (minX > maxX) continue;
                    // Queue the map regions of this tile-row and the next one now (below player demand), so the
                    // workers find them decoded instead of waiting a minute per region at every region edge.
                    if (model.regions() != null) {
                        OsmRegionManager regions = model.regions();
                        int rz0 = regions.regionCoord(r0 << 4), rz1 = regions.regionCoord(Math.min(lastRow, r1 + TILE) << 4);
                        int rx0 = regions.regionCoord(minX << 4), rx1 = regions.regionCoord((maxX << 4) + 15);
                        for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) regions.future(rx, rz, 30);
                    }
                    int t0 = Math.floorDiv(minX, TILE), t1 = Math.floorDiv(maxX, TILE);
                    boolean reverse = (Math.floorDiv(tileRow, TILE) & 1) != 0; // serpentine: the next tile-row starts where this one ends
                    for (int ti = 0; ti <= t1 - t0 && !stopRequested; ti++) {
                        int tx = reverse ? t1 - ti : t0 + ti;
                        int x0 = tx * TILE, x1 = x0 + TILE - 1;
                        for (int row = r0; row <= r1 && !stopRequested; row++) {
                            for (int[] range : sweep.rowRanges(row)) {
                                int a = Math.max(range[0], x0), b = Math.min(range[1], x1);
                                for (int cx = a; cx <= b && !stopRequested; cx++) {
                                    if (isOpenSea(model, cx, row)) {
                                        skippedSea.incrementAndGet();
                                        continue;
                                    }
                                    outstanding.computeIfAbsent(row, k -> new AtomicInteger()).incrementAndGet();
                                    submit(cx, row);
                                }
                            }
                        }
                    }
                }
                if (!stopRequested) producerRow = lastRow + 1;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("[orbis] Area sweep failed: " + e);
                e.printStackTrace();
            } finally {
                producerFinishedAt = System.currentTimeMillis();
                producerDone = true;
            }
        }, "Orbis-Pregen-Sweep");
        producer.setDaemon(true);
        producer.start();
    }

    /** Asks the chunk system for a chunk (from this non-server thread the request is queued, not blocking). */
    private void submit(int cx, int cz) throws InterruptedException {
        slots.acquire();
        String why;
        while (!stopRequested && (why = mustWait()) != null) {
            waiting = why;
            Thread.sleep(100);
        }
        waiting = null;
        if (stopRequested) {
            slots.release();
            return;
        }
        inFlight.incrementAndGet();
        level.getServer().execute(() -> {
            ChunkPos pos = new ChunkPos(cx, cz);
            CompletableFuture<?> future;
            try {
                future = level.getChunkSource().addTicketAndLoadWithRadius(PREGEN_TICKET, pos, 0);
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            pending.add(new Pending(cz, pos, future));
        });
    }

    private static int loadedLimitFor(ServerLevel level) {
        int sections = Math.max(1, level.getHeight() / 16);
        return Math.max(MAX_LOADED_CHUNKS, Math.min(8000, MAX_LOADED_CHUNKS * 254 / sections));
    }

    /**
     * A tile-row of the sweep touches every map region across the area's width, twice or three times over
     * (the rows themselves, the neighbours generated with them, the tunnel lookups); with the default cache of
     * 64 regions and an area 28 regions wide, regions were evicted and rasterised again a few thousand times per
     * sweep, each taking up to a minute, and the rate fell from 48 chunks/s to under 20. So the cache is widened
     * to hold three region rows across the area for the sweep's duration.
     */
    private void widenRegionCache(int spanBlocks) {
        if (model == null || model.regions() == null) return;
        int across = spanBlocks / model.regions().regionSize() + 2;
        int needed = Math.min(320, 3 * across + 16);
        OrbisConfig cfg = model.cfg();
        if (cfg.regionCacheSize < needed) {
            cacheSizeBefore = cfg.regionCacheSize;
            cfg.regionCacheSize = needed;
            System.out.println("[orbis] Pregen: map region cache " + cacheSizeBefore + " -> " + needed + " regions for an area " + across + " regions wide");
        }
    }

    private void restoreRegionCache() {
        if (cacheSizeBefore >= 0 && model != null) {
            model.cfg().regionCacheSize = cacheSizeBefore;
            cacheSizeBefore = -1;
        }
    }

    /** Why the producer must pause right now, or null. */
    private String mustWait() {
        if (level.getChunkSource().getLoadedChunksCount() > baselineLoaded + maxLoaded) return "waiting for unloads";
        int backlog = writeBacklog();
        if (backlog > MAX_PENDING_WRITES) return "waiting for the disk";
        // Memory still held after a collection: only worth waiting for when there is a write backlog to drain.
        if (backlog > 200 && oldGenAfterGc() > MAX_HEAP_FRACTION) return "waiting for memory";
        return null;
    }

    /** Chunks handed to the region-file writer and not written yet, or -1 when that cannot be read. */
    private int writeBacklog() {
        try {
            Object worker = ((SimpleRegionStorageAccessor) (Object) level.getChunkSource().chunkMap).orbisterrarum$worker();
            return ((IOWorkerAccessor) worker).orbisterrarum$pendingWrites().size();
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    private static MemoryPoolMXBean findOldGen() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String n = pool.getName();
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported() && (n.contains("Old") || n.contains("Tenured"))) return pool;
        }
        return null;
    }

    /** Old-generation heap in use right after the last collection, as a fraction of the heap maximum. */
    private double oldGenAfterGc() {
        if (oldGen == null) return 0;
        MemoryUsage u = oldGen.getCollectionUsage();
        if (u == null) return 0;
        double max = u.getMax() > 0 ? u.getMax() : Runtime.getRuntime().maxMemory();
        return max > 0 ? u.getUsed() / max : 0;
    }

    /**
     * Rows whose chunks have all left memory: no longer loaded (except around a player) and no longer waiting to
     * unload, so their writes are queued. Only such rows count as progress on disk once a write barrier passes.
     */
    private void advanceUnloadedRow() {
        if (sweep == null) return;
        ChunkMapAccessor map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        var visible = map.orbisterrarum$visibleChunkMap();
        var unloads = map.orbisterrarum$pendingUnloads();
        // Chunks stay loaded well beyond the view distance: the simulation distance (often larger) plus the
        // ~11 chunks over which the loading levels fall off, and around the world spawn the spawn chunks. Rows
        // there never unload while someone stands nearby; they are saved with the world instead. (With the
        // view distance alone, a player at spawn with simulation distance 12 froze the "on disk" row for hours.)
        var players = level.getServer().getPlayerList();
        int view = Math.max(players.getViewDistance(), players.getSimulationDistance()) + 13;
        int budget = 4; // rows per tick
        while (budget-- > 0 && unloadedRow < completedRow) {
            int row = unloadedRow + 1;
            for (int[] range : sweep.rowRanges(row)) {
                for (int cx = range[0]; cx <= range[1]; cx++) {
                    long key = ChunkPos.pack(cx, row);
                    if (unloads.containsKey(key)) return;
                    if (visible.containsKey(key) && !nearPlayer(cx, row, view) && !nearSpawn(cx, row)) return;
                }
            }
            unloadedRow = row;
        }
    }

    private boolean nearSpawn(int cx, int cz) {
        try {
            net.minecraft.core.BlockPos spawn = level.getRespawnData().pos();
            return Math.abs((spawn.getX() >> 4) - cx) <= 16 && Math.abs((spawn.getZ() >> 4) - cz) <= 16;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean nearPlayer(int cx, int cz, int view) {
        for (ServerPlayer p : level.players()) {
            ChunkPos c = p.chunkPosition();
            if (Math.abs(c.x() - cx) <= view && Math.abs(c.z() - cz) <= view) return true;
        }
        return false;
    }

    private static boolean isOpenSea(WorldModel model, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        int[][] samples = {{x0 + 8, z0 + 8}, {x0 + 1, z0 + 1}, {x0 + 14, z0 + 1}, {x0 + 1, z0 + 14}, {x0 + 14, z0 + 14}};
        for (int[] s : samples) {
            double e = model.elevation(s[0], s[1]);
            if (Double.isNaN(e) || e > DEEP_SEA_M) return false;
        }
        return true;
    }

    private static long pack(int cx, int cz) {
        return ((cx & 0xffffffffL) << 32) | (cz & 0xffffffffL);
    }

    private static String slug(String name) {
        String s = name.split(",")[0].trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return s.isEmpty() ? "area" : s;
    }

    private static JsonObject readProgress(Path file) {
        try {
            if (!Files.exists(file)) return null;
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean matches(JsonObject saved, WorldModel model, boolean mainlandOnly) {
        try {
            return Math.abs(saved.get("originLat").getAsDouble() - model.cfg().originLat) < 1e-6
                    && Math.abs(saved.get("originLon").getAsDouble() - model.cfg().originLon) < 1e-6
                    && Math.abs(saved.get("metersPerBlock").getAsDouble() - model.cfg().metersPerBlock) < 1e-9
                    && saved.get("mainlandOnly").getAsBoolean() == mainlandOnly;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void saveProgress(boolean finished) {
        if (progressFile == null) return;
        try {
            if (finished) {
                Files.deleteIfExists(progressFile);
                return;
            }
            WorldModel model = OrbisMod.model();
            if (model == null) return;
            Files.createDirectories(progressFile.getParent());
            String json = String.format(Locale.ROOT,
                    "{\n  \"label\": \"%s\",\n  \"originLat\": %.7f,\n  \"originLon\": %.7f,\n  \"metersPerBlock\": %s,\n  \"mainlandOnly\": %s,\n"
                            + "  \"firstRow\": %d,\n  \"lastRow\": %d,\n  \"completedRow\": %d,\n  \"done\": %d,\n  \"skippedSea\": %d,\n  \"saved\": \"%s\"\n}\n",
                    label.replace("\"", "'"), model.cfg().originLat, model.cfg().originLon, model.cfg().metersPerBlock,
                    mainlandOnly, sweep.firstRow(), sweep.lastRow(), flushedRow, done, skippedSea.get(), java.time.Instant.now());
            Files.writeString(progressFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save pregen progress: " + e.getMessage());
        }
    }

    // ---- control ---------------------------------------------------------------------

    public static synchronized Component stop() {
        PregenTask t = active;
        if (t == null) return Component.literal("No pre-generation is running.");
        t.stopRequested = true;
        if (t.producer != null) t.producer.interrupt();
        t.saveProgress(false);
        t.restoreRegionCache();
        active = null;
        for (Pending p : t.pending) t.level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, p.pos(), 0);
        t.pending.clear();
        while (!t.held.isEmpty()) t.level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, t.held.pollFirst(), 0);
        return Component.literal("Stopped " + t.label + "\n" + t.progress()
                + "\n  Chunks still queued for the disk are written in the background."
                + (t.sweep != null ? " Run the same command again to resume from the last row on disk." : ""));
    }

    /** True while a sweep is running (the client then refuses to pause the game, see MinecraftPauseMixin). */
    public static boolean isRunning() {
        return active != null;
    }

    public static PregenTask active() {
        return active;
    }

    /** The outline of the last area sweep started in this session, kept after it stopped (for the map). */
    private static volatile AreaSweep lastSweep;

    public static AreaSweep lastSweep() {
        return lastSweep;
    }

    public AreaSweep sweep() {
        return sweep;
    }

    public int completedRow() {
        return completedRow;
    }

    public int flushedRow() {
        return flushedRow;
    }

    private long lastMap = System.currentTimeMillis();

    public static Component status() {
        PregenTask t = active;
        if (t == null) return Component.literal("No pre-generation is running.");
        t.updateRateNow();
        return t.chatProgress(false, true);
    }

    /** Called every server tick. */
    public static void tick(MinecraftServer server) {
        PregenTask t = active;
        if (t == null) return;
        if (t.step()) {
            active = null;
            t.saveProgress(true);
            t.restoreRegionCache();
            PregenMap.writeInBackground(t.level);
            System.out.println("[Orbis Terrarum] Pre-generation finished: " + t.label + "\n" + t.progress());
            server.getPlayerList().broadcastSystemMessage(t.chatProgress(true, false), false);
        } else if (System.currentTimeMillis() - t.lastReport > 30_000) {
            // In chat (not the one-line action bar, which gets cut off), every 30 s: four short lines for the
            // player; the full picture goes to the log for later diagnosis.
            t.lastReport = System.currentTimeMillis();
            t.updateRateNow();
            if (System.currentTimeMillis() - t.lastMap > 600_000) {
                t.lastMap = System.currentTimeMillis();
                PregenMap.writeInBackground(t.level);
            }
            System.out.println("[Orbis Terrarum] " + t.label + "\n" + t.progress());
            server.getPlayerList().broadcastSystemMessage(t.chatProgress(false, false), false);
        }
    }

    /** Collects finished chunks (server thread, never blocking). @return true when finished */
    private boolean step() {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.future().isDone()) continue;
            it.remove();
            try {
                Object res = p.future().join();
                if (res instanceof ChunkResult<?> r ? r.isSuccess() : res != null) done++;
                else failed++;
            } catch (RuntimeException e) {
                failed++;
            }
            held.addLast(p.pos());
            while (held.size() > HOLD_TICKETS) level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, held.pollFirst(), 0);
            inFlight.decrementAndGet();
            slots.release();
            if (sweep != null) {
                AtomicInteger n = outstanding.get(p.row());
                if (n != null && n.decrementAndGet() <= 0) outstanding.remove(p.row(), n);
            }
        }
        long now = System.currentTimeMillis();
        // Held tickets are a cache, not a promise: when the loaded-chunk limit is near, give the oldest back so
        // chunks can unload (otherwise the producer waits for unloads that the held tickets prevent).
        int loaded = level.getChunkSource().getLoadedChunksCount();
        for (int i = 0; i < 16 && !held.isEmpty() && loaded > baselineLoaded + maxLoaded - 512; i++) {
            level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, held.pollFirst(), 0);
        }
        if (sweep != null) {
            int limit = producerRow; // rows below this are fully queued
            while (completedRow + 1 < limit && !outstanding.containsKey(completedRow + 1)) completedRow++;
            advanceUnloadedRow();
            // A write barrier every 10 s: whatever had left memory when it was taken is on disk once it completes,
            // and only that counts as progress for a resume (generated-but-unwritten rows are lost with a crash).
            if (barrier != null && barrier.isDone()) {
                flushedRow = Math.max(flushedRow, barrierRow);
                barrier = null;
            }
            if (barrier == null && now - lastBarrier > 10_000) {
                lastBarrier = now;
                barrierRow = unloadedRow;
                barrier = level.getChunkSource().chunkMap.synchronize(false);
            }
            if (now - lastSave > 10_000) {
                lastSave = now;
                saveProgress(false);
            }
        }
        if (!producerDone || !pending.isEmpty()) return false;
        // Everything is generated: release the tickets still held, let the chunks leave memory, then wait for
        // the disk to have them all.
        while (!held.isEmpty()) level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, held.pollFirst(), 0);
        if (finalBarrier == null) {
            // Quiet: the sweep's chunks have unloaded and their writes are queued (an unloading chunk queues its
            // write a tick or two after it leaves the loaded count, hence the pending-unloads check).
            boolean quiet = level.getChunkSource().getLoadedChunksCount() <= baselineLoaded + 64
                    && ((ChunkMapAccessor) level.getChunkSource().chunkMap).orbisterrarum$pendingUnloads().isEmpty();
            if (!quiet && now - producerFinishedAt < 120_000) return false;
            finalBarrier = level.getChunkSource().chunkMap.synchronize(false);
        }
        if (!finalBarrier.isDone()) return false;
        if (writeBacklog() > 0 && now - producerFinishedAt < 600_000) return false;
        if (sweep != null) flushedRow = unloadedRow = completedRow;
        return true;
    }

    private String progress() {
        long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
        int finished = done + failed;
        String failedNote = failed > 0 ? String.format(Locale.ROOT, " (%,d FAILED)", failed) : "";
        double rate = finished / (double) elapsed;
        WorldModel model = OrbisMod.model();
        String regions = model != null && model.regions() != null ? "\n  map " + model.regions().stats() : "";
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        int backlog = writeBacklog();
        String why = waiting;
        String load = String.format(Locale.ROOT, "  %d in flight, %,d chunks loaded (limit %,d), %s queued for the disk (limit %,d)%s\n  heap %,d / %,d MB (%.0f%% still in use after the last collection)%s",
                inFlight.get(), level.getChunkSource().getLoadedChunksCount(), baselineLoaded + maxLoaded,
                backlog < 0 ? "?" : String.format(Locale.ROOT, "%,d", backlog), MAX_PENDING_WRITES,
                why != null ? " -- " + why : "", usedMb, maxMb, 100 * oldGenAfterGc(), regions);
        if (sweep == null) {
            long remaining = rate > 0 ? (long) ((chunks.length - finished) / rate) : -1;
            return String.format(Locale.ROOT, "  %,d / %,d chunks%s (%.0f%%), %.1f chunks/s, ETA %s\n%s",
                    finished, chunks.length, failedNote, 100.0 * finished / Math.max(1, chunks.length), rate, eta(remaining), load);
        }
        int rowsDone = Math.max(0, completedRow - sweep.firstRow() + 1);
        int rowsOnDisk = Math.max(0, flushedRow - sweep.firstRow() + 1);
        int rowsFromStart = Math.max(0, completedRow - startRow + 1);
        int rowsLeft = sweep.rowCount() - rowsDone;
        long remaining = rowsFromStart > 0 ? (long) (elapsed * (double) rowsLeft / rowsFromStart) : -1;
        return String.format(Locale.ROOT, "  row %,d / %,d generated (%.0f%%), on disk up to row %,d, ETA %s\n  %,d chunks generated%s, %,d open-sea skipped, %.1f chunks/s\n%s",
                rowsDone, sweep.rowCount(), 100.0 * rowsDone / Math.max(1, sweep.rowCount()), rowsOnDisk, eta(remaining),
                finished, failedNote, skippedSea.get(), rate, load);
    }

    private void updateRateNow() {
        long now = System.currentTimeMillis();
        int finished = done + failed;
        long dt = now - rateWindowAt;
        if (dt >= 5_000) {
            rateNow = (finished - rateWindowDone) * 1000.0 / dt;
            rateWindowAt = now;
            rateWindowDone = finished;
        }
    }

    /** The place without the "(largest part)" / "(all parts)" tail. */
    private String shortLabel() {
        int i = label.indexOf(" (");
        return i > 0 ? label.substring(0, i) : label;
    }

    private static MutableComponent line(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(colour);
    }

    /**
     * The progress as four short chat lines that fit the chat width without wrapping:
     * <pre>
     * Pregen Bergen - 35% - ETA 17.3 h
     *   row 884 of 2,505 - on disk to row 873
     *   519,642 chunks - 17.9/s now - 18.0/s avg
     *   loaded 4,140 - disk queue 0 - heap 6.7/16 GB
     * </pre>
     * plus, for /orbis pregen status, a line with the map-region and in-flight details.
     */
    private Component chatProgress(boolean finished, boolean detailed) {
        long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
        int total = done + failed;
        double avg = total / (double) elapsed;
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        int backlog = writeBacklog();
        String pct, where, remaining;
        if (sweep == null) {
            pct = String.format(Locale.ROOT, "%.0f%%", 100.0 * total / Math.max(1, chunks.length));
            where = String.format(Locale.ROOT, "%,d of %,d chunks", total, chunks.length);
            remaining = eta(avg > 0 ? (long) ((chunks.length - total) / avg) : -1);
        } else {
            int rowsDone = Math.max(0, completedRow - sweep.firstRow() + 1);
            int rowsOnDisk = Math.max(0, flushedRow - sweep.firstRow() + 1);
            int rowsFromStart = Math.max(0, completedRow - startRow + 1);
            pct = String.format(Locale.ROOT, "%.0f%%", 100.0 * rowsDone / Math.max(1, sweep.rowCount()));
            where = String.format(Locale.ROOT, "row %,d of %,d - on disk to row %,d", rowsDone, sweep.rowCount(), rowsOnDisk);
            remaining = eta(rowsFromStart > 0 ? (long) (elapsed * (double) (sweep.rowCount() - rowsDone) / rowsFromStart) : -1);
        }
        MutableComponent msg = line("Pregen " + shortLabel(), ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD);
        msg.append(line(finished ? " - finished in " + eta(elapsed) : " - " + pct + " - ETA " + remaining, ChatFormatting.WHITE).withStyle(style -> style.withBold(false)));
        msg.append(line("\n  " + where, ChatFormatting.GRAY));
        msg.append(line(String.format(Locale.ROOT, "\n  %,d chunks - %.1f/s now - %.1f/s avg", total, rateNow, avg), ChatFormatting.WHITE));
        if (failed > 0) msg.append(line(String.format(Locale.ROOT, " - %,d FAILED", failed), ChatFormatting.RED));
        msg.append(line(String.format(Locale.ROOT, "\n  loaded %,d - disk queue %s - heap %.1f/%d GB",
                level.getChunkSource().getLoadedChunksCount(), backlog < 0 ? "?" : String.format(Locale.ROOT, "%,d", backlog), usedMb / 1024.0, maxMb / 1024), ChatFormatting.GRAY));
        String why = waiting;
        int terrainWaits = RealWorldChunkGenerator.terrainWaiting();
        WorldModel wm = OrbisMod.model();
        int regionWaits = wm != null && wm.regions() != null ? wm.regions().terrainRetries() : 0;
        if (terrainWaits > 0 || regionWaits > 0) why = "waiting for terrain downloads (internet down?)";
        if (why != null) msg.append(line(" - " + why, ChatFormatting.YELLOW));
        if (detailed) {
            WorldModel model = OrbisMod.model();
            String regions = model != null && model.regions() != null ? model.regions().stats() : "no map data";
            msg.append(line(String.format(Locale.ROOT, "\n  %d in flight - limit %,d - GC %.0f%% - %s", inFlight.get(), baselineLoaded + maxLoaded, 100 * oldGenAfterGc(), regions), ChatFormatting.DARK_GRAY));
        }
        return msg;
    }

    private static String eta(long seconds) {
        return seconds < 0 ? "?" : seconds > 3600 ? String.format(Locale.ROOT, "%.1f h", seconds / 3600.0)
                : seconds > 60 ? (seconds / 60) + " min" : seconds + " s";
    }
}
