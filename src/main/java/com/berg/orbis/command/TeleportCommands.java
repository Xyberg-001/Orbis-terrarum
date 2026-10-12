package com.berg.orbis.command;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.net.Geocoder;
import com.berg.orbis.worldgen.PregenTask;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.berg.orbis.worldgen.WorldModel;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * The commands with no world map equivalent (the map does the rest since 1.1.0): /tpll and /wherell (also /orbis tpll, /orbis where),
 * /orbis pregen (for server consoles), /orbis export aternos and /orbis settings. Map data from Geofabrik is Download map data on the
 * world map (and the offer when a world is created), at every scale.
 */
public class TeleportCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /tpll and /wherell stay as short aliases (the convention other Earth mods use).
            // Teleporting is for operators, like vanilla /tp.
            dispatcher.register(Commands.literal("tpll")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("target", StringArgumentType.greedyString())
                            .executes(TeleportCommands::teleportToTarget)));
            dispatcher.register(Commands.literal("wherell").executes(TeleportCommands::reportLatLon));

            dispatcher.register(Commands.literal("orbis")
                    // The mod's performance and network settings, for a server's operators (no settings screen there).
                    .then(Commands.literal("settings")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(ctx -> reply(ctx, setting(null, null)))
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(SERVER_SETTINGS.keySet(), b))
                                    .executes(ctx -> reply(ctx, setting(StringArgumentType.getString(ctx, "name"), null)))
                                    .then(Commands.argument("value", StringArgumentType.greedyString())
                                            .executes(ctx -> reply(ctx, setting(StringArgumentType.getString(ctx, "name"),
                                                    StringArgumentType.getString(ctx, "value")))))))
                    .then(Commands.literal("tpll")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.argument("target", StringArgumentType.greedyString())
                                    .executes(TeleportCommands::teleportToTarget)))
                    .then(Commands.literal("where").executes(TeleportCommands::reportLatLon))
                    .then(Commands.literal("export")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("aternos")
                                    .executes(ctx -> reply(ctx, com.berg.orbis.export.AternosExport.start(ctx.getSource().getServer(), false, false)))
                                    .then(Commands.literal("zip")
                                            .executes(ctx -> reply(ctx, com.berg.orbis.export.AternosExport.start(ctx.getSource().getServer(), false, true))))
                                    .then(Commands.literal("orbis")
                                            .executes(ctx -> reply(ctx, com.berg.orbis.export.AternosExport.start(ctx.getSource().getServer(), true, false)))
                                            .then(Commands.literal("zip")
                                                    .executes(ctx -> reply(ctx, com.berg.orbis.export.AternosExport.start(ctx.getSource().getServer(), true, true)))))))
                    .then(Commands.literal("pregen")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("stop").executes(ctx -> reply(ctx, PregenTask.stop())))
                            .then(Commands.literal("status").executes(ctx -> reply(ctx, PregenTask.status())))
                            .then(Commands.literal("area")
                                    .then(Commands.literal("all")
                                            .then(Commands.argument("place", StringArgumentType.greedyString())
                                                    .executes(ctx -> pregenArea(ctx, false))))
                                    .then(Commands.argument("place", StringArgumentType.greedyString())
                                            .executes(ctx -> pregenArea(ctx, true))))
                            .then(Commands.argument("radiusKm", DoubleArgumentType.doubleArg(0.1, 2000.0))
                                    .executes(TeleportCommands::pregenHere))
                            .then(Commands.literal("at")
                                    .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                            .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                                    .then(Commands.argument("radiusKm", DoubleArgumentType.doubleArg(0.1, 2000.0))
                                                            .executes(TeleportCommands::pregenAt)))))));
        });
    }

    /** What /orbis settings can show and change: the installation's performance and network settings. */
    private static final java.util.Map<String, String> SERVER_SETTINGS = new java.util.LinkedHashMap<>();

    static {
        String[][] s = {
                {"fastPregen", "build terrain ahead in memory during pre-generation"},
                {"fastPregenThreads", "threads building terrain ahead, 0 = half the processor's"},
                {"parallelChunkCompression", "compress chunks for saving on several threads"},
                {"pregenPauseWorld", "pause mobs, crops, redstone and time while pre-generating"},
                {"fastChunkWrites", "don't force every chunk to disk at once (restart)"},
                {"waitForOsm", "hold new chunks until their map data has arrived"},
                {"prefetchSpawnAtStartup", "download the map around the spawn when the server starts"},
                {"keepDownloadedMapFiles", "keep Geofabrik files after a map data download imported the area"},
                {"regionPrefetchRadius", "map regions downloaded ahead of players, 0-6"},
                {"displayFrameRange", "blocks within which shop display frames are sent to players (0 = vanilla's 160)"},
                {"regionCacheSize", "map regions kept in memory, 8-256 (restart)"},
                {"overpassConcurrentRequests", "parallel OpenStreetMap downloads, 1-8"},
                {"osmMaxWaitMinutes", "longest wait for a region's map data"},
                {"demTileCacheSize", "terrain tiles kept in memory (restart)"},
                {"imageryTileCacheSize", "photo tiles kept in memory (restart)"},
                {"imageryZoom", "finest photo zoom, 14-20"},
                {"debugLogging", "more detail in the server log"},
        };
        for (String[] e : s) SERVER_SETTINGS.put(e[0], e[1]);
    }

    /** Lists the settings (no name), shows one (no value), or changes and saves it. */
    private static Component setting(String name, String value) {
        com.berg.orbis.config.OrbisConfig cfg = OrbisMod.config();
        if (name == null) {
            StringBuilder sb = new StringBuilder("Orbis Terrarum settings (/orbis settings <name> <value>):");
            for (var e : SERVER_SETTINGS.entrySet()) sb.append("\n ").append(e.getKey()).append(" = ").append(read(cfg, e.getKey())).append("  - ").append(e.getValue());
            return Component.literal(sb.toString());
        }
        if (!SERVER_SETTINGS.containsKey(name)) return Component.literal("No setting called " + name + ". /orbis settings lists them.");
        if (value == null) return Component.literal(name + " = " + read(cfg, name) + "  - " + SERVER_SETTINGS.get(name));
        try {
            java.lang.reflect.Field f = com.berg.orbis.config.OrbisConfig.class.getField(name);
            String v = value.trim().toLowerCase(java.util.Locale.ROOT);
            Object parsed;
            if (f.getType() == boolean.class) {
                if (v.equals("true") || v.equals("on") || v.equals("yes")) parsed = true;
                else if (v.equals("false") || v.equals("off") || v.equals("no")) parsed = false;
                else return Component.literal(name + " takes true or false.");
            } else if (f.getType() == int.class) {
                parsed = Integer.parseInt(v);
            } else {
                parsed = Double.parseDouble(v);
            }
            OrbisMod.updateConfig(c -> {
                try {
                    f.set(c, parsed);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            });
            return Component.literal(name + " = " + read(OrbisMod.config(), name) + " (saved). Pre-generation settings apply to the next"
                    + " pre-generation, the others when the server next starts.");
        } catch (NumberFormatException e) {
            return Component.literal(name + " takes a number.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Component.literal("Could not change " + name + ": " + e);
        }
    }

    private static String read(com.berg.orbis.config.OrbisConfig cfg, String name) {
        try {
            return String.valueOf(com.berg.orbis.config.OrbisConfig.class.getField(name).get(cfg));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    /** /orbis pregen area [all] <place>: sweep a country/region/city outline north to south. */
    private static int pregenArea(CommandContext<CommandSourceStack> ctx, boolean mainlandOnly) {
        CommandSourceStack source = ctx.getSource();
        String place = StringArgumentType.getString(ctx, "place").trim();
        source.sendSuccess(() -> Component.literal("Looking up the outline of " + place + "..."), false);
        var server = source.getServer();
        var level = source.getLevel();
        Geocoder.lookupArea(place).whenComplete((outline, error) -> server.execute(() -> {
            if (error != null || outline == null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
                source.sendFailure(Component.literal("Could not get an outline for " + place + ": " + (cause != null ? cause.getMessage() : "no result")));
                return;
            }
            com.berg.orbis.net.AreaOutline chosen = mainlandOnly ? outline.largestOnly() : outline;
            String parts = outline.polygons().size() > 1
                    ? (mainlandOnly ? " (largest of " + outline.polygons().size() + " parts; use 'area all' for islands and overseas parts too)" : " (all " + outline.polygons().size() + " parts)")
                    : "";
            source.sendSuccess(() -> Component.literal("Outline: " + outline.name() + parts + ", " + chosen.vertexCount() + " vertices."), false);
            Component msg = PregenTask.startArea(level, chosen, mainlandOnly);
            source.sendSuccess(() -> msg, false);
        }));
        return 1;
    }

    private static int pregenHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double km = DoubleArgumentType.getDouble(ctx, "radiusKm");
        int x = (int) Math.floor(source.getPosition().x), z = (int) Math.floor(source.getPosition().z);
        return reply(ctx, PregenTask.startKm(source.getLevel(), x, z, km));
    }

    private static int pregenAt(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Orbis Terrarum isn't initialised."));
            return 0;
        }
        double km = DoubleArgumentType.getDouble(ctx, "radiusKm");
        int[] block = model.mapper().toBlock(DoubleArgumentType.getDouble(ctx, "lat"), DoubleArgumentType.getDouble(ctx, "lon"));
        return reply(ctx, PregenTask.startKm(source.getLevel(), block[0], block[1], km));
    }

    /**
     * /tpll 27.7172, 85.3240   or   /tpll Kathmandu   or   /tpll Eiffel Tower
     * Coordinates teleport at once; a name is resolved (Nominatim, Photon, then
     * an Overpass place lookup) and the teleport happens when the answer comes.
     */
    private static int teleportToTarget(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Orbis Terrarum isn't initialised."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only a player can teleport."));
            return 0;
        }
        String target = StringArgumentType.getString(ctx, "target").trim();
        java.util.concurrent.CompletableFuture<Geocoder.Result> lookup = Geocoder.lookup(target);
        if (lookup.isDone() && !lookup.isCompletedExceptionally()) {
            teleport(source, player, model, lookup.join());
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Looking up \"" + target + "\"..."), false);
        lookup.whenComplete((result, error) -> source.getServer().execute(() -> {
            if (error != null || result == null) {
                String why = error != null && error.getMessage() != null ? error.getMessage() : "no match";
                source.sendFailure(Component.literal("Could not find \"" + target + "\": " + why));
            } else {
                teleport(source, player, model, result);
            }
        }));
        return 1;
    }

    private static void teleport(CommandSourceStack source, ServerPlayer player, WorldModel model, Geocoder.Result target) {
        int[] block = model.mapper().toBlock(target.lat(), target.lon());
        int blockX = block[0], blockZ = block[1];
        net.minecraft.world.level.border.WorldBorder border = source.getLevel().getWorldBorder();
        if (!border.isWithinBounds(blockX, blockZ)) {
            // a cubic world's border is nearer than vanilla's (Cubic Worlds packs positions with more bits for height), so far places
            // can lie beyond it; a smaller scale (more metres a block) brings more of the Earth inside
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "%s (block %d, %d) is beyond the world border (x %.0f..%.0f, z %.0f..%.0f).%s", target.name(), blockX, blockZ,
                    border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ(), OrbisMod.cubicWorld()
                            ? " A cubic world reaches less far than a normal one; a world made at a smaller scale (more metres a block) holds more of the Earth."
                            : "")));
            return;
        }
        int surfaceY = source.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (surfaceY <= model.cfg().minY + 1) {
            // Chunk not generated yet: use the real elevation so we don't fall into the void.
            surfaceY = Math.max(model.terrainHeight(blockX, blockZ), model.cfg().waterLevelY()) + 1;
        }
        player.teleportTo(blockX + 0.5, surfaceY + 1, blockZ + 0.5);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Teleported to %s (%.5f, %.5f; block %d, %d)", target.name(), target.lat(), target.lon(), blockX, blockZ)), false);
    }

    private static int reportLatLon(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Orbis Terrarum isn't initialised."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only a player can use /wherell."));
            return 0;
        }
        double[] ll = model.mapper().toLatLonExact(player.getX(), player.getZ());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "You are at %.5f, %.5f", ll[0], ll[1])), false);
        return 1;
    }
}
