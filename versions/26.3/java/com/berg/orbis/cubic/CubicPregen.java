package com.berg.orbis.cubic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.worldgen.AreaSweep;
import com.berg.orbis.worldgen.PregenTask;
import com.berg.orbis.worldgen.WorldModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.opencubicchunks.cubicchunks.api.CubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.CubeTerrain;
import io.github.opencubicchunks.cubicchunks.api.CubicApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Pre-generation of a cubic world: the same sweeps (radius, place outline, drawn selection, the automatic one) over cube columns instead of
 * chunks. A cubic world reaches far up and down, and generating every cube of a column (some 125 across Y -2000..2000) would be almost all
 * sky and deep rock that is made anyway when someone goes there; each column gets the band of cubes around its ground instead: from
 * {@link #BELOW} blocks under its lowest ground to {@link #ABOVE} above its highest ground or water, as the cube generator's surface
 * sampled across the column gives them.
 * <p>
 * The area is walked in tiles of 8 x 8 cube columns (16 x 16 chunks, as the chunk sweeps), tile rows north to south, each tile row
 * alternately west to east and back. Each cube is loaded as full with the sweep's ticket (Cubic Chunks generates it, and the cubes around
 * it as far as it needs), held while the next cubes are made (they share their neighbours), then let go: it unloads and is saved.
 * Progress (the columns done in walking order) is kept beside the world every 10 s, so the same sweep started again resumes.
 */
public final class CubicPregen implements PregenTask.CubicSweeper {
    private static final CubicPregen INSTANCE = new CubicPregen();

    /** Blocks generated under a column's lowest ground and over its highest ground or water. */
    private static final int BELOW = 64;
    private static final int ABOVE = 64;
    /** Cube columns across a tile. */
    private static final int TILE = 8;
    /** Cubes asked for and not full yet. */
    private static final int MAX_IN_FLIGHT = 192;
    /** Full cubes kept held after they were made (their neighbours' generation reuses them), oldest let go first. */
    private static final int KEEP_HELD = 768;
    /** Writes queued for the disk before the sweep waits. */
    private static final int MAX_PENDING_WRITES = 2000;
    /** Holders (cubes and columns) loaded beyond the start before the sweep waits for unloads. */
    private static final int MAX_LOADED = 60_000;
    /** Old-generation heap still in use after the last collection, as a fraction of the maximum: above this, wait (as the chunk sweeps). */
    private static final double MAX_HEAP_FRACTION = 0.75;
    private static final java.lang.management.MemoryPoolMXBean OLD_GEN = findOldGen();

    private volatile Run run;

    private CubicPregen() {
    }

