package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.osm.OsmRegionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Map around you": a bar for each player in an Orbis world while the map data around them is still coming in, so the
 * work behind live generation is visible as it is for a pre-generation (PregenTask's bar). It counts the map regions
 * the server keeps ready around the player ({@code regionPrefetchRadius}, the ones the hard limit leaves out not counted),
 * says what is being done (the downloads by kind, regions being prepared or waiting), and turns yellow when the
 * player's own region has been waiting for 20 s and red while terrain downloads keep failing (a dropped connection
 * left flat ground with no word to anyone). Shown only after 2 s of work, so a region that comes at once shows nothing;
 * "ready" stays 3 s, then the bar goes. Never during a pre-generation, which has its own bar. A vanilla boss bar: players
 * without the mod see it too. Off with {@code liveStatusBar}.
 */
public final class LiveStatus {
    private static final long SHOW_AFTER_MS = 2_000, READY_FOR_MS = 3_000, SLOW_AFTER_MS = 20_000;

    private static final class Bar {
        ServerBossEvent event;
        long busySince, readySince;
    }

    private static final Map<UUID, Bar> BARS = new HashMap<>();
    private static int ticks;

    private LiveStatus() {
    }

    /** Every server tick; works once a second. */
    public static void tick(MinecraftServer server) {
        if (++ticks % 20 != 0) return;
        WorldModel m = OrbisMod.model();
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        boolean on = OrbisMod.config() != null && OrbisMod.config().liveStatusBar && m != null && m.regions() != null && !PregenTask.isRunning()
                && overworld != null && overworld.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator;
        if (!on) {
            clear();
            return;
        }
        OsmRegionManager regions = m.regions();
        int radius = Math.max(0, m.cfg().regionPrefetchRadius), size = regions.regionSize();
        String downloads = PregenTask.downloadsNow();
        int queued = regions.queued(), preparing = regions.preparing();
        boolean working = queued > 0 || preparing > 0 || !downloads.isEmpty();
        boolean terrainFailing = RealWorldChunkGenerator.terrainWaiting() > 0 || regions.terrainRetries() > 0;
        long now = System.currentTimeMillis();
        Set<UUID> here = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD) continue;
            here.add(p.getUUID());
            int rx = regions.regionCoord((int) Math.floor(p.getX())), rz = regions.regionCoord((int) Math.floor(p.getZ()));
            int total = 0, ready = 0;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = rx + dx, nz = rz + dz;
                    if (!HardLimit.needed(nx * size, nz * size, nx * size + size - 1, nz * size + size - 1)) continue;
                    total++;
                    if (regions.isLoaded(nx, nz)) ready++;
                }
            }
            boolean busy = ready < total && working || terrainFailing;
            Bar b = BARS.computeIfAbsent(p.getUUID(), u -> new Bar());
            if (busy) {
                if (b.busySince == 0) b.busySince = now;
                b.readySince = 0;
            } else {
                b.busySince = 0;
                if (b.readySince == 0) b.readySince = now;
            }
            // "Ready" only when every region is (one given up on leaves the count short: the bar just goes).
            boolean show = busy ? now - b.busySince >= SHOW_AFTER_MS : b.event != null && ready == total && now - b.readySince < READY_FOR_MS;
            if (!show) {
                hide(b);
                continue;
            }
            if (b.event == null) {
                b.event = new ServerBossEvent(UUID.randomUUID(), Component.literal("Map around you"), BossEvent.BossBarColor.GREEN,
                        BossEvent.BossBarOverlay.PROGRESS);
            }
            if (!b.event.getPlayers().contains(p)) {
                b.event.removeAllPlayers(); // a respawn is a new player object
                b.event.addPlayer(p);
            }
            boolean ownWaiting = !regions.isLoaded(rx, rz) && HardLimit.needed(rx * size, rz * size, rx * size + size - 1, rz * size + size - 1);
            b.event.setName(Component.literal(busy ? text(ready, total, downloads, preparing, queued, terrainFailing) : "Map around you - ready"));
            b.event.setProgress(busy && total > 0 ? Math.min(1f, ready / (float) total) : 1f);
            b.event.setColor(terrainFailing ? BossEvent.BossBarColor.RED
                    : busy && ownWaiting && now - b.busySince >= SLOW_AFTER_MS ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN);
        }
        BARS.entrySet().removeIf(e -> {
            if (here.contains(e.getKey())) return false;
            hide(e.getValue());
            return true;
        });
    }

    /** "Map around you - 18 of 25 regions ready - downloading map data (2), terrain (3)". */
    static String text(int ready, int total, String downloads, int preparing, int queued, boolean terrainFailing) {
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "Map around you - %d of %d regions ready", ready, total));
        if (terrainFailing) s.append(" - terrain downloads failing, retrying (internet?)");
        else if (!downloads.isEmpty()) s.append(" - downloading ").append(downloads);
        else if (preparing > 0) s.append(" - preparing ").append(preparing).append(preparing == 1 ? " region" : " regions");
        else if (queued > 0) s.append(" - waiting for ").append(queued).append(queued == 1 ? " region" : " regions");
        return s.toString();
    }

    private static void hide(Bar b) {
        if (b.event != null) {
            b.event.removeAllPlayers();
            b.event = null;
        }
    }

    /** Takes every bar down (the world closed, a pre-generation started, or the bar switched off). */
    public static void clear() {
        for (Bar b : BARS.values()) hide(b);
        BARS.clear();
    }
}
