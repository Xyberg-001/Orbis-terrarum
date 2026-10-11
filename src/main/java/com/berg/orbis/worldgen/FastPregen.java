package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.mc.Mc;
import com.berg.orbis.mixin.ChunkMapAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fast pre-generation: Orbis builds the terrain of a sweep's chunks itself, on half the cores and outside
 * Minecraft's chunk system (biomes, vanilla structure starts and references, ground, water, roads, buildings,
 * caves), and hands each chunk to Minecraft in memory when the sweep asks for it (mixin ChunkMapMixin);
 * Minecraft then only adds decoration (Orbis's furniture, signs, trees and residents, vanilla ores and features,
 * structure pieces), light and animals.
 *
 * <p>Minecraft's chunk system was the limit, not the work: in an experiment on Stord the same terrain step ran at
 * 155 chunks a second outside it, Minecraft finished such chunks at 88 a second, and a sweep that went through the
 * chunk system for everything managed 23 to 30. A first version saved the built chunks to disk for Minecraft to
 * read back; every chunk then went through the game's single save thread twice, and the sweep ran at 16 a second.
 *
 * <p>Safety: a built chunk is only handed over when the chunk is not on disk (the disk always wins, so player builds
 * and earlier generation are never replaced), and the sweep asks for a chunk only once every chunk of this sweep
 * within {@link #RING} of it is done here, so Minecraft does not generate one of them itself in the meantime.
 */
public final class FastPregen {

    /** Visits the sweep's chunks (see PregenTask.walkArea for the order). */
    public interface Walk {
        void run(Visitor visitor) throws InterruptedException;
    }

    public interface Visitor {
        void visit(int cx, int cz) throws InterruptedException;
    }

    /** True for chunks the sweep covers (the ring check skips the others). */
    public interface Planned {
        boolean test(int cx, int cz);
    }

    /**
     * Minecraft's generation pyramid: to finish a chunk it loads every chunk within 11 of it at least to structure
     * starts (terrain within 2, biomes within 3). One of them not built here yet became a stub that this then had
     * to leave to the game's own, slow path: a quarter of a fresh Stord sweep, with a ring of 3.
     */
    public static final int RING = 11;
    /** Vanilla's structure-reference reach: a chunk lists the starts of structures within 8 chunks that reach it. */
    private static final int REFERENCE_REACH = 8;
    private static final int MAX_STARTS_CACHED = 60_000;
    /** Built chunks waiting for Minecraft (about a hundred KB each); the walk waits while this many are. */
    private static final int MAX_WAITING = 3000;
    /**
     * Minecraft's structure templates cache their blocks in plain hash maps, and several threads laying out the same
     * village at once can break that (ConcurrentModificationException), as with vanilla's own threads. Worked out one
     * at a time, structure starts were most of this class's time and held the sweep up; now a chunk that meets
     * the error tries again.
     */
    private static final int STRUCTURE_TRIES = 6;
    /**
     * The first structure starts of a sweep are worked out one at a time while those caches fill (ten chunks of a
     * Stord sweep still failed in its first fifteen seconds); after these, in parallel.
     */
    private static final int WARM_UP_STARTS = 300;
    private final AtomicInteger startsComputed = new AtomicInteger();
    private final Object warmUp = new Object();
    /** Starts being worked out right now: another thread wanting the same chunk's waits for them instead. */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Map<Structure, StructureStart>>> computing =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile FastPregen active;

    private final ServerLevel level;
    private final ChunkMap map;
    private final RealWorldChunkGenerator generator;
    private final RandomState random;
    private final long seed;
    private final boolean structures;
    private final PalettedContainerFactory factory;
    private final Path regionFolder;
    private final Planned planned;
    private final int threads;
    private final LongOpenHashSet done = new LongOpenHashSet();
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private final Long2ObjectLinkedOpenHashMap<ProtoChunk> waiting = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BitSet> onDisk = new Long2ObjectOpenHashMap<>();
    private final ExecutorService pool;
    private final Semaphore queued;
    private final Thread walker;
    private volatile boolean stopped, walked;
    private final AtomicLong built = new AtomicLong(), handed = new AtomicLong(), existing = new AtomicLong(),
            dropped = new AtomicLong(), failed = new AtomicLong();
    private final AtomicInteger errorsLogged = new AtomicInteger();
    private final Map<Long, Map<Structure, StructureStart>> starts = new LinkedHashMap<>(4096, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<Structure, StructureStart>> eldest) {
            return size() > MAX_STARTS_CACHED;
        }
    };

    private FastPregen(ServerLevel level, RealWorldChunkGenerator generator, Walk walk, Planned planned) {
        this.level = level;
        this.map = level.getChunkSource().chunkMap;
        this.generator = generator;
        this.random = level.getChunkSource().randomState();
        this.seed = level.getSeed();
        this.structures = level.getServer().getWorldGenSettings().options().generateStructures();
        this.factory = PalettedContainerFactory.create(level.registryAccess());
        this.regionFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        this.planned = planned;
        int set = OrbisMod.config().fastPregenThreads;
        this.threads = set > 0 ? Math.min(set, Runtime.getRuntime().availableProcessors()) : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Orbis-Fast-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.queued = new Semaphore(threads * 4);
        this.walker = new Thread(() -> {
            try {
                walk.run((cx, cz) -> {
                    if (stopped) throw new InterruptedException();
                    long p = ChunkPos.pack(cx, cz);
                    synchronized (visited) {
                        if (!visited.add(p)) return; // the walk visits a few rows twice (see PregenTask.walkArea)
                    }
                    waitForRoom();
                    queued.acquire();
                    pool.execute(() -> {
                        try {
                            build(cx, cz);
                        } finally {
                            synchronized (done) {
                                done.add(p);
                            }
                            queued.release();
                        }
                    });
                });
            } catch (InterruptedException ignored) {
                // stopped
            } catch (RuntimeException e) {
                System.err.println("[orbis] Fast pre-generation stopped building ahead: " + e);
                e.printStackTrace();
            } finally {
                walked = true;
            }
        }, "Orbis-Fast-Walk");
        walker.setDaemon(true);
    }

    public static FastPregen start(ServerLevel level, RealWorldChunkGenerator generator, Walk walk, Planned planned) {
        FastPregen f = new FastPregen(level, generator, walk, planned);
        active = f;
        f.walker.start();
        System.out.println("[orbis] Fast pre-generation: building terrain ahead of the sweep on " + f.threads + " threads");
        return f;
    }

    public void stop() {
        stopped = true;
        if (active == this) active = null;
        walker.interrupt();
        pool.shutdownNow();
        synchronized (waiting) {
            waiting.clear(); // never on disk: the game generates these itself when they are needed
        }
    }

    /**
     * The chunk built here for this position, taken out of the waiting chunks (ChunkMapMixin asks while
     * Minecraft loads a chunk that is not on disk), or null.
     */
    public static ProtoChunk claim(ServerLevel level, ChunkPos pos) {
        FastPregen f = active;
        if (f == null || f.level != level) return null;
        ProtoChunk chunk;
        synchronized (f.waiting) {
            chunk = f.waiting.remove(pos.pack());
        }
        if (chunk != null) f.handed.incrementAndGet();
        return chunk;
    }

    /**
     * True once every chunk of the sweep within {@link #RING} of this one is done here (built, or left to the game),
     * so the sweep may ask Minecraft for it.
     */
    public boolean ready(int cx, int cz) {
        if (stopped) return true;
        boolean idle = walked && queued.availablePermits() == threads * 4;
        for (int dz = -RING; dz <= RING; dz++) {
            for (int dx = -RING; dx <= RING; dx++) {
                long p = ChunkPos.pack(cx + dx, cz + dz);
                boolean isDone;
                synchronized (done) {
                    isDone = done.contains(p);
                }
                if (isDone || idle || !planned.test(cx + dx, cz + dz)) continue;
                return false;
            }
        }
        return true;
    }

    /** "terrain built ahead 12,345 - 67 existing kept" for the progress lines. */
    /** Chunks built ahead of the sweep so far. */
    public long builtAhead() {
        return built.get();
    }

    public String summary() {
        String s = String.format(java.util.Locale.ROOT, "terrain built ahead %,d", built.get());
        if (existing.get() > 0) s += String.format(java.util.Locale.ROOT, " - %,d existing kept", existing.get());
        if (dropped.get() > 0) s += String.format(java.util.Locale.ROOT, " - %,d not taken", dropped.get());
        if (failed.get() > 0) s += String.format(java.util.Locale.ROOT, " - %,d left to the game after errors", failed.get());
        return s;
    }

    /**
     * Waits while too many built chunks are waiting for Minecraft. One that waited for half a minute is dropped
     * (Minecraft loaded that chunk some other way, or the sweep will not ask for it): the game generates it itself
     * should it be needed after all.
     */
    private void waitForRoom() throws InterruptedException {
        long since = System.currentTimeMillis();
        while (!stopped) {
            synchronized (waiting) {
                if (waiting.size() < MAX_WAITING) return;
                if (System.currentTimeMillis() - since > 30_000) {
                    waiting.removeFirst();
                    dropped.incrementAndGet();
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    private void build(int cx, int cz) {
        if (stopped || OrbisMod.stopping()) return;
        ChunkPos pos = new ChunkPos(cx, cz);
        try {
            if (HardLimit.blocks(cx, cz)) return;
            if (onDisk(cx, cz) || loaded(pos)) {
                existing.incrementAndGet();
                return;
            }
            WorldModel model = OrbisMod.model();
            int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
            if (model == null || !model.terrainReady(minX, minZ)) return; // no terrain tiles yet: the game's own path waits for them
            RegionRaster raster = model.regions() == null ? null : model.regions().futureForBlock(minX, minZ).join();
            ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, factory, null);
            if (structures) {
                // Vanilla's order: structure starts, structure references, biomes, terrain.
                chunk.setAllStarts(startsFor(cx, cz, chunk));
                level.onStructureStartsAvailable(chunk);
                addReferences(chunk, cx, cz);
            }
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            generator.buildDirect(chunk, raster, seed);
            Mc.afterTerrain(chunk);
            chunk.setPersistedStatus(Mc.TERRAIN_DONE);
            if (stopped || loaded(pos)) return;
            synchronized (waiting) {
                waiting.put(pos.pack(), chunk);
            }
            built.incrementAndGet();
        } catch (Throwable e) {
            failed.incrementAndGet();
            if (errorsLogged.incrementAndGet() <= 5) {
                System.err.println("[orbis] Fast pre-generation could not build chunk " + pos + " (the game generates it instead): " + e);
                e.printStackTrace();
            }
        }
    }

    /** Loaded, or on its way out of memory. */
    private boolean loaded(ChunkPos pos) {
        long p = pos.pack();
        ChunkMapAccessor a = (ChunkMapAccessor) map;
        if (a.orbisterrarum$visibleChunkMap().containsKey(p)) return true;
        try {
            return a.orbisterrarum$pendingUnloads().containsKey(p);
        } catch (RuntimeException e) {
            return true; // read while the server thread changed it: assume the worst
        }
    }

    /**
     * Whether the region file lists the chunk, from its header read once (saves not to build chunks that exist; the
     * hand-over checks the disk again through the game, so a chunk saved since is still safe).
     */
    private boolean onDisk(int cx, int cz) {
        long key = ChunkPos.pack(cx >> 5, cz >> 5);
        BitSet present;
        synchronized (onDisk) {
            present = onDisk.get(key);
            if (present == null) {
                present = new BitSet(1024);
                Path file = regionFolder.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
                if (Files.isRegularFile(file)) {
                    try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
                        for (int i = 0; i < 1024 && in.getFilePointer() + 4 <= in.length(); i++) if (in.readInt() != 0) present.set(i);
                    } catch (IOException e) {
                        present.set(0, 1024); // unreadable: treat every chunk as there, leave them all to the game
                    }
                }
                onDisk.put(key, present);
            }
        }
        return present.get((cx & 31) + (cz & 31) * 32);
    }

    /**
     * The structure starts of a chunk (Orbis's createStructures: vanilla's starts, then its own filters), computed
     * once and kept: every chunk's references read the starts of the 17 x 17 chunks around it.
     */
    private Map<Structure, StructureStart> startsFor(int cx, int cz, ProtoChunk into) {
        long key = ChunkPos.pack(cx, cz);
        Map<Structure, StructureStart> known;
        synchronized (starts) {
            known = starts.get(key);
        }
        if (known != null) return known;
        java.util.concurrent.CompletableFuture<Map<Structure, StructureStart>> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Map<Structure, StructureStart>> other = computing.putIfAbsent(key, mine);
        if (other != null) return other.join();
        try {
            ProtoChunk chunk = into != null ? into : new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, level, factory, null);
            if (startsComputed.incrementAndGet() <= WARM_UP_STARTS) {
                synchronized (warmUp) {
                    createStructuresRetrying(chunk);
                }
            } else {
                createStructuresRetrying(chunk);
            }
            Map<Structure, StructureStart> result = new HashMap<>();
            chunk.getAllStarts().forEach((structure, start) -> {
                if (start != null) result.put(structure, start); // saving a chunk cannot take an empty entry
            });
            synchronized (starts) {
                starts.put(key, result);
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            computing.remove(key, mine);
        }
    }

    private void createStructuresRetrying(ProtoChunk chunk) {
        for (int attempt = 1; ; attempt++) {
            try {
                createStructures(chunk);
                return;
            } catch (java.util.ConcurrentModificationException e) {
                if (attempt >= STRUCTURE_TRIES) throw e;
                chunk.setAllStarts(Map.of());
                try {
                    Thread.sleep(5L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void createStructures(ProtoChunk chunk) {
        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(), level.structureManager(), chunk,
                Mc.structureTemplates(level.getServer()), level.dimension());
    }

    /** Vanilla's createReferences, from the kept starts instead of a world-generation region. */
    private void addReferences(ProtoChunk chunk, int cx, int cz) {
        WorldModel model = OrbisMod.model();
        if (model != null && !model.cfg().vanillaStructures) return;
        int minX = cx << 4, minZ = cz << 4, maxX = minX + 15, maxZ = minZ + 15;
        for (int dz = -REFERENCE_REACH; dz <= REFERENCE_REACH; dz++) {
            for (int dx = -REFERENCE_REACH; dx <= REFERENCE_REACH; dx++) {
                Map<Structure, StructureStart> around = dx == 0 && dz == 0 ? chunk.getAllStarts() : startsFor(cx + dx, cz + dz, null);
                for (Map.Entry<Structure, StructureStart> e : around.entrySet()) {
                    StructureStart start = e.getValue();
                    if (start != null && start.isValid() && start.getBoundingBox().intersects(minX, minZ, maxX, maxZ)) {
                        chunk.addReferenceForStructure(e.getKey(), ChunkPos.pack(cx + dx, cz + dz));
                    }
                }
            }
        }
    }
}
