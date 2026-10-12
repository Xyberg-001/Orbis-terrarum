package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.net.AllowedAreaPayload;
import com.berg.orbis.net.PregenTasksPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world's pre-generations not finished yet, listed on the world map: each with its area, how far it has got and whether it runs,
 * waits its turn (queued) or is paused. One runs at a time; when it finishes or is paused the next queued one starts. Operators resume,
 * pause or stop (end and remove) any of them from the map; the map shades what each has left in purple.
 * <p>
 * Kept in the world's {@code orbis-pregen/tasks} folder ({@code <id>.json}: what, state, last row done; {@code <id>-area.json}: its
 * chunks), so they outlive a restart: the one that was running when the world closed carries on by itself a few seconds after it
 * opens, the others keep their state. A task's id is its area's fingerprint, so pressing Generate on the same selection again picks the
 * task up instead of making a second one. The sweep itself is {@link PregenTask} (or the cubic sweeper in a cubic world).
 */
public final class PregenTasks {
    /** DOWNLOADING: listed, waiting for its map data to download (the map's "Map data: Download"); it is queued when that is in. */
    public enum State { RUNNING, QUEUED, PAUSED, DOWNLOADING }

    /** One pre-generation: its whole area (rows north to south) and the last row on disk. */
    public static final class Task {
        public final String id, label, kind;
        /** Skip open sea for this one; null: the world's setting. */
        public final Boolean skipSea;
        public final ChunkSelection area;
        public final long total;
        int doneRow;
        long doneChunks;
        State state;
        /** Its map data comes from a Geofabrik download (else the online servers while it generates). */
        boolean download;
        /** Its download runs now (it can while the task is paused). */
        boolean downloadRunning;
        /** How far that download has got (0 to 1), or -1. */
        float mapFraction = -1;

        Task(String id, String label, String kind, Boolean skipSea, ChunkSelection area, int doneRow, State state) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.skipSea = skipSea;
            this.area = area;
            this.total = area.count();
            this.state = state;
            setDone(doneRow);
        }

        public int doneRow() {
            return doneRow;
        }

