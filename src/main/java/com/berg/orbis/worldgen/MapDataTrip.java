package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.osm.extract.LocalExtractStore;
import com.berg.orbis.osm.extract.MapDataJob;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map data for a trip: when a player has been for a minute where no map data on disk covers the land (the map then
 * comes from the public Overpass servers, which can queue every region behind one working server: 50-190 s a region
 * in the Sahara, 18 Oct 2026), the people who may decide (the singleplayer host, or a server's operators) are offered
 * Geofabrik's file for the country in chat: [Download] [Not now] [Never for ...]. The 100 km square around the player
 * is kept (whole countries took gigabytes: see MapDataJob.planTrip), and the file stays on disk so the next square is a
 * short import with no new download. Once accepted, the next squares of that country start by themselves as the
 * player travels on. One download at a time; progress above the hotbar of whoever accepted it.
 * Off with {@code offerMapDownloads}; "Never" is remembered in {@code mapDataDeclined}. Fine scales only (finer than
 * 8 m per block): coarse worlds read the country map that the Create screen offers.
 */
public final class MapDataTrip {
    private static final long UNCOVERED_MS = 60_000, OFFER_AGAIN_MS = 10 * 60_000L, PROGRESS_EVERY_MS = 3_000;

    /** Regions declined for this game session ("Not now"). */
    private static final Set<String> NOT_NOW = ConcurrentHashMap.newKeySet();
    /** Regions accepted this session: their further squares start without asking. */
    private static final Set<String> ACCEPTED = ConcurrentHashMap.newKeySet();
    /** The plans on offer, by region id. */
    private static final Map<String, MapDataJob.Plan> OFFERED = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_OFFERED = new ConcurrentHashMap<>();
    /** Since when each player has been where no map data on disk covers the land (0: covered). */
    private static final Map<UUID, Long> UNCOVERED_SINCE = new HashMap<>();
    private static volatile boolean planning;
    private static int ticks;

    private MapDataTrip() {
    }

    /** Every server tick; looks every 5 s. */
    public static void tick(MinecraftServer server) {
        if (++ticks % 100 != 0) return;
        OrbisConfig cfg = OrbisMod.config();
        WorldModel m = OrbisMod.model();
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        if (cfg == null || !cfg.offerMapDownloads || m == null || m.regions() == null || m.cfg().metersPerBlock >= 8.0 || overworld == null
                || !(overworld.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) {
            UNCOVERED_SINCE.clear();
            return;
        }
        if (planning || MapDataJob.busy()) return;
        double mpb = m.cfg().metersPerBlock;
        Path data = OrbisMod.dataDir();
        LocalExtractStore store = LocalExtractStore.get(data.resolve("extracts"));
        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD) continue;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            if (HardLimit.blocks(x >> 4, z >> 4)) continue;
            double[] ll = m.mapper().toLatLon(x, z);
            if (covered(store, ll[0], ll[1], mpb)) {
                UNCOVERED_SINCE.remove(p.getUUID());
                continue;
            }
            long since = UNCOVERED_SINCE.computeIfAbsent(p.getUUID(), k -> now);
            if (now - since < UNCOVERED_MS) continue;
            UNCOVERED_SINCE.put(p.getUUID(), now); // looked at again in a minute (a "Not now" stays until the session ends)
            planning = true;
            UUID who = p.getUUID();
            double lat = ll[0], lon = ll[1];
            CompletableFuture.supplyAsync(() -> {
                try {
                    return MapDataJob.planTrip(data, lat, lon, mpb);
                } catch (Exception e) {
                    System.err.println("[orbis] Map data look-up for the trip failed: " + e);
                    return null;
                }
            }).whenComplete((plan, error) -> {
                planning = false;
                if (plan != null) server.execute(() -> offer(server, who, plan));
            });
            return; // one look-up at a time
        }
    }

    private static boolean covered(LocalExtractStore store, double lat, double lon, double mpb) {
        double d = 0.01;
        for (LocalExtractStore.Extract e : store.extracts()) {
            if (e.usableAt(mpb) && e.covers(lat - d, lon - d, lat + d, lon + d)) return true;
        }
        return false;
    }

    private static void offer(MinecraftServer server, UUID who, MapDataJob.Plan plan) {
        OrbisConfig cfg = OrbisMod.config();
        String id = plan.region().id();
        if (cfg.mapDataDeclined != null && cfg.mapDataDeclined.contains(id) || NOT_NOW.contains(id)) return;
        if (ACCEPTED.contains(id)) {
            start(server, who, plan);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_OFFERED.get(id);
        if (last != null && now - last < OFFER_AGAIN_MS) return;
        String place = plan.region().name();
        MutableComponent msg = Component.literal("[Orbis] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT,
                        "The map around you (%s) comes from the online map servers, which can be slow or down. "
                                + "Download its map file from Geofabrik (%s) and keep the 100 km around you on your disk? "
                                + "The file stays, so further on in %s only the next area is read from it, without asking. ",
                        place, plan.sizeText(), place))
                        .withStyle(ChatFormatting.WHITE))
                .append(button("[Download]", ChatFormatting.GREEN, "/orbis mapdata download " + id,
                        "Downloads in the background; the world keeps generating from the online servers until it is ready."))
                .append(" ")
                .append(button("[Not now]", ChatFormatting.GRAY, "/orbis mapdata notnow " + id, "Not asked again until the game restarts."))
                .append(" ")
                .append(button("[Never for " + place + "]", ChatFormatting.DARK_GRAY, "/orbis mapdata never " + id,
                        "Never asked again for " + place + " (offerMapDownloads turns all such offers off)."));
        boolean sent = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!mayDecide(p.createCommandSourceStack())) continue;
            p.sendSystemMessage(msg);
            sent = true;
        }
        if (sent) {
            OFFERED.put(id, plan);
            LAST_OFFERED.put(id, now);
        }
    }

    private static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(colour).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Who may download map data: a server's operators, or the singleplayer host (cheats on or not). */
    public static boolean mayDecide(CommandSourceStack src) {
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src)) return true;
        ServerPlayer p = src.getPlayer();
        return p != null && src.getServer().isSingleplayerOwner(p.nameAndId());
    }

    /** /orbis mapdata download|notnow|never <region>: the chat buttons. */
    public static Component command(CommandSourceStack src, String action, String id) {
        MinecraftServer server = src.getServer();
        switch (action) {
            case "download" -> {
                MapDataJob.Plan plan = OFFERED.remove(id);
                if (plan == null) return Component.literal("No map data download is on offer for " + id + " now.");
                if (MapDataJob.busy()) {
                    OFFERED.put(id, plan);
                    return Component.literal("Another map data download is running; try again when it is done.");
                }
                ACCEPTED.add(id);
                ServerPlayer p = src.getPlayer();
                start(server, p != null ? p.getUUID() : null, plan);
                return Component.literal("Downloading the map data for " + plan.region().name() + " in the background.");
            }
            case "notnow" -> {
                OFFERED.remove(id);
                NOT_NOW.add(id);
                return Component.literal("Not asked again for " + id + " until the game restarts.");
            }
            case "never" -> {
                OFFERED.remove(id);
                OrbisMod.updateConfig(c -> {
                    if (c.mapDataDeclined == null) c.mapDataDeclined = new java.util.ArrayList<>();
                    if (!c.mapDataDeclined.contains(id)) c.mapDataDeclined.add(id);
                });
                return Component.literal("Never asked again for " + id + " (setting mapDataDeclined).");
            }
            default -> {
                return Component.literal("Use download, notnow or never.");
            }
        }
    }

    private static void start(MinecraftServer server, UUID who, MapDataJob.Plan plan) {
        String place = plan.region().name();
        boolean keep = true; // the next square reuses the file
        long[] lastShown = {0};
        boolean started = MapDataJob.start(OrbisMod.dataDir(), plan, keep, new MapDataJob.Listener() {
            @Override
            public void progress(String line, float fraction) {
                long now = System.currentTimeMillis();
                if (now - lastShown[0] < PROGRESS_EVERY_MS) return;
                lastShown[0] = now;
                server.execute(() -> {
                    ServerPlayer p = who == null ? null : server.getPlayerList().getPlayer(who);
                    if (p != null) p.sendOverlayMessage(Component.literal("Map data for " + place + ": " + line).withStyle(ChatFormatting.GRAY));
                });
            }

            @Override
            public void finished(String message, boolean ok) {
                System.out.println("[orbis] " + message);
                server.execute(() -> {
                    ServerPlayer p = who == null ? null : server.getPlayerList().getPlayer(who);
                    if (p != null) p.sendSystemMessage(Component.literal("[Orbis] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(message).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
                });
            }
        });
        if (!started) System.out.println("[orbis] Map data for " + place + " not started: another download is running");
    }

    /** Forgets the session's answers and offers (the server stopped). */
    public static void reset() {
        NOT_NOW.clear();
        ACCEPTED.clear();
        OFFERED.clear();
        LAST_OFFERED.clear();
        UNCOVERED_SINCE.clear();
        planning = false;
    }
}
