package com.berg.orbis.compat.voxy;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.biome.RealWorldBiomeSource;
import com.berg.orbis.net.OrbisHttp;
import com.berg.orbis.worldgen.FarTerrain;
import com.berg.orbis.worldgen.PregenMap;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voxy's far view of land nobody has generated yet (prototype, singleplayer): the surface of each chunk around the
 * player, painted from the world's data ({@link FarTerrain}: the generator's own elevation, climate, snow and painter,
 * so it meets the real chunks) and handed to Voxy as ordinary chunk sections, rings outward from the player, nearest
 * first. Only the surface goes in: each column from just under its lowest neighbour up to its top, so slopes and cliffs
 * have no holes, which is one or two sections a chunk instead of a whole column. Farther out a column stands for 2, 4
 * or 8 blocks (Voxy draws those distances coarser anyway). Chunks the world has generated go in as saved, their surface
 * sections read from the region files: in singleplayer Voxy only knows the chunks the player has had loaded, so a
 * pre-generated city was empty sky past the view distance (Bergen 1:2, 6 Oct 2026). Past the world's border the
 * terrain alone stands as scenery. Voxy has no public API for this; its ingest call is used as its own importers use
 * it, and if a Voxy update changes it the far view simply stops with a log line.
 */
final class VoxyFarView {

    private static volatile Job job;
    /**
     * What a stopped job had fed, and in which level: saving the Orbis settings rebuilds the world model, which stopped
     * the far view and started it over, feeding the whole city to Voxy again (Bergen 1:2, 6 Oct 2026).
     */
    private static Done keptDone;
    private static net.minecraft.client.multiplayer.ClientLevel keptLevel;
    private static int ticks;

