package com.berg.orbis.map;

import com.berg.orbis.net.MapTilePayloads;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Server side of the world map's Minecraft layer: keeps {@link BlockMapStore} current from chunk loads and unloads,
 * answers the players' tile requests on two background threads, saves the map every minute and on shutdown, and
 * can draw every pre-generated region ahead of time ({@code /orbis map render}).
 */
public final class BlockMapService {
    private static volatile BlockMapStore store;
    private static ExecutorService workers;
    /** The one thread that draws whole regions for /orbis map render, so players' requests never wait behind it. */
    private static ExecutorService bulk;
    private static int ticks;
    private static final AtomicBoolean RENDERING = new AtomicBoolean();
    private static final AtomicInteger RENDER_DONE = new AtomicInteger(), RENDER_TOTAL = new AtomicInteger();

    private BlockMapService() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(MapTilePayloads.Request.TYPE, MapTilePayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapTilePayloads.Data.TYPE, MapTilePayloads.Data.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapTilePayloads.Request.TYPE, (payload, ctx) -> handle(ctx.player(), payload));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel overworld = server.overworld();
            if (!(overworld.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
            store = new BlockMapStore(overworld);
            workers = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Orbis-map");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            bulk = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Orbis-map-render");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BlockMapStore s = store;
            store = null;
            if (workers != null) workers.shutdownNow();
            if (bulk != null) bulk.shutdownNow();
            workers = null;
            bulk = null;
            RENDERING.set(false);
            if (s != null) s.flush();
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            BlockMapStore s = store;
            if (s != null && level.dimension() == Level.OVERWORLD) s.draw(chunk);
        });
        // Unloading chunks carry whatever players built since they loaded.
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            BlockMapStore s = store;
            if (s != null && level.dimension() == Level.OVERWORLD) s.draw(chunk);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 1200 != 0) return;
            BlockMapStore s = store;
            ExecutorService w = workers;
            if (s != null && w != null) w.execute(s::flush);
        });
    }

    private static void handle(ServerPlayer player, MapTilePayloads.Request req) {
        BlockMapStore s = store;
        ExecutorService w = workers;
        if (s == null || w == null) return;
        int level = Math.max(0, Math.min(4, req.level()));
        MinecraftServer server = player.level().getServer();
        for (int i = 0; i < req.rx().length; i++) {
            int rx = req.rx()[i], rz = req.rz()[i], known = req.known()[i];
            if (Math.abs(rx) > 60_000 || Math.abs(rz) > 60_000) continue;
            w.execute(() -> {
                try {
                    MapTilePayloads.Data data;
                    if (req.blocks()) {
                        BlockMapStore.Region r = s.complete(rx, rz);
                        int version = s.version(r);
                        long[] mask = s.fullMask(rx, rz);
                        data = version == known && known != 0
                                ? new MapTilePayloads.Data(rx, rz, level, version, mask, true, new byte[0])
                                : new MapTilePayloads.Data(rx, rz, level, version, mask, false, s.tile(r, level));
                    } else {
                        data = new MapTilePayloads.Data(rx, rz, level, 0, s.fullMask(rx, rz), true, new byte[0]);
                    }
                    MapTilePayloads.Data d = data;
                    server.execute(() -> {
                        if (!player.hasDisconnected()) ServerPlayNetworking.send(player, d);
                    });
                } catch (RuntimeException e) {
                    System.err.println("[orbis] Map tile " + rx + "," + rz + ": " + e);
                }
            });
        }
    }

    /**
     * This world's id: a random one made the first time it is asked for and kept in {@code <world>/orbis-map/world-id}
     * (a world's name can be reused by a new world, and a server can start over under the same address).
     */
    public static synchronized String worldId(MinecraftServer server) {
        Path f = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-map").resolve("world-id");
        try {
            if (Files.exists(f)) {
                String id = Files.readString(f).trim();
                if (!id.isEmpty()) return id;
            }
            String id = java.util.UUID.randomUUID().toString();
            Files.createDirectories(f.getParent());
            Files.writeString(f, id);
            return id;
        } catch (IOException e) {
            return "";
        }
    }

    /** Draws every region of the overworld that has not been drawn yet, in the background. */
    public static Component renderAll(MinecraftServer server) {
        BlockMapStore s = store;
        ExecutorService w = bulk;
        if (s == null || w == null) return Component.translatable("orbisterrarum.mapcmd.noworld");
        if (!RENDERING.compareAndSet(false, true)) return status();
        Path dir = com.berg.orbis.worldgen.PregenMap.regionDir(server.overworld());
        List<int[]> regions = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                String[] p = f.getFileName().toString().split("\\.");
                if (p.length == 4 && p[0].equals("r") && p[3].equals("mca")) {
                    try {
                        regions.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        } catch (IOException e) {
            RENDERING.set(false);
            return Component.literal("Could not list " + dir + ": " + e.getMessage());
        }
        RENDER_DONE.set(0);
        RENDER_TOTAL.set(regions.size());
        for (int[] r : regions) {
            w.execute(() -> {
                try {
                    s.complete(r[0], r[1]);
                } finally {
                    if (RENDER_DONE.incrementAndGet() >= RENDER_TOTAL.get()) {
                        RENDERING.set(false);
                        server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                                Component.translatable("orbisterrarum.mapcmd.done", RENDER_TOTAL.get()), false));
                    }
                }
            });
        }
        return Component.translatable("orbisterrarum.mapcmd.started", regions.size());
    }

    public static Component status() {
        if (store == null) return Component.translatable("orbisterrarum.mapcmd.noworld");
        if (!RENDERING.get()) return Component.translatable("orbisterrarum.mapcmd.idle");
        return Component.translatable("orbisterrarum.mapcmd.progress", RENDER_DONE.get(), RENDER_TOTAL.get());
    }
}
