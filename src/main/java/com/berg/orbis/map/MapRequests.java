package com.berg.orbis.map;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.net.MapAnswerPayload;
import com.berg.orbis.net.MapRequestPayload;
import com.berg.orbis.osm.extract.MapDataJob;
import com.berg.orbis.sky.SkySwitches;
import com.berg.orbis.worldgen.HardLimit;
import com.berg.orbis.worldgen.Landmarks;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world map's questions to the server, and its World window's switches (the commands they replace in brackets):
 * <ul>
 * <li>{@code here}: what is at a place (/orbis here), for the What's here card</li>
 * <li>{@code about}: the world's facts (/orbis info), its real sky switches (/orbis sky), the hard limit, the Blocks layer's drawing and
 * the map data download, for the World window</li>
 * <li>{@code landmarks}: the landmark advancements as pins, and which ones this player has found</li>
 * <li>operators only: {@code sky} (/orbis daylight on ...), {@code drawall} (/orbis map render), {@code mapdata} and
 * {@code mapdata-stop} (/orbis mapdata, /orbis prefetch)</li>
 * </ul>
 * A change is answered with a "msg" line for the map's message line, then a fresh "about".
 */
public final class MapRequests {
    private static final Map<UUID, Long> lastHere = new ConcurrentHashMap<>();
    /** The map data download started from a map: its last progress line and fraction (-1: unknown), for every World window. */
    private static volatile String mapDataLine = "";
    private static volatile float mapDataFraction = -1;