        void setDone(int row) {
            doneRow = Math.max(area.firstRow() - 1, Math.min(area.lastRow(), row));
            long n = 0;
            for (int cz = area.firstRow(); cz <= doneRow; cz++) {
                int[] r = area.rawRuns(cz);
                if (r != null) for (int i = 0; i < r.length; i += 2) n += r[i + 1] - r[i] + 1;
            }
            doneChunks = n;
        }
    }

    /** A drawn selection, a place's outline, or the world's automatic pre-generation. */
    public static final String SELECTION = "selection", AREA = "area", AUTO = "auto";
    /** How long after the world opens the task it closed with carries on. */
    private static final long RESUME_DELAY_MS = 5_000;

    private static MinecraftServer server;
    /** In the order they were made: the queue's order. */
    private static final Map<String, Task> tasks = new LinkedHashMap<>();
    /** The task whose sweep runs now, or null. */
    private static Task running;
    /** It runs on the cubic sweeper (which says only whether it runs: finished is "no longer running and not paused by us"). */
    private static boolean runningCubic;
    private static long resumeAt;
    /** A sweep finished: start the next queued one on a later tick, once the finished one has let go of everything. */
    private static boolean nextDue;
    /** The areas each player's map already has (sent once; progress comes without them). */
    private static final Map<UUID, Set<String>> sent = new HashMap<>();
    private static int ticks;
    /** Writes the files in order, beside the game's thread (a country's rows run to megabytes). */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-pregen-tasks");
        t.setDaemon(true);
        return t;
    });

    private PregenTasks() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PregenTasksPayload.TYPE, PregenTasksPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(PregenTasks::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            synchronized (PregenTasks.class) {
                server = null;
                nextDue = false;
                tasks.clear();
                running = null;
                sent.clear();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> s.execute(() -> {
            synchronized (PregenTasks.class) {
                sent.remove(handler.player.getUUID());
                sendTo(handler.player);
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> s.execute(() -> {
            synchronized (PregenTasks.class) {
                sent.remove(handler.player.getUUID());
            }
        }));
        ServerTickEvents.END_SERVER_TICK.register(PregenTasks::tick);
    }

    // ------------------------------------------------------------------ adding

    /**
     * A new pre-generation (or the same area again): it starts at once when nothing runs, else it waits its turn. {@code doneRow}: rows
     * already done (from an older progress file), or the area's first row - 1. {@code initial}: QUEUED to start (or wait its turn),
     * PAUSED to list it only, DOWNLOADING to list it until its map data is in ({@link #mapDataDone}). {@code note} is added to the
     * start message.
     */
    static synchronized Component submit(ServerLevel level, String label, String kind, ChunkSelection area, Boolean skipSea, int doneRow,
                                         State initial, String note) {
        if (area.isEmpty()) return Component.literal("The area is empty.");
        server = level.getServer();
        String id = area.fingerprint();
        Task t = tasks.get(id);
        State want = initial == State.RUNNING ? State.QUEUED : initial;
        if (t == null) {
            t = new Task(id, label, kind, skipSea, area, doneRow, want);
            tasks.put(id, t);
            writeArea(t);
            t.download = want == State.DOWNLOADING;
        } else if (t.state == State.RUNNING) {
            return Component.literal(t.label + " is already being pre-generated (world map, B).");
        } else if (want != State.PAUSED) {
            t.state = want;
            if (want == State.DOWNLOADING) t.download = true;
        }
        writeMeta(t);
        Component reply;
        if (t.state == State.PAUSED) {
            reply = Component.literal(t.label + " is listed as paused on the world map (B): Resume carries on with it.");
        } else if (t.state == State.DOWNLOADING) {
            reply = Component.literal(t.label + " is listed on the world map (B); it starts when its map data is in.");
        } else if (running == null && !PregenTask.isRunning()) {
            reply = run(level, t);
            if (!note.isEmpty()) reply = Component.empty().append(reply).append(note);
        } else {
            Task r = running;
            reply = Component.literal(String.format(Locale.ROOT, "%s is queued: it starts when %s is done (world map, B).", t.label,
                    r != null ? r.label : "the pre-generation running now"));
        }
        broadcast();
        return reply;
    }

    /** A pre-generation's map data download has started (MapRequests.downloadForTask). */
    public static synchronized void mapDataStarted(String id) {
        Task t = tasks.get(id);
        if (t == null) return;
        t.downloadRunning = true;
        t.mapFraction = -1;
        broadcast();
    }

    /** How far a pre-generation's map data download has got (0 to 1); the map's list shows it. */
    public static synchronized void mapDataProgress(String id, float fraction) {
        Task t = tasks.get(id);
        if (t == null || !t.downloadRunning) return;
        boolean changed = Math.round(t.mapFraction * 100) != Math.round(fraction * 100);
        t.mapFraction = fraction;
        if (changed) broadcast();
    }

    /**
     * Its map data download ended: in, or not needed ({@code ok}), or failed (it goes on with the online servers, Live). A
     * pre-generation waiting for it is queued and may start; a paused one stays paused.
     */
    public static synchronized void mapDataDone(String id, boolean ok) {
        Task t = tasks.get(id);
        if (t == null) return;
        t.downloadRunning = false;
        t.mapFraction = -1;
        if (!ok) t.download = false;
        if (t.state == State.DOWNLOADING) t.state = State.QUEUED;
        writeMeta(t);
        Component started = startNext();
        if (started != null && server != null) server.getPlayerList().broadcastSystemMessage(started, false);
        broadcast();
    }

    /**
     * Switches pre-generations between Live (the online servers while they generate) and Download (Geofabrik first), when one of
     * them fails: Download to Live drops the download and the task may go at once; Live to Download pauses a running one, downloads
     * its map data and queues it when that is in. One download runs at a time.
     */
    private static Component switchSource(MinecraftServer s, List<Task> chosen, UUID who) {
        List<String> out = new ArrayList<>();
        for (Task t : chosen) {
            if (t.download) {
                if (t.downloadRunning) com.berg.orbis.osm.extract.MapDataJob.stop();
                t.download = false;
                t.downloadRunning = false;
                t.mapFraction = -1;
                if (t.state == State.DOWNLOADING) t.state = State.QUEUED;
                writeMeta(t);
                out.add(t.label + ": Live");
            } else if (com.berg.orbis.osm.extract.MapDataJob.busy()) {
                out.add(t.label + ": stays Live (another map data download is running; switch it when that is done)");
            } else {
                if (t == running) haltRunning();
                State before = t.state;
                t.download = true;
                t.state = State.DOWNLOADING;
                writeMeta(t);
                if (com.berg.orbis.map.MapRequests.downloadForTask(s, who, t.id, t.area)) {
                    out.add(t.label + ": Download");
                } else {
                    t.download = false;
                    t.state = before == State.RUNNING ? State.QUEUED : before;
                    writeMeta(t);
                    out.add(t.label + ": stays Live (no map data download possible here)");
                }
            }
        }
        Component started = startNext();
        broadcast();
        String msg = "Map data: " + String.join(", ", out) + ".";
        return started == null ? Component.literal(msg) : Component.literal(msg + "\n").append(started);
    }

    // ------------------------------------------------------------------ the map's buttons

    /** Resume, Pause or Stop (end and remove) the tasks named, from the world map. */
    public static synchronized Component control(MinecraftServer s, String action, List<String> ids, UUID who) {
        server = s;
        List<Task> chosen = new ArrayList<>();
        for (Task t : tasks.values()) if (ids.contains(t.id)) chosen.add(t);
        if (chosen.isEmpty()) return Component.literal("Those pre-generations are gone already.");
        List<String> names = new ArrayList<>();
        switch (action) {
            case "resume" -> {
                for (Task t : chosen) {
                    if (t.state != State.PAUSED) continue;
                    t.state = State.QUEUED;
                    writeMeta(t);
                    names.add(t.label);
                }
                Component started = startNext();
                broadcast();
                if (started != null) return started;
                return Component.literal(names.isEmpty() ? "Nothing to resume." : "Queued: " + String.join(", ", names) + ".");
            }
            case "pause" -> {
                for (Task t : chosen) {
                    if (t.state == State.PAUSED) continue;
                    if (t == running) haltRunning();
                    t.state = State.PAUSED;
                    writeMeta(t);
                    names.add(t.label);
                }
                Component started = startNext();
                broadcast();
                String msg = names.isEmpty() ? "Nothing to pause." : "Paused: " + String.join(", ", names) + ". Resume on the world map carries on with it.";
                return started == null ? Component.literal(msg) : Component.literal(msg + "\n").append(started);
            }
            case "source" -> {
                return switchSource(s, chosen, who);
            }
            case "stop" -> {
                for (Task t : chosen) {
                    if (t == running) haltRunning();
                    if (t.downloadRunning) com.berg.orbis.osm.extract.MapDataJob.stop();
                    tasks.remove(t.id);
                    deleteFiles(t);
                    names.add(t.label);
                }
                Component started = startNext();
                broadcast();
                String msg = "Stopped and removed: " + String.join(", ", names) + " (what is generated stays).";
                return started == null ? Component.literal(msg) : Component.literal(msg + "\n").append(started);
            }
            default -> {
                return Component.literal("Unknown action " + action);
            }
        }
    }

    // ------------------------------------------------------------------ the sweep's reports

    /** Rows up to {@code done} are on disk (every 10 s, and as the sweep stops). */
    static synchronized void progress(Task t, int done) {
        if (t == null || done == t.doneRow || !tasks.containsKey(t.id)) return;
        t.setDone(done);
        writeMeta(t);
        broadcast();
    }

    /** The sweep ran to its end: the task goes, and the next queued one starts. */
    static synchronized void finished(Task t) {
        if (t == null) return;
        if (running == t) running = null;
        if (tasks.remove(t.id) != null) deleteFiles(t);
        broadcast();
        // Not now: this is called from inside the finishing sweep, which still restores the region cache it widened (and a next
        // sweep started here ran with the cache shrunk under it and stalled). server.execute would run it right here too.
        nextDue = true;
    }

    /** The task whose sweep runs now, or null. */
    public static synchronized Task running() {
        return running;
    }

    // ------------------------------------------------------------------ running

    private static Component run(ServerLevel level, Task t) {
        running = t;
        t.state = State.RUNNING;
        writeMeta(t);
        Component reply = PregenTask.runTask(level, t);
        runningCubic = PregenTask.active() == null && PregenTask.isRunning();
        if (!PregenTask.isRunning()) {
            // It could not start (the world model not ready): wait as paused rather than spin.
            running = null;
            t.state = State.PAUSED;
            writeMeta(t);
        }
        return reply;
    }

    /** Starts the first queued task when nothing runs; its start message, or null. */
    private static Component startNext() {
        if (running != null || PregenTask.isRunning() || server == null) return null;
        for (Task t : tasks.values()) {
            if (t.state == State.QUEUED) return run(server.overworld(), t);
        }
        return null;
    }

    /** Stops the running sweep, its progress kept (it reports it through {@link #progress}). */
    private static void haltRunning() {
        Task t = running;
        running = null;
        if (t == null) return;
        PregenTask.stop();
        t.state = State.PAUSED;
    }

    private static void tick(MinecraftServer s) {
        if (nextDue) {
            synchronized (PregenTasks.class) {
                nextDue = false;
                Component started = startNext();
                if (started != null) s.getPlayerList().broadcastSystemMessage(started, false);
            }
        }
        if (resumeAt != 0 && System.currentTimeMillis() >= resumeAt) {
            resumeAt = 0;
            synchronized (PregenTasks.class) {
                Component started = startNext();
                if (started != null) {
                    System.out.println("[orbis] Pre-generation carries on: " + started.getString());
                    s.getPlayerList().broadcastSystemMessage(started, false);
                }
            }
        }
        if (++ticks % 20 != 0) return;
        synchronized (PregenTasks.class) {
            // The cubic sweeper finished by itself.
            if (running != null && runningCubic && !PregenTask.isRunning() && !OrbisMod.stopping()) finished(running);
            // Its rows done, each second (the chunk sweep reports them itself), so the purple shrinks there too.
            PregenTask.CubicSweeper c = PregenTask.cubicSweeper;
            if (running != null && runningCubic && c != null && c.running()) {
                int row = c.doneRow();
                if (row != Integer.MIN_VALUE) progress(running, row);
            }
        }
    }

    // ------------------------------------------------------------------ files

    /** The world opens: its tasks, the one that was running to carry on shortly. */
    private static synchronized void load(MinecraftServer s) {
        server = s;
        tasks.clear();
        running = null;
        sent.clear();
        resumeAt = 0;
        if (!(s.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
        Path dir = dir();
        if (!Files.isDirectory(dir)) return;
        List<Task> found = new ArrayList<>();
        Map<String, Long> created = new HashMap<>();
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json") || name.endsWith("-area.json")) continue;
                try {
                    JsonObject m = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    String id = m.get("id").getAsString();
                    ChunkSelection area = ChunkSelection.fromJson(Files.readString(dir.resolve(id + "-area.json"), StandardCharsets.UTF_8));
                    if (area.isEmpty()) continue;
                    State st = State.valueOf(m.get("state").getAsString());
                    // Its download stopped with the server: listed as paused (Resume uses the online servers, or press Generate
                    // with Download again).
                    if (st == State.DOWNLOADING) st = State.PAUSED;
                    Task t = new Task(id, m.get("label").getAsString(), m.get("kind").getAsString(),
                            m.has("skipSea") ? m.get("skipSea").getAsBoolean() : null, area, m.get("doneRow").getAsInt(), st);
                    t.download = m.has("download") && m.get("download").getAsBoolean();
                    found.add(t);
                    created.put(id, m.has("created") ? m.get("created").getAsLong() : 0L);
                } catch (IOException | RuntimeException e) {
                    System.err.println("[orbis] Could not read the pre-generation " + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[orbis] Could not list the pre-generations: " + e.getMessage());
        }
        found.sort(java.util.Comparator.comparingLong(t -> created.get(t.id)));
        boolean resume = false;
        for (Task t : found) {
            // The one that was running when the world closed goes first; the queue keeps its order behind it.
            if (t.state == State.RUNNING) {
                t.state = State.QUEUED;
                resume = true;
                tasks.put(t.id, t);
            }
        }
        for (Task t : found) tasks.putIfAbsent(t.id, t);
        if (resume) resumeAt = System.currentTimeMillis() + RESUME_DELAY_MS;
        for (Task t : tasks.values()) {
            System.out.printf(Locale.ROOT, "[orbis] Pre-generation %s: %s, %,d of %,d chunks done%n", t.state.name().toLowerCase(Locale.ROOT), t.label,
                    t.doneChunks, t.total);
        }
    }

    private static Path dir() {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen").resolve("tasks");
    }

    private static void writeArea(Task t) {
        Path f = dir().resolve(t.id + "-area.json");
        String json = t.area.toJson();
        WRITER.execute(() -> {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[orbis] Could not save a pre-generation's area: " + e.getMessage());
            }
        });
    }

    private static void writeMeta(Task t) {
        Path f = dir().resolve(t.id + ".json");
        JsonObject m = new JsonObject();
        m.addProperty("id", t.id);
        m.addProperty("label", t.label);
        m.addProperty("kind", t.kind);
        if (t.skipSea != null) m.addProperty("skipSea", t.skipSea);
        m.addProperty("state", t.state.name());
        if (t.download) m.addProperty("download", true);
        m.addProperty("doneRow", t.doneRow);
        m.addProperty("firstRow", t.area.firstRow());
        m.addProperty("lastRow", t.area.lastRow());
        m.addProperty("chunks", t.total);
        m.addProperty("created", created(f));
        String json = m + "\n";
        WRITER.execute(() -> {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[orbis] Could not save a pre-generation: " + e.getMessage());
            }
        });
    }

    /** When the task was first written (its queue place), kept across rewrites. */
    private static final Map<Path, Long> createdAt = new HashMap<>();

    private static long created(Path f) {
        return createdAt.computeIfAbsent(f, k -> {
            try {
                if (Files.exists(k)) {
                    JsonObject m = JsonParser.parseString(Files.readString(k, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (m.has("created")) return m.get("created").getAsLong();
                }
            } catch (IOException | RuntimeException ignored) {
            }
            return System.currentTimeMillis();
        });
    }

    private static void deleteFiles(Task t) {
        Path dir = dir();
        createdAt.remove(dir.resolve(t.id + ".json"));
        WRITER.execute(() -> {
            try {
                Files.deleteIfExists(dir.resolve(t.id + ".json"));
                Files.deleteIfExists(dir.resolve(t.id + "-area.json"));
            } catch (IOException e) {
                System.err.println("[orbis] Could not remove a pre-generation's files: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ the world map

    /** The list for one player, with the areas their map does not have yet (in messages of a bounded size). */
    private static void sendTo(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, PregenTasksPayload.TYPE)) return;
        Set<String> has = sent.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        has.retainAll(tasks.keySet());
        List<Task> missing = new ArrayList<>();
        for (Task t : tasks.values()) if (!has.contains(t.id) && AllowedAreaPayload.fits(t.area)) missing.add(t);
        do {
            Map<String, ChunkSelection> areas = new HashMap<>();
            long runs = 0;
            while (!missing.isEmpty()) {
                Task t = missing.get(0);
                long r = runs(t.area);
                if (!areas.isEmpty() && runs + r > AllowedAreaPayload.MAX_RUNS) break;
                areas.put(t.id, t.area);
                runs += r;
                has.add(t.id);
                missing.remove(0);
            }
            List<PregenTasksPayload.Entry> list = new ArrayList<>();
            for (Task t : tasks.values()) {
                int mapPercent = t.downloadRunning ? (t.mapFraction < 0 ? 0 : Math.round(t.mapFraction * 100)) : -1;
                list.add(new PregenTasksPayload.Entry(t.id, t.label, t.state.ordinal(), t.total, t.doneChunks, t.doneRow, areas.get(t.id),
                        t.download, mapPercent));
            }
            ServerPlayNetworking.send(player, new PregenTasksPayload(list));
        } while (!missing.isEmpty());
    }

    private static long runs(ChunkSelection a) {
        long n = 0;
        for (int z = a.firstRow(); z <= a.lastRow(); z++) {
            int[] r = a.rawRuns(z);
            if (r != null) n += r.length / 2;
        }
        return n;
    }

    private static void broadcast() {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            synchronized (PregenTasks.class) {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) sendTo(p);
            }
        });
    }
}
