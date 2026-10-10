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
    /** A drawn area's own Skip open sea button; null: the world's setting (the /orbis pregen commands). */
    private Boolean skipSeaChoice;
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
    public static final TicketType PREGEN_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    /**
     * Pre-generation of a cubic world (versions/26.3 cubic.CubicPregen; null elsewhere): its chunks hold no blocks, so the sweeps here hand
     * their area to it instead (the commands, map selections and automatic sweeps stay the same).
     */
    public interface CubicSweeper {
        /** Starts (or resumes, by {@code key}) a sweep over the area's cube columns; a message for the player. */
        Component start(ServerLevel level, String label, AreaSweep area, boolean skipSea, String key);

        Component stop();

        Component status();

        boolean running();

        /** Whether the level is a cubic one (asked of Cubic Chunks, so it is known before the first cube is made). */
        boolean cubic(ServerLevel level);
    }

    public static volatile CubicSweeper cubicSweeper;

    /** The cubic sweeper when the level is cubic, else null. */
    private static CubicSweeper cubic(ServerLevel level) {
        CubicSweeper c = cubicSweeper;
        return c != null && c.cubic(level) ? c : null;
    }

    private final ServerLevel level;
    private final String label;
    // radius mode
    private final long[] chunks;
    // area mode
    private final AreaSweep sweep;
    private final Path progressFile;
    private final int startRow;
    private boolean mainlandOnly = true;
    /** The fingerprint of a drawn selection (resume only the same one), or null. */
    private String selectionId;
    private long estimatedChunks;
    private final ConcurrentHashMap<Integer, AtomicInteger> outstanding = new ConcurrentHashMap<>();
    private final AtomicLong skippedSea = new AtomicLong();
    /** Chunks already finished on disk, not loaded (see DiskChunks): loading them would rewrite them all. */
    private final AtomicLong skippedExisting = new AtomicLong();
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
    /** Builds the terrain ahead of the sweep (fast pre-generation), or null. */
    private volatile FastPregen fast;
    /** The row of the chunk the sweep asked for last (fast pre-generation keeps within a few tile-rows of it). */
    private volatile int sweepRow;
    private int done, failed;
    private final long startedAt = System.currentTimeMillis();
    private long lastReport = System.currentTimeMillis();
    private long lastChat = System.currentTimeMillis();
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
        CubicSweeper cubic = cubic(level);
        if (cubic != null) {
            if (cubic.running()) return Component.literal("A pre-generation is already running; /orbis pregen stop first.\n" + cubic.status().getString());
            int ccx = centerX >> 4, ccz = centerZ >> 4, cr = (radiusBlocks >> 4) + 1;
            ChunkSelection disc = new ChunkSelection();
            for (int dz = -cr; dz <= cr; dz++) {
                int w = (int) Math.floor(Math.sqrt((double) cr * cr - (double) dz * dz));
                disc.addRow(ccz + dz, new int[]{ccx - w, ccx + w});
            }
            HardLimit.extend(level.getServer(), disc);
            WorldModel m = OrbisMod.model();
            double mpb = m == null ? 1.0 : m.cfg().metersPerBlock;
            return cubic.start(level, String.format(Locale.ROOT, "%.1f km around %d, %d", radiusBlocks * mpb / 1000.0, centerX, centerZ),
                    new AreaSweep(disc, new ArrayList<>()), m != null && m.cfg().pregenSkipOpenSea,
                    "cubic-radius-" + centerX + "_" + centerZ + "_" + radiusBlocks + ".json");
        }
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
        ChunkSelection circle = new ChunkSelection();
        for (int dz = -r; dz <= r; dz++) {
            int w = (int) Math.floor(Math.sqrt((double) r * r - (double) dz * dz));
            circle.addRow(cz + dz, new int[]{cx - w, cx + w});
        }
        HardLimit.extend(level.getServer(), circle);

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
        tellNeeds(level);
        double estMb = packed.length * 12 / 1024.0;
        return Component.literal(String.format(Locale.ROOT,
                "Pre-generating %s: %,d chunks (%d map regions queued). Rough world size afterwards: %.0f MB. Progress every 30 s; /orbis pregen stop to end.%s",
                label, packed.length, queuedRegions, estMb, syncNote(level)));
    }

    private void startRadiusProducer() {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet planned = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunks);
        boolean skip = skipSea();
        startFast(visitor -> {
            for (long p : chunks) {
                if (stopRequested) break;
                int cx = (int) (p >> 32), cz = (int) p;
                if (skip && isOpenSea(model, cx, cz)) continue;
                visitor.visit(cx, cz);
            }
        }, (cx, cz) -> planned.contains(pack(cx, cz)) && !(skip && isOpenSea(model, cx, cz)));
        producer = new Thread(() -> {
            DiskChunks disk = new DiskChunks(level);
            try {
                for (long p : chunks) {
                    if (stopRequested) break;
                    int cx = (int) (p >> 32), cz = (int) p;
                    if (disk.finished(cx, cz)) {
                        skippedExisting.incrementAndGet();
                        continue;
                    }
                    if (skip && isOpenSea(model, cx, cz)) {
                        skippedSea.incrementAndGet();
                        continue;
                    }
                    submit(cx, cz);
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
        CubicSweeper cubic = cubic(level);
        if (cubic != null) {
            if (cubic.running()) return Component.literal("A pre-generation is already running; /orbis pregen stop first.\n" + cubic.status().getString());
            return cubic.start(level, outline.name(), sweep, model.cfg().pregenSkipOpenSea, "cubic-" + fileName);
        }
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
        ChunkSelection area = new ChunkSelection();
        for (int row = sweep.firstRow(); row <= sweep.lastRow(); row++) {
            List<int[]> ranges = sweep.rowRanges(row);
            int[] flat = new int[ranges.size() * 2];
            int k = 0;
            for (int[] range : ranges) {
                estimate += range[1] - range[0] + 1;
                spanMin = Math.min(spanMin, range[0]);
                spanMax = Math.max(spanMax, range[1]);
                flat[k++] = range[0];
                flat[k++] = range[1];
            }
            area.addRow(row, flat);
        }
        HardLimit.extend(level.getServer(), area);
        t.estimatedChunks = estimate;
        if (spanMin <= spanMax) t.widenRegionCache((spanMax - spanMin + 1) * 16);
        t.startAreaProducer(model);
        active = t;
        tellNeeds(level);

        String extractNote;
        LocalExtractStore store = LocalExtractStore.get(OrbisMod.dataDir().resolve("extracts"));
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

    /**
     * Starts (or resumes) a sweep over a selection drawn on the world map: exactly its chunks, north to south, sea
     * included. Progress is kept per selection; drawing the same selection again and pressing Generate resumes.
     */
    public static Component startSelection(ServerLevel level, ChunkSelection selection) {
        return startSelection(level, selection, null);
    }

    /** A drawn area, with its own Skip open sea choice (null: the world's setting). */
    public static synchronized Component startSelection(ServerLevel level, ChunkSelection selection, Boolean skipSea) {
        if (active != null) return Component.literal("A pre-generation is already running (" + active.label + "); /orbis pregen stop first.\n" + active.progress());
        WorldModel model = OrbisMod.model();
        if (model == null) return Component.literal("The world model is not ready yet.");
        if (selection.isEmpty()) return Component.literal("The selection is empty.");
        List<double[][]> outline = new ArrayList<>();
        int[] xr = selection.xRange();
        outline.add(new double[][]{{xr[0] * 16.0, selection.firstRow() * 16.0}, {(xr[1] + 1) * 16.0, selection.firstRow() * 16.0},
                {(xr[1] + 1) * 16.0, (selection.lastRow() + 1) * 16.0}, {xr[0] * 16.0, (selection.lastRow() + 1) * 16.0}});
        AreaSweep sweep = new AreaSweep(selection, outline);
        lastSweep = sweep;
        HardLimit.extend(level.getServer(), selection);
        String id = selection.fingerprint();
        CubicSweeper cubic = cubic(level);
        if (cubic != null) {
            if (cubic.running()) return Component.literal("A pre-generation is already running; /orbis pregen stop first.\n" + cubic.status().getString());
            return cubic.start(level, "the selection", sweep, skipSea != null ? skipSea : model.cfg().pregenSkipOpenSea, "cubic-selection-" + id + ".json");
        }
        Path file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen").resolve("selection-" + id + ".json");
        int startRow = sweep.firstRow();
        String resumed = "";
        JsonObject saved = readProgress(file);
        if (saved != null && matches(saved, model, true) && saved.has("selection") && id.equals(saved.get("selection").getAsString())) {
            int completed = saved.get("completedRow").getAsInt();
            if (completed >= sweep.firstRow() && completed < sweep.lastRow()) {
                startRow = completed + 1;
                resumed = String.format(Locale.ROOT, " Resuming at row %,d of %,d (%,d chunks were done before).",
                        startRow - sweep.firstRow() + 1, sweep.rowCount(), saved.has("done") ? saved.get("done").getAsLong() : 0);
            }
        }
        long chunks = selection.count();
        double km2 = chunks * 256.0 * model.cfg().metersPerBlock * model.cfg().metersPerBlock / 1e6;
        String label = String.format(Locale.ROOT, "Selection (%,.0f km²)", km2);
        PregenTask t = new PregenTask(level, label, sweep, file, startRow);
        t.model = model;
        t.skipSeaChoice = skipSea;
        t.selectionId = id;
        t.estimatedChunks = chunks;
        t.widenRegionCache((xr[1] - xr[0] + 1) * 16);
        t.startAreaProducer(model);
        active = t;
        warnIfTooLow(level, model, selection);
        tellNeeds(level);
        return Component.literal(String.format(Locale.ROOT,
                "Pre-generating the selection drawn on the map\n  %,d chunks, about %,.0f km², %,d chunk rows north to south.%s\n  Progress every 30 s; /orbis pregen stop to end (Generate the same selection again to resume).%s",
                chunks, km2, sweep.rowCount(), resumed.isEmpty() ? "" : "\n " + resumed, t.loadNote() + syncNote(level)));
    }

    /** The game's "sync chunk writes" option makes every chunk write a separate synchronous disk write. */
    private static String syncNote(ServerLevel level) {
        String pause = level.getServer().isSingleplayer()
                ? "\n  The game will not pause while the sweep runs (Escape menu, switching windows): a paused game stops unloading and saving chunks."
                : "";
        if (!level.getServer().forceSynchronousWrites() || com.berg.orbis.OrbisMod.fastChunkWrites(level.getServer())) return pause;
        return pause + "\n  Fast chunk writes is OFF (Orbis Terrarum settings, Streaming), so every chunk is a separate synchronous disk write and the "
                + "sweep is paced by the disk (it waits whenever more than " + String.format(Locale.ROOT, "%,d", MAX_PENDING_WRITES)
                + " chunks are queued for it). For a much faster sweep switch it on and open the world again.";
    }

    private void startAreaProducer(WorldModel model) {
        sweepRow = startRow;
        // At most two tile-rows ahead of the sweep: enough for every chunk's neighbours to be built before the sweep
        // asks for it (in serpentine order those of a tile-row's last rows come late in the next tile-row), and the
        // map regions are still in memory when Minecraft decorates the chunks.
        startFast(visitor -> walkArea(model, false, (cx, row) -> {
            while (!stopRequested && row > sweepRow + 2 * TILE + 4) Thread.sleep(50);
            visitor.visit(cx, row);
        }), (cx, cz) -> plannedInArea(model, cx, cz));
        producer = new Thread(() -> {
            DiskChunks disk = new DiskChunks(level);
            try {
                walkArea(model, true, (cx, row) -> {
                    if (disk.finished(cx, row)) {
                        skippedExisting.incrementAndGet();
                        return;
                    }
                    outstanding.computeIfAbsent(row, k -> new AtomicInteger()).incrementAndGet();
                    submit(cx, row);
                });
                if (!stopRequested) producerRow = sweep.lastRow() + 1;
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

    /**
     * The area's chunks in sweep order: tile-rows of TILE rows, tiles across in serpentine order. The sweep itself
     * ({@code forSweep}) also records its row and queues the map regions ahead; fast pre-generation walks the same
     * order to build the terrain ahead of it.
     */
    private void walkArea(WorldModel model, boolean forSweep, FastPregen.Visitor visitor) throws InterruptedException {
        int lastRow = sweep.lastRow();
        DiskChunks disk = new DiskChunks(level);
        java.util.Map<Long, Boolean> work = new java.util.HashMap<>();
        for (int tileRow = Math.floorDiv(startRow, TILE) * TILE; tileRow <= lastRow && !stopRequested; tileRow += TILE) {
            int r0 = Math.max(tileRow, startRow), r1 = Math.min(tileRow + TILE - 1, lastRow);
            if (forSweep) producerRow = r0; // every row below this one is fully queued
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            for (int row = r0; row <= r1; row++) {
                for (int[] range : sweep.rowRanges(row)) {
                    minX = Math.min(minX, range[0]);
                    maxX = Math.max(maxX, range[1]);
                }
            }
            if (minX > maxX) continue;
            // Queue the map regions of this tile-row and of the two region rows after it now (below player
            // demand), so the workers find them decoded instead of waiting a minute per region at every region
            // edge. In a fresh area every region downloads its photos, terrain, road widths and rock first; with
            // one row queued the workers sat idle two thirds of the time while the terrain builders waited for
            // the regions still downloading (Stavanger 1:2, 2 Oct 2026: 23-30 chunks/s with the world paused).
            // Fast pre-generation walks ahead of the sweep, so its walk queues them earlier still.
            if (model.regions() != null) {
                OsmRegionManager regions = model.regions();
                int ahead = 2 * regions.regionSize() / 16; // two region rows, in chunk rows
                int rz0 = regions.regionCoord(r0 << 4), rz1 = regions.regionCoord(Math.min(lastRow, r1 + TILE + ahead) << 4);
                int rx0 = regions.regionCoord(minX << 4), rx1 = regions.regionCoord((maxX << 4) + 15);
                for (int rz = rz0; rz <= rz1; rz++) {
                    for (int rx = rx0; rx <= rx1; rx++) {
                        // Only regions with chunks left to make: a repair or a resume has most of the area on disk
                        // already, and preparing every region in its path cost 10-15 s each for nothing (Bergen,
                        // 2 Oct 2026). A neighbour a chunk needs is still fetched when it asks for it.
                        int qrx = rx, qrz = rz;
                        if (work.computeIfAbsent(((long) rx << 32) ^ (rz & 0xffffffffL), k -> regionHasWork(disk, regions.regionSize(), qrx, qrz))) {
                            regions.future(rx, rz, 30);
                        }
                    }
                }
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
                            if (skipSea() && isOpenSea(model, cx, row)) {
                                if (forSweep) skippedSea.incrementAndGet();
                                continue;
                            }
                            visitor.visit(cx, row);
                        }
                    }
                }
                if (!forSweep) {
                    // Fast pre-generation also builds the rows just below the tile now: the sweep asks for a chunk
                    // only once the chunks around it are built, and in serpentine order the next tile-row reaches
                    // the ones below a tile's last rows a whole tile-row later (all of it waiting in memory).
                    int reach = FastPregen.RING + 1;
                    for (int row = r1 + 1; row <= Math.min(lastRow, r1 + reach) && !stopRequested; row++) {
                        for (int cx = x0 - reach; cx <= x1 + reach && !stopRequested; cx++) {
                            if (plannedInArea(model, cx, row)) visitor.visit(cx, row);
                        }
                    }
                }
            }
        }
    }

    /** Whether any chunk of the sweep in this map region is still to be made (not finished on disk). */
    private boolean regionHasWork(DiskChunks disk, int regionSize, int rx, int rz) {
        int c0x = Math.floorDiv(rx * regionSize, 16), c1x = Math.floorDiv((rx + 1) * regionSize, 16) - 1;
        int c0z = Math.floorDiv(rz * regionSize, 16), c1z = Math.floorDiv((rz + 1) * regionSize, 16) - 1;
        for (int cz = Math.max(c0z, startRow); cz <= Math.min(c1z, sweep.lastRow()); cz++) {
            for (int[] range : sweep.rowRanges(cz)) {
                for (int cx = Math.max(range[0], c0x); cx <= Math.min(range[1], c1x); cx++) {
                    if (!disk.finished(cx, cz)) return true;
                }
            }
        }
        return false;
    }

    /** Whether the area sweep covers this chunk (what walkArea visits). */
    private boolean plannedInArea(WorldModel model, int cx, int cz) {
        if (cz < startRow || cz > sweep.lastRow()) return false;
        for (int[] range : sweep.rowRanges(cz)) {
            if (cx >= range[0] && cx <= range[1]) return !(skipSea() && isOpenSea(model, cx, cz));
        }
        return false;
    }

    /** Starts building the terrain ahead of the sweep when "Fast pre-generation" is on and this is an Orbis world. */
    private void startFast(FastPregen.Walk walk, FastPregen.Planned planned) {
        OrbisConfig cfg = OrbisMod.config();
        if (cfg != null && !cfg.fastPregen) return;
        if (!(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator generator)) return;
        try {
            fast = FastPregen.start(level, generator, walk, planned);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[orbis] Fast pre-generation unavailable, sweeping the usual way: " + e);
        }
    }

    private void stopFast() {
        FastPregen f = fast;
        fast = null;
        if (f != null) f.stop();
    }

    /** Asks the chunk system for a chunk (from this non-server thread the request is queued, not blocking). */
    private void submit(int cx, int cz) throws InterruptedException {
        sweepRow = cz;
        FastPregen f = fast;
        if (f != null) {
            while (!stopRequested && !f.ready(cx, cz)) {
                waiting = "building terrain ahead";
                Thread.sleep(20);
            }
        }
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
        int needed = Math.min(480, 5 * across + 16); // the rows in use and the two queued ahead
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

    /**
     * The world's "Skip open sea" setting: chunks that are deep sea all over are left out of every sweep (radius,
     * place outline, drawn selection) and generate when someone sails there; off, the sweep generates them too.
     */
    private boolean skipSea() {
        if (skipSeaChoice != null) return skipSeaChoice;
        return model != null && model.cfg().pregenSkipOpenSea;
    }

    /** Whether the chunk is deep sea all over (the sweeps' Skip open sea leaves it out). */
    public static boolean isOpenSea(WorldModel model, int cx, int cz) {
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
                            + "  \"firstRow\": %d,\n  \"lastRow\": %d,\n  \"completedRow\": %d,\n  \"done\": %d,\n  \"skippedSea\": %d,\n%s  \"saved\": \"%s\"\n}\n",
                    label.replace("\"", "'"), model.cfg().originLat, model.cfg().originLon, model.cfg().metersPerBlock,
                    mainlandOnly, sweep.firstRow(), sweep.lastRow(), flushedRow, done, skippedSea.get(),
                    selectionId == null ? "" : "  \"selection\": \"" + selectionId + "\",\n", java.time.Instant.now());
            Files.writeString(progressFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save pregen progress: " + e.getMessage());
        }
    }

    // ---- control ---------------------------------------------------------------------

    public static synchronized Component stop() {
        PregenTask t = active;
        CubicSweeper cubic = cubicSweeper;
        if (t == null && cubic != null && cubic.running()) return cubic.stop();
        if (t == null) return Component.literal("No pre-generation is running.");
        t.stopRequested = true;
        t.stopFast();
        if (t.producer != null) t.producer.interrupt();
        t.saveProgress(false);
        t.restoreRegionCache();
        active = null;
        t.remember("stopped");
        for (Pending p : t.pending) t.level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, p.pos(), 0);
        t.pending.clear();
        while (!t.held.isEmpty()) t.level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, t.held.pollFirst(), 0);
        return Component.literal("Stopped " + t.label + "\n" + t.progress()
                + "\n  Chunks still queued for the disk are written in the background."
                + (t.selectionId != null ? " Generate the same selection from the map again to resume from the last row on disk."
                : t.sweep != null ? " Run the same command again to resume from the last row on disk." : ""));
    }

    // ---- history (the mod's Overview tab) --------------------------------------------------

    private static final java.nio.file.Path HISTORY_FILE_NAME = java.nio.file.Path.of("pregen-history.txt");
    private static final int HISTORY_KEEP = 30;

    /**
     * One line in the installation's pre-generation history (config folder, newest last): when, which world, what,
     * how many chunks, how long and how fast. Shown in Mod Menu → Orbis Terrarum → Overview.
     */
    private void remember(String how) {
        try {
            long seconds = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
            int chunks = done + failed;
            String world = level.getServer().getWorldData().getLevelName();
            WorldModel m = model != null ? model : OrbisMod.model();
            String scale = m == null ? "" : String.format(Locale.ROOT, ", 1:%s", trimScale(m.cfg().metersPerBlock));
            String line = String.format(Locale.ROOT, "%s  %s%s: %s, %s, %,d chunks in %s (%.1f chunks/s)",
                    java.time.LocalDate.now(), world, scale, label, how, chunks, duration(seconds), chunks / (double) seconds);
            java.nio.file.Path file = OrbisMod.configDir().resolve(HISTORY_FILE_NAME);
            synchronized (HISTORY_FILE_NAME) {
                List<String> lines = new ArrayList<>(java.nio.file.Files.exists(file)
                        ? java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8) : List.of());
                lines.add(line);
                if (lines.size() > HISTORY_KEEP) lines = lines.subList(lines.size() - HISTORY_KEEP, lines.size());
                java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("[Orbis Terrarum] Could not note the pre-generation in its history: " + e);
        }
    }

    /** The last pre-generations of this installation, newest first. */
    public static List<String> history(int max) {
        try {
            java.nio.file.Path file = OrbisMod.configDir().resolve(HISTORY_FILE_NAME);
            if (!java.nio.file.Files.exists(file)) return List.of();
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8));
            java.util.Collections.reverse(lines);
            return lines.subList(0, Math.min(max, lines.size()));
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static String trimScale(double metersPerBlock) {
        return metersPerBlock == Math.rint(metersPerBlock) ? String.valueOf((long) metersPerBlock) : String.valueOf(metersPerBlock);
    }

    private static String duration(long seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, seconds / 60 % 60)
                : String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
    }

    /** The last task that ran to the end (not stopped), for {@link AutoPregen}. */
    private static volatile PregenTask lastFinished;

    public static PregenTask lastFinished() {
        return lastFinished;
    }

    /**
     * The server is stopping: put the sweep aside with its progress saved (an area or selection resumes when started
     * again) and forget it, so the next world opened in this session does not find a stale task "running".
     */
    public static synchronized void serverStopping() {
        MinecraftServer paused = pausedByUs;
        if (paused != null) {
            // Not left frozen for the next start (the sweep resumes, and freezes it again, by itself).
            if (paused.tickRateManager().isFrozen()) paused.tickRateManager().setFrozen(false);
            pausedByUs = null;
        }
        PregenTask t = active;
        if (t == null) return;
        t.stopRequested = true;
        t.stopFast();
        if (t.producer != null) t.producer.interrupt();
        t.saveProgress(false);
        t.restoreRegionCache();
        active = null;
        t.remember("put aside");
        t.pending.clear();
        t.held.clear();
        System.out.println("[Orbis Terrarum] Pre-generation put aside as the server stops: " + t.label + " (resumes when started again)");
    }

    /** Blocks a selection's highest point may be lowered by before the players are told (noise of the coarse terrain). */
    public static final int SQUEEZE_WARNING_BLOCKS = 8;

    /**
     * Tells the players, once worked out in the background, when the selection's mountains are higher than this world
     * allows. A world's height is set for good when it is created (fitted to the place then), so a selection added
     * later, the Himalayas to a Kathmandu world, has its peaks squeezed under the ceiling.
     */
    /** In chat, what the world's services need of the player (a VPN, patience), with whether each answers now. */
    private static void tellNeeds(ServerLevel level) {
        WorldModel model = OrbisMod.model();
        if (model == null) return;
        CompletableFuture.runAsync(() -> {
            for (com.berg.orbis.config.DataSources.Requirement r : com.berg.orbis.config.DataSources.requirements(model.cfg())) {
                boolean ok = com.berg.orbis.config.DataSources.reachable(r);
                String text = r.name() + ": " + r.text() + (ok ? " It answers from this network." : " It does not answer from this network right now.");
                System.out.println("[orbis] " + text);
                level.getServer().execute(() -> level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal(text).withStyle(ok ? ChatFormatting.GOLD : ChatFormatting.RED), false));
            }
        }, net.minecraft.util.Util.backgroundExecutor()).exceptionally(e -> null);
    }

    private static void warnIfTooLow(ServerLevel level, WorldModel model, ChunkSelection selection) {
        CompletableFuture.supplyAsync(() -> WorldHeight.peak(model, selection), net.minecraft.util.Util.backgroundExecutor())
                .thenAccept(p -> {
                    if (p == null || p.squeezedBy() <= SQUEEZE_WARNING_BLOCKS) return;
                    String text = String.format(Locale.ROOT,
                            "Mountains in this selection reach Y %,d, higher than this world allows (it ends at Y %,d): their tops are squeezed by up to %,d blocks."
                                    + " A world created with a larger World height (Advanced tab) keeps them.",
                            Math.round(p.unsqueezedY()), model.cfg().maxY(), p.squeezedBy());
                    System.out.println("[orbis] " + text);
                    level.getServer().execute(() -> level.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal(text).withStyle(ChatFormatting.GOLD), false));
                })
                .exceptionally(e -> null);
    }

    /** True while a sweep is running (the client then refuses to pause the game, see MinecraftPauseMixin). */
    public static boolean isRunning() {
        CubicSweeper cubic = cubicSweeper;
        return active != null || (cubic != null && cubic.running());
    }

    /**
     * Chunks the level must unload every tick while this sweep runs (see mixin ChunkMapMixin). 0 for other
     * levels and when no sweep runs (vanilla rules).
     */
    public static int unloadsPerTick(ServerLevel level) {
        PregenTask t = active;
        if (t == null || t.level != level) return 0;
        return 32;
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
        CubicSweeper cubic = cubicSweeper;
        if (t == null && cubic != null && cubic.running()) return cubic.status();
        if (t == null) return Component.literal("No pre-generation is running.");
        t.updateRateNow();
        return t.chatProgress(false, true);
    }

    /** The server whose world this mod froze for a sweep (so only that freeze is ever undone), or null. */
    private static volatile MinecraftServer pausedByUs;
    /** A sweep that finished no chunk for this long while the world was paused gets the world running again. */
    private static final long PAUSE_STALL_MS = 120_000;
    /** The sweep the pause was lifted for after repeated stalls (it stays lifted for that sweep). */
    private static volatile PregenTask stalledUnderPause;
    /** After a stall the world runs this long (tickets expire, chunks unload), then pauses again. */
    private static final long PAUSE_RETRY_MS = 60_000;
    private int doneAtLastCheck = -1;
    private long doneChangedAt;
    private int pauseStalls;
    private long pauseAgainAt;

    /**
     * While a sweep runs the world stands still, as with /tick freeze: mobs, random block ticks, spawning,
     * redstone, weather and time. Chunks keep loading, generating, unloading and saving, and players can move. A
     * flight recording of a Stord sweep had the server thread spending 58% of its time on mobs, 17% on random block
     * ticks and 9% on spawning around the player, while it also runs part of every chunk's generation and all the
     * unloads. A freeze someone made themselves is left alone, and so is an unfreeze they make during the sweep.
     */
    private static void syncWorldPause(MinecraftServer server) {
        OrbisConfig cfg = OrbisMod.config();
        PregenTask t = active;
        long now = System.currentTimeMillis();
        boolean want = t != null && (cfg == null || cfg.pregenPauseWorld) && stalledUnderPause != t && now >= t.pauseAgainAt;
        if (want && pausedByUs == server) {
            // Waiting for Orbis's own data is not a stall the pause causes: a fresh area's map regions and terrain
            // download for minutes before the first chunks finish (Stavanger, 2 Oct 2026: the pause was lifted for
            // the whole sweep after two minutes of downloads, and mobs then took half the server thread).
            WorldModel m = OrbisMod.model();
            boolean ownData = "building terrain ahead".equals(t.waiting)
                    || (m != null && m.regions() != null && m.regions().preparing() > 0);
            if (t.done != t.doneAtLastCheck || ownData) {
                t.doneAtLastCheck = t.done;
                t.doneChangedAt = now;
            } else if (now - t.doneChangedAt > PAUSE_STALL_MS) {
                // Safety net: whatever else holds the sweep up while the world is paused (a frozen world keeps
                // chunk tickets from expiring), the world runs a minute and pauses again; after three such stalls
                // it runs for the rest of the sweep.
                want = false;
                if (++t.pauseStalls >= 3) {
                    stalledUnderPause = t;
                    System.out.println("[Orbis Terrarum] No chunk finished in " + PAUSE_STALL_MS / 1000 + " s while the world was paused ("
                            + t.waiting + "), three times; the world runs again for the rest of this pre-generation");
                } else {
                    t.pauseAgainAt = now + PAUSE_RETRY_MS;
                    System.out.println("[Orbis Terrarum] No chunk finished in " + PAUSE_STALL_MS / 1000 + " s while the world was paused ("
                            + t.waiting + "); the world runs for " + PAUSE_RETRY_MS / 1000 + " s, then pauses again");
                }
            }
        }
        net.minecraft.server.ServerTickRateManager ticks = server.tickRateManager();
        if (want && pausedByUs == null && !ticks.isFrozen()) {
            ticks.setFrozen(true);
            pausedByUs = server;
            t.doneAtLastCheck = t.done;
            t.doneChangedAt = System.currentTimeMillis();
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "The world is paused while pre-generation runs (mobs, crops, redstone, time and weather stand still); it carries on when the pre-generation ends or stops.")
                    .withStyle(ChatFormatting.GRAY), false);
        } else if (!want && pausedByUs == server) {
            if (ticks.isFrozen()) ticks.setFrozen(false);
            pausedByUs = null;
            server.getPlayerList().broadcastSystemMessage(Component.literal("The world carries on.").withStyle(ChatFormatting.GRAY), false);
        }
    }

    /** Called every server tick. */
    public static void tick(MinecraftServer server) {
        syncWorldPause(server);
        PregenTask t = active;
        if (t == null) {
            hideBar();
            return;
        }
        if (++barTicks % 20 == 0) showBar(server, t);
        if (t.step()) {
            active = null;
            lastFinished = t;
            t.stopFast();
            t.saveProgress(true);
            t.restoreRegionCache();
            t.remember("finished");
            PregenMap.writeInBackground(t.level);
            System.out.println("[Orbis Terrarum] Pre-generation finished: " + t.label + "\n" + t.progress());
            server.getPlayerList().broadcastSystemMessage(t.chatProgress(true, false), false);
        } else if (System.currentTimeMillis() - t.lastReport > 30_000) {
            // The full picture to the log every 30 s for later diagnosis; in chat every 2 minutes, four short lines
            // (the bar at the top of the screen shows the live state).
            t.lastReport = System.currentTimeMillis();
            t.updateRateNow();
            if (System.currentTimeMillis() - t.lastMap > 600_000) {
                t.lastMap = System.currentTimeMillis();
                PregenMap.writeInBackground(t.level);
            }
            System.out.println("[Orbis Terrarum] " + t.label + "\n" + t.progress());
            if (System.currentTimeMillis() - t.lastChat > 120_000) {
                t.lastChat = System.currentTimeMillis();
                server.getPlayerList().broadcastSystemMessage(t.chatProgress(false, false), false);
            }
        }
    }

    // ------------------------------------------------------------------ the bar

    /**
     * A bar at the top of every player's screen while a sweep runs, updated every second: how far it is and what it
     * is doing right now (generating, or which data it is downloading: map data, terrain, building heights...). The
     * chat line every 30 s showed "0 chunks/s" for minutes while a fresh area's data downloaded, and players could
     * not tell a sweep at work from a stuck one. A vanilla boss bar: friends on vanilla clients see it too.
     */
    private static net.minecraft.server.level.ServerBossEvent bar;
    private static int barTicks;
    private int barDone = -1;
    private long barAt;
    private double barRate;

    private static void showBar(MinecraftServer server, PregenTask t) {
        if (bar == null) {
            bar = new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(), Component.literal("Pre-generation"),
                    net.minecraft.world.BossEvent.BossBarColor.BLUE, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        // Chunks per second over the last few seconds.
        long now = System.currentTimeMillis();
        int finished = t.done + t.failed;
        if (t.barDone < 0) {
            t.barDone = finished;
            t.barAt = now;
        } else if (now - t.barAt >= 3000) {
            double r = (finished - t.barDone) * 1000.0 / (now - t.barAt);
            t.barRate = finished == t.barDone ? 0 : t.barRate <= 0 ? r : 0.5 * t.barRate + 0.5 * r;
            t.barDone = finished;
            t.barAt = now;
        }
        float fraction = t.fraction();
        boolean generating = t.barRate >= 0.5;
        String text = String.format(Locale.ROOT, "Pre-generating %s - %.0f%% - %s", t.shortLabel(), 100 * fraction, t.barActivity());
        bar.setName(Component.literal(text));
        bar.setProgress(Math.max(0f, Math.min(1f, fraction)));
        bar.setColor(generating ? net.minecraft.world.BossEvent.BossBarColor.GREEN : net.minecraft.world.BossEvent.BossBarColor.YELLOW);
    }

    private static void hideBar() {
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
    }

    /** How far the sweep is, 0..1 (rows for an area sweep, chunks otherwise). */
    private float fraction() {
        if (sweep == null) {
            long sea = skippedSea.get() + skippedExisting.get();
            return (float) ((done + failed + sea) / (double) Math.max(1, chunks.length));
        }
        return (float) (Math.max(0, completedRow - sweep.firstRow() + 1) / (double) Math.max(1, sweep.rowCount()));
    }

    /** What the sweep is doing right now, in a few words. */
    private String barActivity() {
        String downloads = downloadsNow();
        if (barRate >= 0.5) {
            String s = String.format(Locale.ROOT, "%.1f chunks/s", barRate);
            if (sweep != null) {
                int rowsDone = Math.max(0, completedRow - sweep.firstRow() + 1), rowsFromStart = Math.max(0, completedRow - startRow + 1);
                long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                if (rowsFromStart > 0) s += " - " + eta((long) (elapsed * (double) (sweep.rowCount() - rowsDone) / rowsFromStart)) + " left";
            }
            return downloads.isEmpty() ? s : s + " - downloading " + downloads;
        }
        if (!downloads.isEmpty()) return "downloading " + downloads;
        WorldModel m = OrbisMod.model();
        int preparing = m != null && m.regions() != null ? m.regions().preparing() : 0;
        if (preparing > 0) return "preparing map data (" + preparing + (preparing == 1 ? " region)" : " regions)");
        String why = waiting;
        if (why == null) return done + failed == 0 ? "starting" : "generating";
        return switch (why) {
            case "building terrain ahead" -> {
                FastPregen f = fast;
                yield f == null ? "building terrain" : String.format(Locale.ROOT, "building terrain (%,d chunks ready)", f.builtAhead());
            }
            case "waiting for the disk" -> "writing chunks to disk";
            case "waiting for unloads" -> "unloading finished chunks";
            case "waiting for memory" -> "freeing memory";
            default -> why;
        };
    }

    /** The data being downloaded right now, by kind: "map data (3), building heights (12)". */
    private static String downloadsNow() {
        java.util.Map<String, Integer> byKind = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Integer> e : com.berg.orbis.net.OrbisHttp.activeRequests().entrySet()) {
            String kind = dataKind(e.getKey());
            if (kind != null) byKind.merge(kind, e.getValue(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byKind.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(')');
        });
        return sb.toString();
    }

    /** What a host's data is, for the bar; null for requests that are not generation (weather, time zone). */
    private static String dataKind(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.contains("met.no") || h.contains("open-meteo")) return null;
        if (h.contains("mapterhorn")) return "terrain";
        if (h.contains("opengeohub")) return "bare-earth terrain";
        if (h.contains("openwaters")) return "sea floor";
        if (h.contains("overpass") || h.contains("kumi.systems") || h.contains("private.coffee")) return "map data";
        if (h.contains("geofabrik")) return "map file";
        if (h.contains("vegvesen")) return "road widths";
        if (h.contains("worldcover")) return "land cover";
        if (h.contains("macrostrat") || h.contains("ngu.no")) return "rock types";
        if (h.contains("geonorge") || h.contains("ahn") || h.contains("dsm") || h.contains("lidar") || h.contains("hoyde")) return "building heights";
        if (h.contains("arcgis") || h.contains("imagery") || h.contains("orto") || h.contains("photo") || h.contains("wms")) return "aerial photos";
        return "other data";
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
            long sea = skippedSea.get() + skippedExisting.get(); // counted as done: nothing to generate there
            long remaining = rate > 0 ? (long) ((chunks.length - finished - sea) / rate) : -1;
            return String.format(Locale.ROOT, "  %,d / %,d chunks%s%s (%.0f%%), %.1f chunks/s, ETA %s\n%s",
                    finished, chunks.length, failedNote, sea > 0 ? String.format(Locale.ROOT, ", %,d open-sea skipped or already there", sea) : "",
                    100.0 * (finished + sea) / Math.max(1, chunks.length), rate, eta(remaining), load);
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
            long sea = skippedSea.get() + skippedExisting.get();
            pct = String.format(Locale.ROOT, "%.0f%%", 100.0 * (total + sea) / Math.max(1, chunks.length));
            where = String.format(Locale.ROOT, "%,d of %,d chunks", total + sea, chunks.length);
            remaining = eta(avg > 0 ? (long) ((chunks.length - total - sea) / avg) : -1);
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
        if (skippedExisting.get() > 0) msg.append(line(String.format(Locale.ROOT, " - %,d already there", skippedExisting.get()), ChatFormatting.GRAY));
        if (failed > 0) msg.append(line(String.format(Locale.ROOT, " - %,d FAILED", failed), ChatFormatting.RED));
        msg.append(line(String.format(Locale.ROOT, "\n  loaded %,d - disk queue %s - heap %.1f/%.1f GB",
                level.getChunkSource().getLoadedChunksCount(), backlog < 0 ? "?" : String.format(Locale.ROOT, "%,d", backlog), usedMb / 1024.0, maxMb / 1024.0), ChatFormatting.GRAY));
        FastPregen f = fast;
        if (f != null) msg.append(line("\n  " + f.summary(), ChatFormatting.GRAY));
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