    private MapRequests() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(MapRequestPayload.TYPE, MapRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapAnswerPayload.TYPE, MapAnswerPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapRequestPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> handle(ctx.player(), payload)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            lastHere.clear();
            mapDataLine = "";
            mapDataFraction = -1;
        });
    }

    private static boolean orbis(MinecraftServer server) {
        return server.overworld() != null && server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator
                && OrbisMod.model() != null;
    }

    private static boolean op(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }

    private static void answer(ServerPlayer player, String kind, String id, List<String> lines) {
        if (ServerPlayNetworking.canSend(player, MapAnswerPayload.TYPE)) ServerPlayNetworking.send(player, new MapAnswerPayload(kind, id, lines));
    }

    private static void say(ServerPlayer player, Component msg) {
        answer(player, "msg", "", List.of(msg.getString()));
    }

    private static void handle(ServerPlayer player, MapRequestPayload req) {
        MinecraftServer server = player.level().getServer();
        if (!orbis(server)) {
            say(player, Component.literal("This world is not an Orbis Terrarum world."));
            return;
        }
        switch (req.kind()) {
            case "here" -> here(player, req);
            case "about" -> answer(player, "about", req.arg(), about(server, player));
            case "landmarks" -> answer(player, "landmarks", req.arg(), landmarks(server, player));
            case "sky", "drawall", "mapdata", "mapdata-stop" -> {
                if (!op(player)) {
                    say(player, Component.literal("Only operators can change this world."));
                    return;
                }
                Component reply = switch (req.kind()) {
                    case "sky" -> sky(server, req.arg());
                    case "drawall" -> BlockMapService.renderAll(server);
                    case "mapdata" -> mapData(server, player, req);
                    default -> {
                        MapDataJob.stop();
                        yield Component.literal("Stopping the map data download (it carries on next time).");
                    }
                };
                System.out.println("[orbis] " + player.getName().getString() + " used " + req.kind() + " " + req.arg() + " on the world map");
                say(player, reply);
                answer(player, "about", "", about(server, player));
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ what's here

    private static void here(ServerPlayer player, MapRequestPayload req) {
        long now = System.currentTimeMillis();
        Long last = lastHere.get(player.getUUID());
        if (last != null && now - last < 250) return; // a burst of clicks: the map shows the last answer it gets anyway
        lastHere.put(player.getUUID(), now);
        WorldModel model = OrbisMod.model();
        MinecraftServer server = player.level().getServer();
        int[] b = model.mapper().toBlock(req.lat(), req.lon());
        // Off the server thread: a place far away can need terrain tiles fetched first.
        CompletableFuture.supplyAsync(() -> SpotInfo.describe(model, b[0], b[1], false), net.minecraft.util.Util.backgroundExecutor())
                .whenComplete((lines, error) -> server.execute(() -> answer(player, "here", req.arg(),
                        error != null ? List.of("Error\t" + error.getMessage()) : lines)));
    }

    // ------------------------------------------------------------------ about

    private static List<String> about(MinecraftServer server, ServerPlayer player) {
        WorldModel model = OrbisMod.model();
        var cfg = model.cfg();
        var border = server.overworld().getWorldBorder();
        List<String> out = new ArrayList<>();
        out.add("op\t" + (op(player) ? "1" : "0"));
        out.add("info\tCentre\t" + String.format(Locale.ROOT, "%.5f, %.5f (block 0, 0)", cfg.originLat, cfg.originLon));
        out.add("info\tScale\t" + String.format(Locale.ROOT, "1 block = %s m", trim(cfg.metersPerBlock)));
        out.add("info\tHeight\t" + String.format(Locale.ROOT, "Y %,d to %,d, sea level at Y %d", cfg.minY, cfg.maxY(), cfg.seaLevelY));
        out.add("info\tCubic Chunks\t" + (OrbisMod.cubicWorld() ? "yes (tall world)" : "no"));
        double half = border.getSize() / 2;
        out.add("info\tWorld border\t" + String.format(Locale.ROOT, "%,.0f blocks each way (%,.0f km)", half, half * cfg.metersPerBlock / 1000));
        out.add("info\tMap data\t" + (model.regions() == null ? "off" : "OpenStreetMap"));
        out.add("info\tLandmarks\t" + Landmarks.pins(server).size() + " to find");
        out.addAll(SkySwitches.lines());
        out.add("limit\t" + (HardLimit.on() ? "1" : "0") + "\t" + HardLimit.allowedChunks());
        Component draw = BlockMapService.status();
        out.add("draw\t" + (BlockMapService.rendering() ? "1" : "0") + "\t" + draw.getString());
        out.add("mapdata\t" + (MapDataJob.busy() ? "1" : "0") + "\t" + mapDataFraction + "\t" + mapDataLine);
        return out;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ landmarks

    private static List<String> landmarks(MinecraftServer server, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        for (Landmarks.Pin p : Landmarks.pins(server)) {
            boolean done = false;
            var holder = server.getAdvancements().get(p.advancement());
            if (holder != null) done = player.getAdvancements().getOrStartProgress(holder).isDone();
            out.add(String.join("\t", p.name().replace('\t', ' '), String.valueOf(p.x()), String.valueOf(p.z()), done ? "1" : "0",
                    p.task().replace('\t', ' ')));
        }
        return out;
    }

    // ------------------------------------------------------------------ switches

    private static Component sky(MinecraftServer server, String arg) {
        String[] p = arg.split(":", 2);
        SkySwitches.Switch s = p.length == 2 ? SkySwitches.byWord(p[0]) : null;
        if (s == null) return Component.literal("Unknown switch " + arg + ".");
        return SkySwitches.set(server, s, p[1].equals("on"));
    }

    /**
     * Map data for an area: Geofabrik's file for the selection's box (or around the place asked for), downloaded and cut to it, as
     * /orbis mapdata does; where no single file holds it (or it is on disk already), the online map servers' regions around it are
     * fetched ahead instead, as /orbis prefetch did. The landmarks are rebuilt once new data is in.
     */
    private static Component mapData(MinecraftServer server, ServerPlayer player, MapRequestPayload req) {
        WorldModel model = OrbisMod.model();
        if (MapDataJob.busy()) return Component.literal("A map data download is running already.");
        double mpb = model.cfg().metersPerBlock;
        double[] box = parseBox(req.arg());
        if (box == null) box = MapDataJob.boxAround(req.lat(), req.lon(), mpb);
        double[] area = box;
        java.nio.file.Path data = OrbisMod.dataDir();
        UUID who = player.getUUID();
        mapDataLine = "Looking up map data...";
        mapDataFraction = -1;
        Thread t = new Thread(() -> {
            MapDataJob.Plan plan = null;
            String why = null;
            try {
                plan = MapDataJob.plan(data, area, mpb);
            } catch (Exception e) {
                why = e.getMessage();
            }
            if (plan == null) {
                mapDataLine = "";
                String note = mpb >= 8.0 ? "At 8 m per block or coarser a country's map file is imported with /orbis import; "
                        : why != null ? "The map data look-up failed (" + why + "); " : "Map data on disk covers this area already, or no single file holds it; ";
                server.execute(() -> tell(server, who, note + prefetch(model, area)));
                return;
            }
            MapDataJob.Plan chosen = plan;
            server.execute(() -> tell(server, who, String.format(Locale.ROOT,
                    "Downloading map data for %s (%s); only this area is kept. The world uses the online servers until it is ready.",
                    chosen.region().name(), chosen.sizeText())));
            MapDataJob.start(data, plan, OrbisMod.config().keepDownloadedMapFiles, new MapDataJob.Listener() {
                @Override
                public void progress(String line, float fraction) {
                    mapDataLine = line;
                    mapDataFraction = fraction;
                }

                @Override
                public void finished(String message, boolean ok) {
                    System.out.println("[orbis] " + message);
                    mapDataLine = "";
                    mapDataFraction = -1;
                    server.execute(() -> {
                        tell(server, who, message);
                        if (ok) Landmarks.rebuildSoon(server);
                    });
                }
            });
        }, "Orbis-map-data-plan");
        t.setDaemon(true);
        t.start();
        return Component.literal("Looking up map data for the area...");
    }

    /** The online servers' regions over the box, fetched ahead (at most 13 x 13 around its middle); the message to say. */
    private static String prefetch(WorldModel model, double[] box) {
        if (model.regions() == null) return "map data is off in this world.";
        int[] a = model.mapper().toBlock(box[0], box[1]), b = model.mapper().toBlock(box[2], box[3]);
        var regions = model.regions();
        int rx0 = regions.regionCoord(Math.min(a[0], b[0])), rx1 = regions.regionCoord(Math.max(a[0], b[0]));
        int rz0 = regions.regionCoord(Math.min(a[1], b[1])), rz1 = regions.regionCoord(Math.max(a[1], b[1]));
        int radius = Math.min(6, Math.max(rx1 - rx0, rz1 - rz0) / 2 + 1);
        int rx = Math.floorDiv(rx0 + rx1, 2), rz = Math.floorDiv(rz0 + rz1, 2);
        regions.prefetchAround(rx, rz, radius);
        return String.format(Locale.ROOT, "fetching the %d map regions around it from the online map servers instead.", (2 * radius + 1) * (2 * radius + 1));
    }

    private static void tell(MinecraftServer server, UUID who, String text) {
        ServerPlayer p = server.getPlayerList().getPlayer(who);
        if (p == null) return;
        p.sendSystemMessage(Component.literal("[Orbis Terrarum] " + text));
        say(p, Component.literal(text));
    }

    /** "south,west,north,east" in degrees, or null. */
    private static double[] parseBox(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            double[] b = new double[4];
            for (int i = 0; i < 4; i++) b[i] = Double.parseDouble(p[i].trim());
            if (b[0] >= b[2] || b[1] >= b[3] || b[0] < -90 || b[2] > 90 || b[1] < -180 || b[3] > 180) return null;
            return b;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
