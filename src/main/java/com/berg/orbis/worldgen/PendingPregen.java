package com.berg.orbis.worldgen;

import com.berg.orbis.net.AllowedAreaPayload;
import com.berg.orbis.net.PendingAreaPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The pre-generation not finished yet, shaded purple on the world map: the running sweep's area, or a stopped one's (Stop on the map,
 * or put aside as the server stopped), less the chunk rows already on disk. It lives in the world's orbis-pregen folder
 * (pending.json: what and how far; pending-area.json: its chunks), so after a restart the map still shows it and Resume carries on
 * with a selection drawn on the map; it goes when the sweep finishes. Sweeps of a cubic world are not tracked here.
 */
public final class PendingPregen {
    /** A selection drawn on the map (resumed from here after a restart). */
    static final String SELECTION = "selection";
    /** A place's outline. */
    static final String AREA = "area";
    /** The world's automatic pre-generation (AutoPregen resumes it itself). */
    static final String AUTO = "auto";

    private static MinecraftServer server;
    /** The sweep's whole area; null when nothing is pending. */
    private static ChunkSelection area;
    private static String label = "", kind = "";
    private static Boolean skipSea;
    private static int doneRow;
    /** Too many runs for a message: kept, but not shown. */
    private static boolean tooBig;
    /** Writes the files in order, beside the game's thread (a country's rows run to megabytes). */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-pending-pregen");
        t.setDaemon(true);
        return t;
    });

    private PendingPregen() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PendingAreaPayload.TYPE, PendingAreaPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(PendingPregen::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            area = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> s.execute(() -> sendTo(handler.player, full())));
    }

    /** A sweep starts (or resumes): its whole area, and the last row done before it ({@code doneRow}). */
    static synchronized void begin(ServerLevel level, String what, String how, ChunkSelection sweepArea, Boolean seaChoice, int done) {
        server = level.getServer();
        area = sweepArea;
        label = what;
        kind = how;
        skipSea = seaChoice;
        doneRow = done;
        tooBig = !AllowedAreaPayload.fits(sweepArea);
        Path dir = dir();
        String json = sweepArea.toJson();
        WRITER.execute(() -> { // the area is not changed after the start
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pending-area.json"), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[orbis] Could not save the pending pre-generation's area: " + e.getMessage());
            }
        });
        writeMeta();
        broadcast(full());
    }

    /** The world's automatic pre-generation started the sweep just begun. */
    static synchronized void markAuto() {
        if (area == null) return;
        kind = AUTO;
        writeMeta();
    }

    /** Rows up to {@code done} are on disk. */
    static synchronized void progress(int done) {
        if (area == null || done == doneRow) return;
        doneRow = done;
        writeMeta();
        if (!tooBig) broadcast(new PendingAreaPayload(doneRow, null));
    }

    /** The sweep ran to its end: nothing is pending. */
    static synchronized void finished() {
        if (server == null) return;
        area = null;
        Path dir = dir();
        WRITER.execute(() -> {
            try {
                Files.deleteIfExists(dir.resolve("pending.json"));
                Files.deleteIfExists(dir.resolve("pending-area.json"));
            } catch (IOException e) {
                System.err.println("[orbis] Could not clear the pending pre-generation: " + e.getMessage());
            }
        });
        broadcast(PendingAreaPayload.NONE);
    }

    /** The world opens: what was left the last time, and a selection's Resume. */
    private static synchronized void load(MinecraftServer s) {
        server = s;
        area = null;
        PregenTask.offerResume(null, null);
        if (!(s.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
        Path dir = dir();
        try {
            if (!Files.exists(dir.resolve("pending.json")) || !Files.exists(dir.resolve("pending-area.json"))) return;
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("pending.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            ChunkSelection saved = ChunkSelection.fromJson(Files.readString(dir.resolve("pending-area.json"), StandardCharsets.UTF_8));
            if (saved.isEmpty()) return;
            area = saved;
            label = meta.get("label").getAsString();
            kind = meta.get("kind").getAsString();
            skipSea = meta.has("skipSea") ? meta.get("skipSea").getAsBoolean() : null;
            doneRow = meta.get("doneRow").getAsInt();
            tooBig = !AllowedAreaPayload.fits(saved);
            if (SELECTION.equals(kind)) {
                Boolean sea = skipSea;
                PregenTask.offerResume(label, level -> PregenTask.startSelection(level, saved, sea));
            }
            System.out.printf(java.util.Locale.ROOT, "[orbis] Pre-generation left unfinished: %s (%,d chunks, rows after %d to go)%n", label, saved.count(), doneRow);
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not read the pending pre-generation: " + e.getMessage());
            area = null;
        }
    }

    private static Path dir() {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen");
    }

    private static void writeMeta() {
        Path dir = dir();
        JsonObject meta = new JsonObject();
        meta.addProperty("label", label);
        meta.addProperty("kind", kind);
        if (skipSea != null) meta.addProperty("skipSea", skipSea);
        meta.addProperty("doneRow", doneRow);
        meta.addProperty("firstRow", area.firstRow());
        meta.addProperty("lastRow", area.lastRow());
        WRITER.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pending.json"), meta + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[orbis] Could not save the pending pre-generation: " + e.getMessage());
            }
        });
    }

    private static PendingAreaPayload full() {
        return area == null || tooBig ? PendingAreaPayload.NONE : new PendingAreaPayload(doneRow, area);
    }

    private static void sendTo(ServerPlayer player, PendingAreaPayload payload) {
        if (ServerPlayNetworking.canSend(player, PendingAreaPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }

    private static void broadcast(PendingAreaPayload payload) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) sendTo(p, payload);
        });
    }
}