    private VoxyFarView() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(VoxyFarView::tick);
        System.out.println("[orbis] Voxy found: Orbis worlds get a far view of land not generated yet (singleplayer)");
    }

    private static void tick(Minecraft mc) {
        if (++ticks % 40 != 0) return;
        Job j = job;
        WorldModel model = OrbisMod.model();
        var server = mc.getSingleplayerServer();
        boolean wanted = mc.level != null && mc.player != null && server != null && model != null && OrbisMod.config().voxyFarView
                && server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator
                && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
        if (!wanted) {
            if (j != null) {
                j.stop();
                keptDone = j.done;
                keptLevel = j.level;
                job = null;
            }
            if (mc.level == null) {
                keptDone = null; // left the world: a new visit starts over
                keptLevel = null;
            }
            hideBar(server);
            return;
        }
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        if (j != null && j.level == mc.level && Math.max(Math.abs(cx - j.cx), Math.abs(cz - j.cz)) < 64) {
            showBar(server, mc.player.getUUID(), j, model.cfg().metersPerBlock);
            return;
        }
        Done done = j != null && j.level == mc.level ? j.done : keptDone != null && keptLevel == mc.level ? keptDone
                : Done.load(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        if (j != null) j.stop();
        try {
            job = new Job(mc, server.overworld(), model, cx, cz, done);
            job.start();
            readySince = 0;
        } catch (Throwable t) {
            System.err.println("[orbis] Voxy far view could not start: " + t);
            OrbisMod.config().voxyFarView = false;
        }
    }

    // ------------------------------------------------------------------ the bar

    /**
     * A bar at the top of the screen while the far view fills, like pre-generation's: how much is done, how far out
     * it has got (real kilometres) and how fast, yellow while it waits for Voxy, "ready" for a few seconds at the end.
     * A vanilla boss bar on the integrated server, for the player whose far view it is.
     */
    private static net.minecraft.server.level.ServerBossEvent bar;
    private static long readySince;

    private static void showBar(net.minecraft.server.MinecraftServer server, java.util.UUID player, Job j, double metersPerBlock) {
        long total = j.totalChunks;
        if (total <= 0) return;
        long covered = j.covered.get();
        long now = System.currentTimeMillis();
        if (j.barAt == 0) {
            j.barAt = now;
            j.barCovered = covered;
            j.barWaits = j.waits.get();
        } else if (now - j.barAt >= 2000) {
            double r = (covered - j.barCovered) * 1000.0 / (now - j.barAt);
            j.barRate = j.barRate <= 0 ? r : 0.5 * j.barRate + 0.5 * r;
            j.barWaiting = j.waits.get() - j.barWaits > (now - j.barAt) / 20 * THREADS_FOR_BAR / 2; // waited half the time
            j.barAt = now;
            j.barCovered = covered;
            j.barWaits = j.waits.get();
        }
        boolean finished = j.finished;
        if (finished && readySince == 0) readySince = now;
        if (finished && now - readySince > 10_000) {
            hideBar(server);
            return;
        }
        if (!finished) readySince = 0;
        float fraction = finished ? 1f : (float) Math.min(1.0, covered / (double) total);
        String text = finished
                ? String.format(java.util.Locale.ROOT, "Far view ready - %.1f km all round", j.radius * 16 * metersPerBlock / 1000)
                : String.format(java.util.Locale.ROOT, "Far view - %.0f%% - %.1f of %.1f km - %,.0f chunks/s%s", 100 * fraction,
                j.reachedBlocks * metersPerBlock / 1000, j.radius * 16 * metersPerBlock / 1000, Math.max(0, j.barRate),
                j.barWaiting ? " - waiting for Voxy" : "");
        net.minecraft.world.BossEvent.BossBarColor color = finished || !j.barWaiting
                ? net.minecraft.world.BossEvent.BossBarColor.GREEN : net.minecraft.world.BossEvent.BossBarColor.YELLOW;
        server.execute(() -> {
            if (bar == null) {
                bar = new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(), net.minecraft.network.chat.Component.literal("Far view"),
                        net.minecraft.world.BossEvent.BossBarColor.GREEN, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            }
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(player);
            if (p != null && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            bar.setName(net.minecraft.network.chat.Component.literal(text));
            bar.setProgress(Math.max(0f, Math.min(1f, fraction)));
            bar.setColor(color);
        });
    }

    private static void hideBar(net.minecraft.server.MinecraftServer server) {
        if (bar == null) return;
        net.minecraft.server.level.ServerBossEvent b = bar;
        bar = null;
        if (server != null) server.execute(b::removeAllPlayers);
        else b.removeAllPlayers();
    }

    private static final int THREADS_FOR_BAR = Math.max(2, Runtime.getRuntime().availableProcessors() * 2 / 3);

    /**
     * What the far view draws: raise it when that changes (the scenery past the border, how saved chunks are read), so
     * a world's saved progress is dropped and everything is fed to Voxy again, once.
     */
    static final int LOOKS_VERSION = 2; // 2: water fed down to its bed (it was see-through, the sky showing under it)
    /**
     * What the scenery past the map data looks like: raise it when only that changes, so only the chunks fed as
     * scenery are fed again (the saved chunks, most of a city, are kept).
     */
    static final int SCENERY_VERSION = 4; // 2: the sea 32 blocks deep, like the floored water of saved chunks; 3: snow layers lit; 4: lit air over section tops

    /**
     * The chunks already fed, one bit each in a 32 x 32 block per region (128 bytes a region): as a set of boxed
     * chunk keys the 4.2 million chunks of a 1,024-chunk radius took some 300 MB of the game's memory. A second set
     * marks the chunks fed as scenery (not generated then): once the world has generated one, it is fed again.
     * Kept in the world folder, so a second visit only feeds what is new: the first run with the saved chunks took
     * 12 minutes to get through Bergen 1:2, and every visit (and every save of the settings) started over.
     */
    static final class Done {
        private final Map<Long, long[]> regions = new ConcurrentHashMap<>(), scenery = new ConcurrentHashMap<>();
        /** Chunks written only at a coarse Voxy level (index 1-4), not in full blocks: fed again when they come closer. */
        @SuppressWarnings("unchecked")
        private final Map<Long, long[]>[] coarse = new Map[]{null, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>()};
        private final Path file;
        private final long voxyStamp;
        private volatile boolean dirty;

        private Done(Path file, long voxyStamp) {
            this.file = file;
            this.voxyStamp = voxyStamp;
        }

        boolean contains(int x, int z) {
            return has(regions, x, z);
        }

        boolean isScenery(int x, int z) {
            return has(scenery, x, z);
        }

        /** The Voxy level a fed chunk was written at: 0 in full blocks, 1-4 coarse only. */
        int fedLevel(int x, int z) {
            for (int l = 1; l <= 4; l++) if (has(coarse[l], x, z)) return l;
            return 0;
        }

        void add(int x, int z) {
            put(regions, x, z, true);
            put(scenery, x, z, false);
            clearCoarse(x, z);
            dirty = true;
        }

        void addScenery(int x, int z) {
            put(regions, x, z, true);
            put(scenery, x, z, true);
            clearCoarse(x, z);
            dirty = true;
        }

        /** Scenery written at a coarse level only. */
        void addCoarse(int x, int z, int level) {
            put(regions, x, z, true);
            put(scenery, x, z, true);
            for (int l = 1; l <= 4; l++) put(coarse[l], x, z, l == level);
            dirty = true;
        }

        private void clearCoarse(int x, int z) {
            for (int l = 1; l <= 4; l++) put(coarse[l], x, z, false);
        }

        private static boolean has(Map<Long, long[]> m, int x, int z) {
            long[] b = m.get(region(x, z));
            int i = bit(x, z);
            return b != null && (b[i >> 6] & (1L << (i & 63))) != 0;
        }

        private static void put(Map<Long, long[]> m, int x, int z, boolean on) {
            long[] b = on ? m.computeIfAbsent(region(x, z), k -> new long[16]) : m.get(region(x, z));
            if (b == null) return;
            int i = bit(x, z);
            synchronized (b) {
                if (on) b[i >> 6] |= 1L << (i & 63);
                else b[i >> 6] &= ~(1L << (i & 63));
            }
        }

        /** The world's saved progress, or a fresh start when there is none, it is from older looks, or Voxy's store is new. */
        static Done load(Path worldDir) {
            Path file = worldDir.resolve("orbis-far-view").resolve("voxy-fed.bin");
            long stamp = voxyStamp(worldDir);
            Done d = new Done(file, stamp);
            if (stamp == 0 || !Files.exists(file)) return d;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.util.zip.GZIPInputStream(Files.newInputStream(file))))) {
                int magic = in.readInt();
                if (magic != 0x4F465631 && magic != 0x4F465632 && magic != 0x4F465633) return d;
                if (in.readInt() != LOOKS_VERSION) return d;
                int sceneryVersion = magic != 0x4F465631 ? in.readInt() : 1; // the first files had no scenery version
                if (in.readLong() != stamp) return d;
                List<Map<Long, long[]>> maps = magic == 0x4F465633
                        ? List.of(d.regions, d.scenery, d.coarse[1], d.coarse[2], d.coarse[3], d.coarse[4])
                        : List.of(d.regions, d.scenery); // before the coarse levels (7 Oct 2026)
                for (Map<Long, long[]> m : maps) {
                    int n = in.readInt();
                    for (int k = 0; k < n; k++) {
                        long key = in.readLong();
                        long[] b = new long[16];
                        for (int i = 0; i < 16; i++) b[i] = in.readLong();
                        m.put(key, b);
                    }
                }
                if (sceneryVersion != SCENERY_VERSION) {
                    // The scenery looks different now: its chunks go in again, the saved ones stay fed.
                    for (Map.Entry<Long, long[]> e : d.scenery.entrySet()) {
                        long[] fed = d.regions.get(e.getKey());
                        if (fed != null) for (int i = 0; i < 16; i++) fed[i] &= ~e.getValue()[i];
                    }
                    d.scenery.clear();
                    for (int l = 1; l <= 4; l++) d.coarse[l].clear();
                    d.dirty = true;
                    System.out.println("[orbis] Voxy far view: the scenery past the map data has changed and is drawn again");
                }
                System.out.println(String.format("[orbis] Voxy far view: %,d regions already fed in earlier visits", d.regions.size()));
            } catch (Exception e) {
                System.err.println("[orbis] Voxy far view: saved progress unreadable, starting over (" + e + ")");
                return new Done(file, stamp);
            }
            return d;
        }

        /** Writes the progress when it changed (a temporary file, then moved over the old one). */
        void save() {
            if (!dirty || voxyStamp == 0) return;
            dirty = false;
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                        new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp))))) {
                    out.writeInt(0x4F465633);
                    out.writeInt(LOOKS_VERSION);
                    out.writeInt(SCENERY_VERSION);
                    out.writeLong(voxyStamp);
                    for (Map<Long, long[]> m : List.of(regions, scenery, coarse[1], coarse[2], coarse[3], coarse[4])) {
                        List<Map.Entry<Long, long[]>> entries = new ArrayList<>(m.entrySet());
                        out.writeInt(entries.size());
                        for (Map.Entry<Long, long[]> e : entries) {
                            out.writeLong(e.getKey());
                            long[] b = e.getValue();
                            synchronized (b) {
                                for (long v : b) out.writeLong(v);
                            }
                        }
                    }
                }
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                dirty = true;
                System.err.println("[orbis] Voxy far view: could not save its progress: " + e);
            }
        }

        /** When Voxy's store for the world was made: a store deleted and made again has lost what was fed. */
        private static long voxyStamp(Path worldDir) {
            try {
                Path voxy = worldDir.resolve("voxy");
                if (!Files.isDirectory(voxy)) return 0;
                return Files.readAttributes(voxy, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
            } catch (Exception e) {
                return 0;
            }
        }

        private static long region(int x, int z) {
            return ((long) (x >> 5) << 32) | ((z >> 5) & 0xffffffffL);
        }

        private static int bit(int x, int z) {
            return (x & 31) | ((z & 31) << 5);
        }
    }

    /** One run of rings around a centre; a new one starts when the player has moved far from it. */
    private static final class Job implements Runnable {
        final net.minecraft.client.multiplayer.ClientLevel level;
        final int cx, cz;
        final Done done;
        private final ServerLevel server;
        private final WorldModel model;
        private final FarTerrain far;
        private final WorldIdentifier world;
        private final PalettedContainerFactory factory;
        private final Path regionDir;
        private final com.berg.orbis.map.RegionReader reader;
        private final com.mojang.serialization.Codec<net.minecraft.world.level.chunk.PalettedContainer<BlockState>> blockCodec;
        private final com.mojang.serialization.Codec<net.minecraft.world.level.chunk.PalettedContainerRO<Holder<Biome>>> biomeCodec;
        private final Map<Long, byte[]> regionState = new ConcurrentHashMap<>();
        private final int radius, minY, height;
        private volatile boolean stopped;
        private final AtomicLong fed = new AtomicLong(), saved = new AtomicLong(), sections = new AtomicLong(), waits = new AtomicLong();
        private long startedAt, startBytes, lastLog;
        /** For the bar: chunks the pieces cover in all, those done, how far out (blocks), finished. */
        volatile long totalChunks;
        final AtomicLong covered = new AtomicLong();
        volatile long reachedBlocks;
        volatile boolean finished;
        long barAt, barCovered, barWaits;
        double barRate;
        boolean barWaiting;
        private Thread thread;

        Job(Minecraft mc, ServerLevel server, WorldModel model, int cx, int cz, Done done) {
            this.level = mc.level;
            this.server = server;
            this.model = model;
            this.cx = cx;
            this.cz = cz;
            this.done = done;
            this.minY = server.getMinY();
            this.height = server.getHeight();
            this.far = new FarTerrain(model, minY, height);
            this.world = WorldIdentifier.of(mc.level);
            this.factory = PalettedContainerFactory.create(mc.level.registryAccess());
            this.regionDir = server.getServer().getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve("minecraft")
                    .resolve("overworld").resolve("region");
            this.radius = Math.max(16, OrbisMod.config().voxyFarViewChunks);
            this.reader = new com.berg.orbis.map.RegionReader(regionDir);
            // The client's registries: Voxy maps biomes by the client's holders.
            this.blockCodec = factory.blockStatesContainerCodec();
            this.biomeCodec = factory.biomeContainerCodec();
            // Voxy splits a piece of its tree when its outline on screen is wider than its subdivision size: a
            // setting it moves with the frame rate between 28 and 256 pixels. From the finest it can reach, the
            // distance in blocks past which each level is all it ever shows of a piece.
            double f = mc.getWindow().getHeight() / 2.0 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
            // Voxy measures a piece by the area of its outline on screen, which for a cube seen corner-on (from above at
            // an angle, as from a summit) is up to sqrt(3) times a face: at 1.15 it asked for finer pieces that were
            // not there 25,631 times at Everest once the frame rate let it go down to its finest.
            for (int l = 1; l <= 4; l++) splitWithin[l] = 32.0 * (1 << l) * f * Math.sqrt(3) / VOXY_FINEST_SUBDIVISION;
        }

        /** Voxy's finest subdivision size, pixels (it lowers its setting to this when the frame rate allows). */
        private static final double VOXY_FINEST_SUBDIVISION = 28;
        /** Closer than this (blocks), Voxy splits a piece of level l into the level below. */
        private final double[] splitWithin = new double[5];

        void start() {
            thread = new Thread(this, "Orbis-voxy-far-view");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
        }

        @Override
        public void run() {
            startedAt = System.nanoTime();
            lastLog = System.currentTimeMillis();
            startBytes = OrbisHttp.bytesReceived();
            // Voxy's tree, from its biggest pieces (512 blocks) down: a piece Voxy will not split at its distance from
            // the player goes in as it is, at its own coarse level (one write for up to 1,024 chunks, instead of a
            // full-block section for each); one it will split, as its four quarters, down to full blocks near by. So
            // wherever Voxy splits a piece all its quarters are there (no holes), and the far distance costs a
            // fraction of what it did (an hour for 16 km around Everest in full blocks).
            List<long[]> leaves = new ArrayList<>();
            int px = cx * 16 + 8, pz = cz * 16 + 8, reach = radius * 16;
            for (int tx = Math.floorDiv(px - reach, 512); tx <= Math.floorDiv(px + reach, 512); tx++) {
                for (int tz = Math.floorDiv(pz - reach, 512); tz <= Math.floorDiv(pz + reach, 512); tz++) collect(4, tx, tz, px, pz, reach, leaves);
            }
            leaves.sort(java.util.Comparator.comparingLong(l -> l[3]));
            long all = 0;
            for (long[] l : leaves) all += 1L << (2 * (l[0] + 1));
            totalChunks = all;
            System.out.println(String.format("[orbis] Voxy far view: filling %,d blocks around chunk %d, %d: %,d pieces of Voxy's tree"
                    + " (full blocks within %,.0f blocks, then 2, 4, 8 and 16-block cubes past %,.0f, %,.0f and %,.0f)",
                    reach, cx, cz, leaves.size(), splitWithin[1], splitWithin[2], splitWithin[3], splitWithin[4]));
            ForkJoinPool pool = new ForkJoinPool(THREADS);
            try {
                for (int i = 0; i < leaves.size() && !stopped; i += 256) {
                    List<long[]> batch = leaves.subList(i, Math.min(leaves.size(), i + 256));
                    long batchChunks = 0;
                    for (long[] l : batch) batchChunks += 1L << (2 * (l[0] + 1));
                    pool.submit(() -> batch.parallelStream().forEach(l -> {
                        if (!stopped) leaf((int) l[0], (int) l[1], (int) l[2]);
                    })).get();
                    covered.addAndGet(batchChunks);
                    reachedBlocks = batch.get(batch.size() - 1)[3];
                    log((int) (batch.get(batch.size() - 1)[3] / 16), false);
                }
                if (!stopped) {
                    finished = true;
                    log(radius, true);
                }
            } catch (Throwable t) {
                System.err.println("[orbis] Voxy far view stopped: " + t);
                t.printStackTrace();
            } finally {
                pool.shutdown();
                reader.close();
                done.save();
            }
        }

        /** The pieces of Voxy's tree to write, {level, x, z, distance in blocks}, from piece (level, tx, tz) down. */
        private void collect(int level, int tx, int tz, int px, int pz, int reach, List<long[]> out) {
            long size = 32L << level, x0 = tx * size, z0 = tz * size;
            double dx = Math.max(0, Math.max(x0 - px, px - (x0 + size - 1))), dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + size - 1)));
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > reach) return;
            if (level == 0 || d >= splitWithin[level]) {
                out.add(new long[]{level, tx, tz, (long) d});
                return;
            }
            for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) collect(level - 1, tx * 2 + i, tz * 2 + j, px, pz, reach, out);
        }

        /** One piece: in full blocks chunk by chunk (level 0, 2 x 2 chunks), else as coarse cubes with the saved chunks in it fed in full. */
        private void leaf(int level, int tx, int tz) {
            int n = 1 << (level + 1), x0 = tx * n, z0 = tz * n;
            if (level == 0) {
                for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) feed(x0 + i, z0 + j);
                return;
            }
            boolean any = false;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int x = x0 + i, z = z0 + j;
                    if (generated(x, z)) feed(x, z);
                    else if (!done.contains(x, z) || done.fedLevel(x, z) > level) any = true;
                }
            }
            if (!any || stopped || !waitForVoxy()) return;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int x = x0 + i, z = z0 + j;
                    if (!generated(x, z) && done.contains(x, z) && done.fedLevel(x, z) > level) clearCoarse(x, z, done.fedLevel(x, z));
                }
            }
            try {
                writeCoarse(level, tx, tz);
            } catch (RuntimeException | LinkageError e) {
                if (!coarseFailed) {
                    coarseFailed = true;
                    System.err.println("[orbis] Voxy far view: a coarse piece failed (" + e + "); continuing");
                    e.printStackTrace();
                }
                return;
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int x = x0 + i, z = z0 + j;
                    if (generated(x, z) || (done.contains(x, z) && done.fedLevel(x, z) < level)) continue;
                    done.addCoarse(x, z, level);
                    fed.incrementAndGet();
                }
            }
            coarsePieces.incrementAndGet();
        }

        private volatile boolean coarseFailed;
        private final AtomicLong coarsePieces = new AtomicLong();
        private final Map<BlockState, Integer> blockIds = new ConcurrentHashMap<>();

        /**
         * Writes a piece of Voxy's tree at its own level: 32 x 32 columns of 2^level-block cubes from the world's data,
         * straight into Voxy's sections of that level (each 32 cubes high), then the levels above it built from it
         * with Voxy's own merge, and the piece marked there as present (Voxy otherwise keeps a level's existence for
         * pieces built up from full blocks). Columns of saved chunks, or of chunks already written finer, are left as
         * they are. Each cube is the most solid block of its span, lit from the sky down as Voxy lights them.
         */
        private void writeCoarse(int level, int tx, int tz) {
            int s = 1 << level, g = 34, bx0 = tx * 32 * s, bz0 = tz * 32 * s;
            int detail = FarTerrain.FINE_DETAIL + 1;
            List<FarTerrain.Run>[] runs = new List[g * g];
            int[] grounds = new int[g * g];
            for (int j = 0; j < g; j++) {
                for (int i = 0; i < g; i++) {
                    int x = bx0 + (i - 1) * s + s / 2, z = bz0 + (j - 1) * s + s / 2;
                    boolean inside = i > 0 && j > 0 && i < g - 1 && j < g - 1;
                    int chx = x >> 4, chz = z >> 4;
                    boolean keep = !inside || generated(chx, chz) || (done.contains(chx, chz) && done.fedLevel(chx, chz) < level);
                    if (keep) {
                        grounds[j * g + i] = far.groundY(x, z, detail, true);
                        continue;
                    }
                    List<FarTerrain.Run> col = far.column(x, z, detail, true);
                    runs[j * g + i] = col;
                    grounds[j * g + i] = col == null ? Integer.MIN_VALUE : FarTerrain.ground(col, minY);
                }
            }
            me.cortex.voxy.common.world.WorldEngine engine = world.getOrCreateEngine();
            if (engine == null) return;
            me.cortex.voxy.common.world.other.Mapper mapper = engine.getMapper();
            // The cubes per Voxy section of this level (by section Y), and which of them this piece sets.
            Map<Integer, long[]> cubes = new HashMap<>();
            Map<Integer, java.util.BitSet> set = new HashMap<>();
            for (int j = 1; j < g - 1; j++) {
                for (int i = 1; i < g - 1; i++) {
                    List<FarTerrain.Run> col = runs[j * g + i];
                    if (col == null) continue;
                    int top = topOf(col);
                    if (top == Integer.MIN_VALUE) continue;
                    int low = grounds[j * g + i];
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int t = grounds[(j + d[1]) * g + (i + d[0])];
                        if (t != Integer.MIN_VALUE) low = Math.min(low, t);
                    }
                    int from = Math.max(minY, low - 2 * s);
                    int x = bx0 + (i - 1) * s + s / 2, z = bz0 + (j - 1) * s + s / 2;
                    Holder<Biome> biome = biome(x, top, z, col);
                    int biomeId = biome == null ? 0 : mapper.getIdForBiome(biome);
                    int vTop = Math.floorDiv(top, s), vFrom = Math.floorDiv(from, s);
                    // Open sky over the column, up to the top of its section: lit air, as Voxy keeps the sky's light.
                    // At least the cube above the top even when it is in the next section up: a top face against unlit
                    // air drew dark lines along the heights where the ground crosses a section's top.
                    for (int vy = vTop + 1; vy <= Math.max(vTop + 1, Math.floorDiv(vTop, 32) * 32 + 31); vy++) {
                        putCube(cubes, set, vy, i - 1, j - 1, me.cortex.voxy.common.world.other.Mapper.airWithLight(15));
                    }
                    int light = 15;
                    for (int vy = vTop; vy >= vFrom; vy--) {
                        BlockState st = solidest(col, vy * s, vy * s + s - 1);
                        int rank = rank(st);
                        // Full blocks are dark inside (Voxy lights their faces from the air beside them); snow
                        // layers, plants, leaves and water carry the light that reaches them, which Voxy shades them
                        // by (at 0, the snow layers covering Everest drew it black).
                        long id = st.isAir() ? me.cortex.voxy.common.world.other.Mapper.airWithLight(light)
                                : me.cortex.voxy.common.world.other.Mapper.composeMappingId((byte) (rank == 3 ? 0 : light),
                                blockIds.computeIfAbsent(st, mapper::getIdForBlockState), biomeId);
                        putCube(cubes, set, vy, i - 1, j - 1, id);
                        if (rank == 3) light = 0;
                        else if (rank == 2) light = Math.max(0, light - 1);
                        else if (rank == 1) light = Math.max(0, light - s);
                    }
                }
            }
            engine.acquireRef();
            try {
                for (Map.Entry<Integer, long[]> e : cubes.entrySet()) {
                    me.cortex.voxy.common.world.WorldSection section = engine.acquire(level, tx, e.getKey(), tz);
                    try {
                        long[] data = section._unsafeGetRawDataArray();
                        long[] mine = e.getValue();
                        java.util.BitSet which = set.get(e.getKey());
                        boolean solid = false;
                        for (int k = which.nextSetBit(0); k >= 0; k = which.nextSetBit(k + 1)) {
                            data[k] = mine[k];
                            if (!me.cortex.voxy.common.world.other.Mapper.isAir(mine[k])) solid = true;
                        }
                        engine.markDirty(section, me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0x3F);
                        sections.incrementAndGet();
                        if (solid) buildUp(engine, mapper, section);
                    } finally {
                        section.release();
                    }
                }
            } finally {
                engine.releaseRef();
            }
        }

        /**
         * Clears a chunk's columns from the coarse level it was written at, at every height, before it is written finer
         * (it has come closer, or Voxy wants more detail there than before). The finer write only reaches the heights
         * where its own land is: coarse cubes left at other heights, from a pass with other cut-offs or before the
         * player came nearer, hung about as stale land (dark streaks across the Everest snowfield). The levels above
         * are built again from the finer write.
         */
        private void clearCoarse(int chunkX, int chunkZ, int level) {
            me.cortex.voxy.common.world.WorldEngine engine = world.getNullable();
            if (engine == null) return;
            int size = 32 << level, bx = chunkX << 4, bz = chunkZ << 4;
            int sx = Math.floorDiv(bx, size), sz = Math.floorDiv(bz, size);
            int vx0 = (bx - sx * size) >> level, vz0 = (bz - sz * size) >> level, n = Math.max(1, 16 >> level);
            engine.acquireRef();
            try {
                for (int sy = Math.floorDiv(minY, size); sy <= Math.floorDiv(minY + height - 1, size); sy++) {
                    me.cortex.voxy.common.world.WorldSection section = engine.acquireIfExists(level, sx, sy, sz);
                    if (section == null) continue;
                    try {
                        long[] data = section._unsafeGetRawDataArray();
                        boolean changed = false;
                        for (int y = 0; y < 32; y++) {
                            for (int dz = 0; dz < n; dz++) {
                                for (int dx = 0; dx < n; dx++) {
                                    int k = me.cortex.voxy.common.world.WorldSection.getIndex(vx0 + dx, y, vz0 + dz);
                                    if (data[k] != 0) {
                                        data[k] = 0;
                                        changed = true;
                                    }
                                }
                            }
                        }
                        if (changed) engine.markDirty(section, me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0x3F);
                    } finally {
                        section.release();
                    }
                }
            } finally {
                engine.releaseRef();
            }
        }

        /** Builds the levels above a written section from it (Voxy's merge of 2 x 2 x 2 cubes) and marks it present in each. */
        private static void buildUp(me.cortex.voxy.common.world.WorldEngine engine, me.cortex.voxy.common.world.other.Mapper mapper,
                                    me.cortex.voxy.common.world.WorldSection written) {
            me.cortex.voxy.common.world.WorldSection child = written;
            boolean own = false;
            try {
                for (int lvl = written.lvl + 1; lvl <= me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER; lvl++) {
                    me.cortex.voxy.common.world.WorldSection parent = engine.acquire(lvl, child.x >> 1, child.y >> 1, child.z >> 1);
                    long[] c = child._unsafeGetRawDataArray(), p = parent._unsafeGetRawDataArray();
                    int ox = (child.x & 1) * 16, oy = (child.y & 1) * 16, oz = (child.z & 1) * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int x2 = x * 2, y2 = y * 2, z2 = z * 2;
                                // Voxy's own order: x fastest, then z, then y.
                                p[me.cortex.voxy.common.world.WorldSection.getIndex(ox + x, oy + y, oz + z)] = me.cortex.voxy.common.world.other.Mipper.mip(
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2, y2, z2)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2 + 1, y2, z2)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2, y2, z2 + 1)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2 + 1, y2, z2 + 1)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2, y2 + 1, z2)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2 + 1, y2 + 1, z2)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2, y2 + 1, z2 + 1)],
                                        c[me.cortex.voxy.common.world.WorldSection.getIndex(x2 + 1, y2 + 1, z2 + 1)], mapper);
                            }
                        }
                    }
                    int bit = 1 << me.cortex.voxy.common.world.WorldSection.getChildIndex(child.x, child.y, child.z);
                    boolean added;
                    synchronized (parent) {
                        byte m = parent.getNonEmptyChildren();
                        added = (m & bit) == 0;
                        if (added) parent._unsafeSetNonEmptyChildren((byte) (m | bit));
                    }
                    engine.markDirty(parent, me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT
                            | (added ? me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0), 0x3F);
                    if (own) child.release();
                    child = parent;
                    own = true;
                }
            } finally {
                if (own) child.release();
            }
        }

        private static void putCube(Map<Integer, long[]> cubes, Map<Integer, java.util.BitSet> set, int vy, int x, int z, long id) {
            int sy = Math.floorDiv(vy, 32);
            int k = me.cortex.voxy.common.world.WorldSection.getIndex(x, vy - sy * 32, z);
            cubes.computeIfAbsent(sy, q -> new long[32 * 32 * 32])[k] = id;
            set.computeIfAbsent(sy, q -> new java.util.BitSet(32 * 32 * 32)).set(k);
        }

        /** The most solid block of a column between two heights (the highest of equals), air when there is none. */
        private static BlockState solidest(List<FarTerrain.Run> col, int lo, int hi) {
            BlockState best = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            int bestRank = 0;
            for (FarTerrain.Run r : col) {
                if (r.y0() > hi || r.y1() < lo) continue;
                int rank = rank(r.state());
                if (rank > bestRank) {
                    best = r.state();
                    bestRank = rank;
                }
            }
            return best;
        }

        /** 0 air, 1 water and other fluids, 2 what light gets into (snow layers, plants, leaves, glass), 3 full blocks. */
        private static int rank(BlockState st) {
            if (st.isAir()) return 0;
            if (!st.getFluidState().isEmpty()) return 1;
            return st.isSolidRender() ? 3 : 2;
        }

        private void log(int r, boolean finished) {
            long now = System.currentTimeMillis();
            if (!finished && now - lastLog < 15_000) return;
            lastLog = now;
            done.save();
            double s = Math.max(1e-3, (System.nanoTime() - startedAt) / 1e9);
            me.cortex.voxy.common.world.WorldEngine engine = world.getNullable();
            Runtime rt = Runtime.getRuntime();
            System.out.println(String.format("[orbis] Voxy far view%s: %d of %d chunks out, %,d chunks (%,.0f/s; %,d of them saved), %,d sections, %.1f MB downloaded, %.0f s"
                            + " (waited for Voxy %.0f s; Voxy holds %,d sections; game memory %,d of %,d MB)",
                    finished ? " finished" : "", r, radius, fed.get(), fed.get() / s, saved.get(), sections.get(), (OrbisHttp.bytesReceived() - startBytes) / 1e6, s,
                    waits.get() * 0.02 / THREADS, engine == null ? 0 : engine.getActiveSectionCount(),
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20));
        }

        /**
         * Feeding threads: two thirds of the processor (8 of 12), the rest for the game, its server and Voxy's own
         * threads. Half (6 of 12) fed 570 chunks a second with Voxy idle most of the time.
         */
        private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() * 2 / 3);

        /** Blocks per far-view column by distance in chunks: full detail near, coarser farther. */
        private static int step(int dist) {
            if (dist < 64) return 1;
            if (dist < 192) return 2;
            if (dist < 448) return 4;
            return 8;
        }

        private void feed(int x, int z) {
            // Past the world's border too: the real landscape on the horizon instead of nothing (a world limited to
            // its pre-generated area, like Bergen 1:2, has no other land to show).
            boolean generated = generated(x, z);
            if (done.contains(x, z) && !(generated && done.isScenery(x, z)) && done.fedLevel(x, z) == 0) return;
            if (!waitForVoxy()) return;
            if (done.contains(x, z) && done.fedLevel(x, z) > 0) clearCoarse(x, z, done.fedLevel(x, z));
            if (generated) {
                if (feedSaved(x, z)) {
                    done.add(x, z);
                    fed.incrementAndGet();
                    saved.incrementAndGet();
                    if (fed.get() % 2048 == 0) log(Math.max(Math.abs(x - cx), Math.abs(z - cz)), false);
                }
                return;
            }
            int step = step(Math.max(Math.abs(x - cx), Math.abs(z - cz)));
            int detail = step == 1 ? 0 : FarTerrain.FINE_DETAIL + 1; // full terrain tiles only for full detail
            int n = 16 / step, minX = x << 4, minZ = z << 4;
            // Columns of this chunk with a ring of neighbours (for the cliff fill): (n + 2)^2 samples.
            int g = n + 2;
            int[] tops = new int[g * g], grounds = new int[g * g];
            List<FarTerrain.Run>[] runs = new List[g * g];
            for (int j = 0; j < g; j++) {
                for (int i = 0; i < g; i++) {
                    int bx = minX + (i - 1) * step + step / 2, bz = minZ + (j - 1) * step + step / 2;
                    if (i == 0 || j == 0 || i == g - 1 || j == g - 1) {
                        // A neighbour's column: only its ground counts (how deep this chunk's cliffs go).
                        grounds[j * g + i] = far.groundY(bx, bz, detail, true);
                        continue;
                    }
                    List<FarTerrain.Run> col = far.column(bx, bz, detail, true);
                    runs[j * g + i] = col;
                    tops[j * g + i] = col == null ? Integer.MIN_VALUE : FarTerrain.surface(col, minY);
                    grounds[j * g + i] = col == null ? Integer.MIN_VALUE : FarTerrain.ground(col, minY);
                }
            }
            Map<Integer, LevelChunkSection> out = new HashMap<>();
            for (int j = 1; j <= n; j++) {
                for (int i = 1; i <= n; i++) {
                    List<FarTerrain.Run> col = runs[j * g + i];
                    if (col == null) continue;
                    int top = tops[j * g + i];
                    // From the ground (under any water: water with nothing under it is see-through in Voxy) or the
                    // lowest neighbouring ground, whichever is lower, so slopes and cliffs have no holes.
                    int low = grounds[j * g + i];
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int t = grounds[(j + d[1]) * g + (i + d[0])];
                        if (t != Integer.MIN_VALUE) low = Math.min(low, t);
                    }
                    int from = Math.max(minY, Math.min(top, low) - 2);
                    int to = topOf(col); // includes water over the ground
                    Holder<Biome> biome = biome(minX + (i - 1) * step, top, minZ + (j - 1) * step, col);
                    for (FarTerrain.Run r : col) {
                        if (r.state().isAir() || r.y1() < from) continue;
                        for (int y = Math.max(r.y0(), from); y <= Math.min(r.y1(), to); y++) {
                            LevelChunkSection s = out.computeIfAbsent(y >> 4, k -> new LevelChunkSection(factory));
                            for (int dz = 0; dz < step; dz++) {
                                for (int dx = 0; dx < step; dx++) {
                                    int lx = (i - 1) * step + dx, lz = (j - 1) * step + dz;
                                    s.setBlockState(lx, y & 15, lz, r.state(), false);
                                }
                            }
                        }
                    }
                    setBiome(out, (i - 1) * step, (j - 1) * step, step, from, to, biome);
                }
            }
            // Light as it falls (full light everywhere drew deep water pale), no block light.
            java.util.TreeMap<Integer, LevelChunkSection> filled = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
            for (Map.Entry<Integer, LevelChunkSection> e : out.entrySet()) if (!e.getValue().hasOnlyAir()) filled.put(e.getKey(), e.getValue());
            Map<Integer, DataLayer> skies = new HashMap<>();
            skyLight(filled, skies);
            DataLayer block = new DataLayer();
            for (Map.Entry<Integer, LevelChunkSection> e : filled.entrySet()) {
                VoxelIngestService.rawIngest(world, e.getValue(), x, e.getKey(), z, block, skies.get(e.getKey()));
                sections.incrementAndGet();
            }
            done.addScenery(x, z);
            fed.incrementAndGet();
            if (fed.get() % 2048 == 0) log(Math.max(Math.abs(x - cx), Math.abs(z - cz)), false);
        }

        /** Water fed under the surface of a saved chunk at most; deeper water gets a floor there. */
        private static final int WATER_FED = 32;

        /** {lowest, highest} block Y of a saved heightmap, or null without one. */
        private int[] heights(long[] hm) {
            if (hm == null) return null;
            int bits = net.minecraft.util.Mth.ceillog2(height + 1), per = 64 / bits, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int i = 0; i < 256 && i / per < hm.length; i++) {
                int t = minY + (int) ((hm[i / per] >>> ((i % per) * bits)) & ((1L << bits) - 1)) - 1;
                low = Math.min(low, t);
                high = Math.max(high, t);
            }
            return low == Integer.MAX_VALUE ? null : new int[]{low, high};
        }

        /** Gravel under water still going on below the lowest section fed, so deep water is not see-through. */
        private static void floorDeepWater(LevelChunkSection lowest) {
            BlockState gravel = net.minecraft.world.level.block.Blocks.GRAVEL.defaultBlockState();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (!lowest.getBlockState(lx, 0, lz).getFluidState().isEmpty()) lowest.setBlockState(lx, 0, lz, gravel, false);
                }
            }
        }

        /** Sections under the lowest surface of a saved chunk that go in too (cliff faces, river banks). */
        private static final int SAVED_DEPTH = 16;

        /**
         * A generated chunk as saved: its sections from SAVED_DEPTH under the lowest surface (by the saved heightmap)
         * up to the highest, with the saved light where the save has it. False when it cannot be read.
         */
        private boolean feedSaved(int x, int z) {
            net.minecraft.nbt.CompoundTag tag;
            try {
                tag = reader.read(x, z);
            } catch (Exception e) {
                return false;
            }
            if (tag == null) return false;
            int lowY = minY, highY = minY + height - 1;
            net.minecraft.nbt.CompoundTag maps = tag.getCompoundOrEmpty("Heightmaps");
            int[] surface = heights(maps.getLongArray("WORLD_SURFACE").orElse(null));
            int[] floor = heights(maps.getLongArray("OCEAN_FLOOR").orElse(null));
            if (surface != null) {
                // Down to the sea or lake bed (the floor heightmap ignores water): water with nothing under it is
                // see-through in Voxy, and the fjords showed the sky through them. At most WATER_FED under the
                // surface; a deeper bed gets a floor there instead (the city's fjords are hundreds of metres deep).
                int low = Math.min(surface[0], floor != null ? floor[0] : surface[0]);
                lowY = Math.max(minY, Math.max(low - 2, surface[0] - WATER_FED) - (floor != null ? 0 : SAVED_DEPTH));
                lowY = Math.min(lowY, Math.max(minY, surface[0] - SAVED_DEPTH));
                highY = Math.max(lowY, surface[1]);
            }
            boolean lit = tag.getBooleanOr("isLightOn", false);
            java.util.TreeMap<Integer, LevelChunkSection> found = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
            Map<Integer, DataLayer> skies = new HashMap<>(), blocks = new HashMap<>();
            net.minecraft.nbt.ListTag list = tag.getListOrEmpty("sections");
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.nbt.CompoundTag s = list.getCompoundOrEmpty(i);
                int sy = s.getByteOr("Y", (byte) 0);
                if ((sy << 4) + 15 < lowY || (sy << 4) > highY || s.get("block_states") == null || s.get("biomes") == null) continue;
                var states = blockCodec.parse(net.minecraft.nbt.NbtOps.INSTANCE, s.get("block_states")).result().orElse(null);
                var biomes = biomeCodec.parse(net.minecraft.nbt.NbtOps.INSTANCE, s.get("biomes")).result().orElse(null);
                if (states == null || biomes == null) continue;
                LevelChunkSection section = new LevelChunkSection(states, biomes);
                if (section.hasOnlyAir()) continue;
                found.put(sy, section);
                byte[] skyBytes = s.getByteArray("SkyLight").orElse(null), blockBytes = s.getByteArray("BlockLight").orElse(null);
                if (lit && skyBytes != null && skyBytes.length == 2048) skies.put(sy, new DataLayer(skyBytes));
                if (lit && blockBytes != null && blockBytes.length == 2048) blocks.put(sy, new DataLayer(blockBytes));
            }
            if (!found.isEmpty()) floorDeepWater(found.lastEntry().getValue());
            // A chunk saved without light (an export copy with its light stripped, put back): full light everywhere
            // drew its sea pale beside the lit chunks around it (Bergen 1:2, 699 such chunks around the city).
            if (skies.size() < found.size()) skyLight(found, skies);
            for (Map.Entry<Integer, LevelChunkSection> e : found.entrySet()) {
                DataLayer block = blocks.getOrDefault(e.getKey(), new DataLayer());
                VoxelIngestService.rawIngest(world, e.getValue(), x, e.getKey(), z, block, skies.get(e.getKey()));
                sections.incrementAndGet();
            }
            return true;
        }

        /**
         * Sky light for sections saved without it, top down per column as Minecraft's own light comes out in the open:
         * full through air, a level less for each block of water, leaves or glass, none under a solid block.
         */
        private static void skyLight(java.util.TreeMap<Integer, LevelChunkSection> found, Map<Integer, DataLayer> skies) {
            Map<Integer, DataLayer> out = new HashMap<>();
            for (int sy : found.keySet()) out.put(sy, new DataLayer());
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int light = 15;
                    for (Map.Entry<Integer, LevelChunkSection> e : found.entrySet()) { // highest section first
                        DataLayer layer = out.get(e.getKey());
                        for (int ly = 15; ly >= 0; ly--) {
                            BlockState st = e.getValue().getBlockState(lx, ly, lz);
                            // Full blocks are dark inside; snow layers, plants, leaves and water keep the light that
                            // reaches them (Voxy shades them by their own light: snow layers at 0 drew Everest black).
                            boolean full = !st.isAir() && st.getFluidState().isEmpty() && st.isSolidRender();
                            layer.set(lx, ly, lz, full ? 0 : light);
                            if (full) light = 0;
                            else if (!st.isAir()) light = Math.max(0, light - 1);
                        }
                    }
                }
            }
            for (Map.Entry<Integer, DataLayer> e : out.entrySet()) skies.putIfAbsent(e.getKey(), e.getValue());
        }

        /** Sections Voxy may hold in memory before the feed waits (as loaded, being saved). */
        private static final int MAX_ACTIVE_SECTIONS = 20_000;

        /**
         * Waits while Voxy is behind: its save queue over its own limit (the rate limiter its importers wait on: under
         * 1,200 saves queued), too many sections in its memory, or the game's memory over 80% full. Fed as fast as it
         * could be, the prototype took the game past 18 GB (Voxy's saves piled up). False when the job stops meanwhile.
         */
        private boolean waitForVoxy() {
            Runtime rt = Runtime.getRuntime();
            while (!stopped) {
                me.cortex.voxy.common.world.WorldEngine engine = world.getNullable();
                boolean saving = engine == null || engine.instanceIn == null || engine.instanceIn.savingServiceRateLimiter.getAsBoolean();
                boolean memory = engine == null || engine.getActiveSectionCount() < MAX_ACTIVE_SECTIONS;
                boolean heap = rt.totalMemory() - rt.freeMemory() < rt.maxMemory() * 0.8;
                if (saving && memory && heap) return true;
                waits.incrementAndGet();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return false;
        }

        private static int topOf(List<FarTerrain.Run> runs) {
            for (FarTerrain.Run r : runs) if (!r.state().isAir()) return r.y1();
            return Integer.MIN_VALUE;
        }

        private final Map<Holder<Biome>, Holder<Biome>> clientBiomes = new ConcurrentHashMap<>();

        /** The surface biome, as the client's registry has it (Voxy maps biomes by the client's holders). */
        private Holder<Biome> biome(int x, int y, int z, List<FarTerrain.Run> col) {
            if (!(server.getChunkSource().getGenerator().getBiomeSource() instanceof RealWorldBiomeSource b)) return null;
            // Water the far view drew (the sea or a lake past the map data) gets a water biome: the biome source only
            // knows sea from the map's coastline, and plains tinted the sea in front of Askoy pale.
            BlockState surface = null;
            for (FarTerrain.Run r : col) {
                if (!r.state().isAir()) {
                    surface = r.state();
                    break;
                }
            }
            Holder<Biome> h = surface != null && surface.is(net.minecraft.world.level.block.Blocks.WATER)
                    ? b.farWaterBiome(x, z, topOf(col) <= model.cfg().waterLevelY())
                    : b.farBiome(x >> 2, z >> 2);
            if (h == null) return null;
            return clientBiomes.computeIfAbsent(h, k -> k.unwrapKey().<Holder<Biome>>map(key -> level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key)).orElse(k));
        }

        private static void setBiome(Map<Integer, LevelChunkSection> sections, int x0, int z0, int step, int from, int to, Holder<Biome> biome) {
            if (biome == null) return;
            for (int y = from & ~3; y <= to; y += 4) {
                LevelChunkSection s = sections.get(y >> 4);
                if (s == null) continue;
                for (int dz = 0; dz < step; dz += 4) {
                    for (int dx = 0; dx < step; dx += 4) {
                        ((net.minecraft.world.level.chunk.PalettedContainer<Holder<Biome>>) s.getBiomes()).getAndSetUnchecked((x0 + dx) >> 2, (y & 15) >> 2, (z0 + dz) >> 2, biome);
                    }
                }
            }
        }

        /** Whether the world has this chunk on disk, finished (Voxy takes those from the real world). */
        private boolean generated(int x, int z) {
            long rk = ((long) (x >> 5) << 32) | ((z >> 5) & 0xffffffffL);
            byte[] state = regionState.computeIfAbsent(rk, k -> {
                Path f = regionDir.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
                try {
                    return Files.exists(f) ? PregenMap.regionState(f) : new byte[0];
                } catch (Exception e) {
                    return new byte[0];
                }
            });
            return state.length == 1024 && state[(x & 31) + ((z & 31) << 5)] == 2;
        }
    }
}