    public static void register() {
        PregenTask.cubicSweeper = INSTANCE;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Run r = INSTANCE.run;
            if (r != null && r.level.getServer() == server) r.tick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Run r = INSTANCE.run;
            if (r != null) INSTANCE.end(r, "the server stopped", true);
        });
    }

    @Override
    public boolean cubic(ServerLevel level) {
        return CubicApi.isCubic(level);
    }

    @Override
    public boolean running() {
        return this.run != null;
    }

    @Override
    public synchronized Component start(ServerLevel level, String label, AreaSweep area, boolean skipSea, String key) {
        if (this.run != null) return Component.literal("A pre-generation is already running (" + this.run.label + "); /orbis pregen stop first.");
        CubeGenerator generator = CubicApi.cubeGenerator(level);
        if (generator == null) return Component.literal("This cubic world has no Orbis cube generator to pre-generate with.");
        Run r = new Run(level, label, area, skipSea, key, generator);
        if (r.total == 0) return Component.literal("The area has no cube columns.");
        this.run = r;
        r.start();
        return Component.literal(String.format(Locale.ROOT,
                "Pre-generating %s (cubic world): %,d cube columns, each from %d blocks under its ground to %d over it.%s Progress every 30 s; "
                        + "/orbis pregen stop to end (it resumes later).",
                label, r.total, BELOW, ABOVE, r.resumedFrom > 0 ? String.format(Locale.ROOT, " Resuming after %,d columns done before.", r.resumedFrom) : ""));
    }

    @Override
    public synchronized Component stop() {
        Run r = this.run;
        if (r == null) return Component.literal("No pre-generation is running.");
        this.end(r, "stopped", true);
        return Component.literal("Stopped " + r.label + "\n" + r.progress() + "\n  Start the same sweep again to resume.");
    }

    @Override
    public PregenTask.Snapshot snapshot() {
        Run r = this.run;
        if (r == null) return null;
        return new PregenTask.Snapshot(r.label, r.total == 0 ? 1f : Math.min(1f, (float) r.donePrefix / r.total),
                String.format(Locale.ROOT, "%,d of %,d cube columns, %,d cubes, %.0f cubes/s", r.donePrefix, r.total, r.cubesDone.get(), r.rate),
                r.waiting);
    }

    @Override
    public Component status() {
        Run r = this.run;
        return Component.literal(r == null ? "No pre-generation is running." : r.progress());
    }

    private synchronized void end(Run r, String why, boolean keepProgress) {
        if (this.run != r) return;
        this.run = null;
        r.stopRequested = true;
        if (r.producer != null) r.producer.interrupt();
        if (keepProgress) r.saveProgress();
        else r.deleteProgress();
        Runnable release = r::releaseAll;
        if (r.level.getServer().isSameThread()) release.run();
        else r.level.getServer().execute(release);
        r.bar.removeAllPlayers();
        System.out.println("[orbis] Cubic pre-generation of " + r.label + " " + why + ". " + r.progress());
    }

    /** One sweep. */
    private final class Run {
        final ServerLevel level;
        final String label;
        final AreaSweep area;
        final boolean skipSea;
        final CubeGenerator generator;
        final WorldModel model = OrbisMod.model();
        final Path progressFile;
        final int minCube;
        final int maxCube;
        /** Cube columns in the area (counted once at the start). */
        final int total;
        final int resumedFrom;
        final ServerBossEvent bar;
        final long started = System.currentTimeMillis();
        final int loadedAtStart;

        volatile boolean stopRequested;
        volatile String waiting;
        volatile boolean producerDone;
        Thread producer;

        final Semaphore slots = new Semaphore(MAX_IN_FLIGHT);
        final AtomicInteger inFlight = new AtomicInteger();
        final ConcurrentLinkedQueue<long[]> finished = new ConcurrentLinkedQueue<>(); // {x, y, z, column index, ok}
        /** Cubes of each column (by walking index) not full yet; a column with none planned is done at once. */
        final ConcurrentHashMap<Integer, AtomicInteger> remaining = new ConcurrentHashMap<>();
        final AtomicLong cubesDone = new AtomicLong();
        final AtomicLong cubesFailed = new AtomicLong();
        final AtomicInteger columnsSkipped = new AtomicInteger();
        /** Columns walked (all their cubes asked for), and done in walking order with none missing before them. */
        final AtomicInteger columnsWalked = new AtomicInteger();
        int donePrefix;
        final java.util.BitSet doneColumns = new java.util.BitSet();
        /** Full cubes still held (server thread). */
        final ArrayDeque<long[]> held = new ArrayDeque<>();
        /** Cubes asked for and not finished (server thread), to let go if the sweep stops. */
        final java.util.HashSet<Long> asked = new java.util.HashSet<>();

        volatile long lastGcAsked;
        long lastLog;
        long lastChat;
        long lastSave;
        long cubesAtLastLog;
        long lastLogTime;
        double rate;

        Run(ServerLevel level, String label, AreaSweep area, boolean skipSea, String key, CubeGenerator generator) {
            this.level = level;
            this.label = label;
            this.area = area;
            this.skipSea = skipSea;
            this.generator = generator;
            this.minCube = Math.floorDiv(CubicApi.minY(level), CubeTerrain.SIZE);
            this.maxCube = Math.floorDiv(CubicApi.maxY(level), CubeTerrain.SIZE);
            this.progressFile = key == null ? null : level.getServer().getWorldPath(LevelResource.ROOT).resolve("orbis-pregen").resolve(key);
            int[] count = {0};
            walk((index, x, z) -> count[0]++);
            this.total = count[0];
            this.resumedFrom = readProgress();
            this.donePrefix = this.resumedFrom;
            this.bar = new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal("Pre-generating " + label), BossEvent.BossBarColor.GREEN,
                    BossEvent.BossBarOverlay.PROGRESS);
            this.loadedAtStart = level.getChunkSource().getLoadedChunksCount();
        }

        void start() {
            this.lastLog = this.lastChat = this.lastSave = this.lastLogTime = System.currentTimeMillis();
            for (ServerPlayer p : this.level.getServer().getPlayerList().getPlayers()) this.bar.addPlayer(p);
            this.producer = new Thread(this::produce, "Orbis-Cubic-Pregen");
            this.producer.setDaemon(true);
            this.producer.start();
        }

        // ---- walking -----------------------------------------------------------------------------------------

        interface ColumnVisitor {
            void visit(int index, int cubeX, int cubeZ) throws InterruptedException;
        }

        /** Visits the area's cube columns in walking order (tiles of TILE x TILE, tile rows north to south, alternately west-east). */
        void walk(ColumnVisitor visitor) {
            int firstRow = Math.floorDiv(this.area.firstRow(), 2), lastRow = Math.floorDiv(this.area.lastRow(), 2);
            int index = 0;
            int band = 0;
            try {
                for (int bandRow = firstRow; bandRow <= lastRow; bandRow += TILE, band++) {
                    int bandEnd = Math.min(lastRow, bandRow + TILE - 1);
                    List<List<int[]>> rows = new ArrayList<>();
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                    for (int r = bandRow; r <= bandEnd; r++) {
                        List<int[]> ranges = cubeRanges(r);
                        rows.add(ranges);
                        for (int[] range : ranges) {
                            minX = Math.min(minX, range[0]);
                            maxX = Math.max(maxX, range[1]);
                        }
                    }
                    if (minX > maxX) continue;
                    int tiles = Math.floorDiv(maxX - minX, TILE) + 1;
                    for (int t = 0; t < tiles; t++) {
                        int tile = (band & 1) == 0 ? t : tiles - 1 - t;
                        int x0 = minX + tile * TILE, x1 = x0 + TILE - 1;
                        for (int r = bandRow; r <= bandEnd; r++) {
                            for (int[] range : rows.get(r - bandRow)) {
                                for (int x = Math.max(x0, range[0]); x <= Math.min(x1, range[1]); x++) {
                                    visitor.visit(index++, x, r);
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** The cube-X ranges of a cube row: the cube columns over any chunk of the area in the row's two chunk rows. */
        List<int[]> cubeRanges(int cubeRow) {
            List<int[]> ranges = new ArrayList<>();
            for (int chunkRow = cubeRow * 2; chunkRow <= cubeRow * 2 + 1; chunkRow++) {
                for (int[] run : this.area.rowRanges(chunkRow)) ranges.add(new int[]{Math.floorDiv(run[0], 2), Math.floorDiv(run[1], 2)});
            }
            ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] r : ranges) {
                if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1] + 1) {
                    int[] last = merged.get(merged.size() - 1);
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    merged.add(new int[]{r[0], r[1]});
                }
            }
            return merged;
        }

        // ---- the producer (its own thread) -----------------------------------------------------------------------

        void produce() {
            try {
                walk((index, x, z) -> {
                    if (this.stopRequested) throw new InterruptedException();
                    if (index < this.resumedFrom) return;
                    int[] band = band(x, z);
                    if (band == null) {
                        this.columnsSkipped.incrementAndGet();
                        this.finished.add(new long[]{0, 0, 0, index, -1});
                    } else {
                        AtomicInteger left = new AtomicInteger(band[1] - band[0] + 1);
                        this.remaining.put(index, left);
                        for (int y = band[0]; y <= band[1]; y++) submit(index, x, y, z);
                    }
                    this.columnsWalked.incrementAndGet();
                });
            } catch (RuntimeException e) {
                System.err.println("[orbis] Cubic pre-generation stopped by an error: " + e);
                e.printStackTrace();
            } finally {
                this.producerDone = true;
            }
        }

        /** The cube-Y band of a column, or null to leave it out (beyond the world border or limit, or open sea when that is skipped). */
        int[] band(int cubeX, int cubeZ) {
            int size = CubeTerrain.SIZE;
            net.minecraft.world.level.border.WorldBorder border = this.level.getWorldBorder();
            if (!border.isWithinBounds(cubeX * size, cubeZ * size) || !border.isWithinBounds(cubeX * size + size - 1, cubeZ * size + size - 1)) {
                return null; // beyond the world border (in a cubic world, nearer than vanilla's)
            }
            if (this.skipSea && this.model != null) {
                boolean sea = true;
                for (int i = 0; i < 2 && sea; i++) {
                    for (int j = 0; j < 2 && sea; j++) sea = PregenTask.isOpenSea(this.model, cubeX * 2 + i, cubeZ * 2 + j);
                }
                if (sea) return null;
            }
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    int x = cubeX * size + 1 + i * (size - 2) / 2, z = cubeZ * size + 1 + j * (size - 2) / 2;
                    CubeGenerator.Surface s = this.generator.surface(x, z);
                    if (s == null) continue;
                    low = Math.min(low, s.surfaceY());
                    high = Math.max(high, Math.max(s.surfaceY(), s.waterTopY()));
                }
            }
            if (low > high) return null;
            int lo = Math.max(this.minCube, Math.floorDiv(low - BELOW, size));
            int hi = Math.min(this.maxCube, Math.floorDiv(high + ABOVE, size));
            return lo > hi ? null : new int[]{lo, hi};
        }

        void submit(int index, int x, int y, int z) throws InterruptedException {
            this.slots.acquire();
            String why;
            while (!this.stopRequested && (why = mustWait()) != null) {
                this.waiting = why;
                Thread.sleep(100);
            }
            this.waiting = null;
            if (this.stopRequested) {
                this.slots.release();
                throw new InterruptedException();
            }
            this.inFlight.incrementAndGet();
            this.level.getServer().execute(() -> {
                if (this.stopRequested) {
                    this.inFlight.decrementAndGet();
                    this.slots.release();
                    return;
                }
                this.asked.add(key(x, y, z));
                java.util.concurrent.CompletableFuture<Boolean> future;
                try {
                    future = CubicApi.loadCube(this.level, PregenTask.PREGEN_TICKET, x, y, z);
                } catch (RuntimeException e) {
                    future = java.util.concurrent.CompletableFuture.completedFuture(false);
                }
                future.whenComplete((ok, error) -> this.finished.add(new long[]{x, y, z, index, ok != null && ok && error == null ? 1 : 0}));
            });
        }

        String mustWait() {
            if (CubicApi.pendingCubeWrites(this.level) > MAX_PENDING_WRITES) return "waiting for the disk";
            if (this.level.getChunkSource().getLoadedChunksCount() > this.loadedAtStart + MAX_LOADED) return "waiting for unloads";
            if (oldGenAfterGc() > MAX_HEAP_FRACTION) {
                // what was in use at the last collection: with nothing in flight nothing allocates, and without a collection the reading
                // would never come down (the sweep stood still with the heap full of garbage)
                long now = System.currentTimeMillis();
                if (this.inFlight.get() == 0 && now - this.lastGcAsked > 10_000) {
                    this.lastGcAsked = now;
                    System.gc();
                }
                return "waiting for memory";
            }
            return null;
        }

        // ---- the server thread -----------------------------------------------------------------------------------

        void tick() {
            long[] f;
            while ((f = this.finished.poll()) != null) {
                int index = (int) f[3];
                if (f[4] == -1) { // a column left out
                    columnDone(index);
                    continue;
                }
                this.inFlight.decrementAndGet();
                this.slots.release();
                long k = key((int) f[0], (int) f[1], (int) f[2]);
                this.asked.remove(k);
                if (f[4] == 1) {
                    this.cubesDone.incrementAndGet();
                    this.held.addLast(new long[]{f[0], f[1], f[2]});
                } else {
                    this.cubesFailed.incrementAndGet();
                    CubicApi.releaseCube(this.level, PregenTask.PREGEN_TICKET, (int) f[0], (int) f[1], (int) f[2]);
                }
                AtomicInteger left = this.remaining.get(index);
                if (left != null && left.decrementAndGet() == 0) {
                    this.remaining.remove(index);
                    columnDone(index);
                }
            }
            while (this.held.size() > KEEP_HELD) {
                long[] c = this.held.pollFirst();
                CubicApi.releaseCube(this.level, PregenTask.PREGEN_TICKET, (int) c[0], (int) c[1], (int) c[2]);
            }
            for (ServerPlayer p : this.level.getServer().getPlayerList().getPlayers()) {
                if (!this.bar.getPlayers().contains(p)) this.bar.addPlayer(p);
            }
            long now = System.currentTimeMillis();
            this.bar.setProgress(this.total == 0 ? 1f : Math.min(1f, (float) this.donePrefix / this.total));
            this.bar.setName(Component.literal(String.format(Locale.ROOT, "Pre-generating %s: %,d / %,d columns, %.0f cubes/s%s", this.label,
                    this.donePrefix, this.total, this.rate, this.waiting != null ? " (" + this.waiting + ")" : "")));
            if (now - this.lastLogTime >= 10_000) {
                long done = this.cubesDone.get();
                this.rate = (done - this.cubesAtLastLog) * 1000.0 / (now - this.lastLogTime);
                this.cubesAtLastLog = done;
                this.lastLogTime = now;
            }
            if (now - this.lastSave >= 10_000) {
                this.lastSave = now;
                saveProgress();
            }
            if (now - this.lastLog >= 30_000) {
                this.lastLog = now;
                System.out.println("[orbis] Cubic pregen: " + progress());
            }
            if (now - this.lastChat >= 120_000) {
                this.lastChat = now;
                this.level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(progress()), false);
            }
            if (this.producerDone && this.inFlight.get() == 0 && this.finished.isEmpty()) {
                end(this, "finished", false);
                this.level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("Pre-generation of " + this.label + " finished. " + progress()), false);
            }
        }

        void columnDone(int index) {
            this.doneColumns.set(index);
            while (this.doneColumns.get(this.donePrefix)) this.donePrefix++;
        }

        void releaseAll() {
            for (long[] c : this.held) CubicApi.releaseCube(this.level, PregenTask.PREGEN_TICKET, (int) c[0], (int) c[1], (int) c[2]);
            this.held.clear();
            for (long k : this.asked) CubicApi.releaseCube(this.level, PregenTask.PREGEN_TICKET, unpackX(k), unpackY(k), unpackZ(k));
            this.asked.clear();
        }

        String progress() {
            long seconds = Math.max(1, (System.currentTimeMillis() - this.started) / 1000);
            return String.format(Locale.ROOT, "%s: %,d of %,d cube columns done (%.1f%%), %,d cubes made (%,d failed), %,d columns left out; "
                            + "%.0f cubes/s now, %.0f on average; %d in flight%s",
                    this.label, this.donePrefix, this.total, this.total == 0 ? 100.0 : 100.0 * this.donePrefix / this.total, this.cubesDone.get(),
                    this.cubesFailed.get(), this.columnsSkipped.get(), this.rate, this.cubesDone.get() / (double) seconds, this.inFlight.get(),
                    this.waiting != null ? ", " + this.waiting : "");
        }

        // ---- progress on disk ------------------------------------------------------------------------------------

        int readProgress() {
            if (this.progressFile == null || !Files.exists(this.progressFile)) return 0;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(this.progressFile)).getAsJsonObject();
                if (o.get("total").getAsInt() != this.total) return 0;
                return Math.max(0, Math.min(this.total, o.get("columnsDone").getAsInt()));
            } catch (IOException | RuntimeException e) {
                return 0;
            }
        }

        void saveProgress() {
            if (this.progressFile == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("label", this.label);
            o.addProperty("total", this.total);
            o.addProperty("columnsDone", this.donePrefix);
            try {
                Files.createDirectories(this.progressFile.getParent());
                Files.writeString(this.progressFile, o.toString());
            } catch (IOException e) {
                System.err.println("[orbis] Could not save cubic pre-generation progress: " + e);
            }
        }

        void deleteProgress() {
            if (this.progressFile == null) return;
            try {
                Files.deleteIfExists(this.progressFile);
            } catch (IOException ignored) {
            }
        }
    }

    private static java.lang.management.MemoryPoolMXBean findOldGen() {
        for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            String n = pool.getName();
            if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
                    && (n.contains("Old") || n.contains("Tenured"))) {
                return pool;
            }
        }
        return null;
    }

    /** Old-generation heap in use right after the last collection, as a fraction of the heap maximum. */
    private static double oldGenAfterGc() {
        if (OLD_GEN == null) return 0;
        java.lang.management.MemoryUsage u = OLD_GEN.getCollectionUsage();
        if (u == null) return 0;
        double max = u.getMax() > 0 ? u.getMax() : Runtime.getRuntime().maxMemory();
        return max > 0 ? u.getUsed() / max : 0;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    private static int unpackX(long k) {
        return (int) (k << 0 >> 42);
    }

    private static int unpackY(long k) {
        return (int) (k << 22 >> 44);
    }

    private static int unpackZ(long k) {
        return (int) (k << 42 >> 42);
    }
}
