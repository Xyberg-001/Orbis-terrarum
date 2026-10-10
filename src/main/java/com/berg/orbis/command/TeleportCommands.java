package com.berg.orbis.command;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.net.Geocoder;
import com.berg.orbis.worldgen.PregenTask;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.berg.orbis.worldgen.WorldModel;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * /orbis tpll <place or lat, lon>   teleport to a place name or coordinate (/tpll is an alias)
 * /orbis where                      show your position as lat/lon (/wherell is an alias)
 * /orbis info           generator status (origin, caches, climate here)
 * /orbis here           what OSM/DEM know about the column you stand on
 * /orbis prefetch <r>   pre-download OSM regions within r regions of you
 * /orbis sky            the real sky switches of this world; /orbis daylight|weather|seasons|snow|clockhours on|off switches one
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

            var orbis = Commands.literal("orbis");
            // The world's real sky, switched while it runs (kept with the world, see SkySwitches); for operators.
            orbis.then(Commands.literal("sky").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> reply(ctx, com.berg.orbis.sky.SkySwitches.status())));
            for (com.berg.orbis.sky.SkySwitches.Switch s : com.berg.orbis.sky.SkySwitches.Switch.values()) {
                orbis.then(Commands.literal(s.word).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> reply(ctx, com.berg.orbis.sky.SkySwitches.status()))
                        .then(Commands.literal("on").executes(ctx -> reply(ctx, com.berg.orbis.sky.SkySwitches.set(ctx.getSource().getServer(), s, true))))
                        .then(Commands.literal("off").executes(ctx -> reply(ctx, com.berg.orbis.sky.SkySwitches.set(ctx.getSource().getServer(), s, false)))));
            }
            dispatcher.register(orbis
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
                    .then(Commands.literal("info").executes(TeleportCommands::info))
                    .then(Commands.literal("here").executes(TeleportCommands::here))
                    .then(Commands.literal("prefetch")
                            .then(Commands.argument("radius", IntegerArgumentType.integer(0, 6))
                                    .executes(TeleportCommands::prefetch)))
                    .then(Commands.literal("import")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.argument("file", StringArgumentType.greedyString())
                                    .executes(TeleportCommands::importExtract)))
                    .then(Commands.literal("extracts").executes(TeleportCommands::listExtracts))
                    .then(Commands.literal("mapdata")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(TeleportCommands::mapData)
                            .then(Commands.literal("stop").executes(ctx -> {
                                com.berg.orbis.osm.extract.MapDataJob.stop();
                                ctx.getSource().sendSuccess(() -> Component.literal("Stopping the map data download (it continues next time)."), false);
                                return 1;
                            })))
                    .then(Commands.literal("import-places")
                            .then(Commands.argument("file", StringArgumentType.greedyString())
                                    .executes(TeleportCommands::importPlaces)))
                    .then(Commands.literal("landmarks").executes(TeleportCommands::landmarks))
                    .then(Commands.literal("map")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("render").executes(ctx -> reply(ctx, com.berg.orbis.map.BlockMapService.renderAll(ctx.getSource().getServer()))))
                            .then(Commands.literal("status").executes(ctx -> reply(ctx, com.berg.orbis.map.BlockMapService.status()))))
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
                    .then(Commands.literal("hardlimit")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(ctx -> reply(ctx, com.berg.orbis.worldgen.HardLimit.status()))
                            .then(Commands.literal("on").executes(ctx -> reply(ctx, com.berg.orbis.worldgen.HardLimit.set(ctx.getSource().getServer(), true))))
                            .then(Commands.literal("off").executes(ctx -> reply(ctx, com.berg.orbis.worldgen.HardLimit.set(ctx.getSource().getServer(), false)))))
                    .then(Commands.literal("pregen")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("stop").executes(ctx -> reply(ctx, PregenTask.stop())))
                            .then(Commands.literal("resume").executes(ctx -> reply(ctx, com.berg.orbis.worldgen.AutoPregen.resume(ctx.getSource().getServer()))))
                            .then(Commands.literal("status").executes(ctx -> reply(ctx, PregenTask.status())))
                            .then(Commands.literal("map")
                                    .executes(ctx -> reply(ctx, com.berg.orbis.worldgen.PregenMap.writeNow(ctx.getSource().getLevel())))
                                    .then(Commands.argument("place", StringArgumentType.greedyString())
                                            .executes(TeleportCommands::pregenMapOf)))
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
                {"keepDownloadedMapFiles", "keep Geofabrik files after /orbis mapdata imported the area"},
                {"regionPrefetchRadius", "map regions downloaded ahead of players, 0-6"},
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

    /** /orbis pregen map <place>: the map with that place's outline drawn over the world. */
    private static int pregenMapOf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String place = StringArgumentType.getString(ctx, "place").trim();
        var server = source.getServer();
        var level = source.getLevel();
        source.sendSuccess(() -> Component.literal("Looking up the outline of " + place + "..."), false);
        Geocoder.lookupArea(place).whenComplete((outline, error) -> server.execute(() -> {
            if (error != null || outline == null) {
                source.sendFailure(Component.literal("Could not get an outline for " + place + "; writing the map without one."));
                source.sendSuccess(() -> com.berg.orbis.worldgen.PregenMap.writeNow(level), false);
                return;
            }
            var model = OrbisMod.model();
            var sweep = model == null ? null : com.berg.orbis.worldgen.AreaSweep.of(outline.largestOnly(), model.mapper());
            source.sendSuccess(() -> com.berg.orbis.worldgen.PregenMap.writeNow(level, sweep), false);
        }));
        return 1;
    }

    /**
     * /orbis mapdata: Geofabrik's file for the land around you (the smallest region that holds it), downloaded in the
     * background, only that area imported, the file deleted afterwards (unless keepDownloadedMapFiles). For servers,
     * which have no Create screen to offer it.
     */
    private static int mapData(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Not an Orbis Terrarum world."));
            return 0;
        }
        if (com.berg.orbis.osm.extract.MapDataJob.busy()) {
            source.sendFailure(Component.literal("A map data download is running already (/orbis mapdata stop stops it)."));
            return 0;
        }
        double mpb = model.cfg().metersPerBlock;
        if (mpb >= 8.0) {
            source.sendFailure(Component.literal("At 8 m per block or coarser, import a country's map file with /orbis import instead."));
            return 0;
        }
        var pos = source.getPosition();
        double[] ll = model.mapper().toLatLon((int) Math.floor(pos.x), (int) Math.floor(pos.z));
        double[] box = com.berg.orbis.osm.extract.MapDataJob.boxAround(ll[0], ll[1], mpb);
        var server = source.getServer();
        java.nio.file.Path data = OrbisMod.dataDir();
        source.sendSuccess(() -> Component.literal("Looking up map data for the land around you..."), false);
        Thread t = new Thread(() -> {
            com.berg.orbis.osm.extract.MapDataJob.Plan plan;
            try {
                plan = com.berg.orbis.osm.extract.MapDataJob.plan(data, box, mpb);
            } catch (Exception e) {
                server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Map data look-up failed: " + e.getMessage())));
                return;
            }
            if (plan == null) {
                server.execute(() -> source.sendSystemMessage(Component.literal(
                        "[Orbis Terrarum] Nothing to download: map data on disk covers this area already, or it crosses a border (no single region file holds it).")));
                return;
            }
            server.execute(() -> source.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "[Orbis Terrarum] Downloading map data for %s (%s); only this area is kept. The world uses the online servers until it is ready.",
                    plan.region().name(), plan.sizeText()))));
            long[] lastLine = {0};
            com.berg.orbis.osm.extract.MapDataJob.start(data, plan, OrbisMod.config().keepDownloadedMapFiles, new com.berg.orbis.osm.extract.MapDataJob.Listener() {
                @Override
                public void progress(String line, float fraction) {
                    long now = System.currentTimeMillis();
                    if (now - lastLine[0] < 30_000) return;
                    lastLine[0] = now;
                    server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Map data: " + line)));
                }

                @Override
                public void finished(String message, boolean ok) {
                    System.out.println("[orbis] " + message);
                    server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] " + message)));
                }
            });
        }, "Orbis-map-data-plan");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** /orbis import <file.osm.pbf>: turn a country extract into the local map-data store. */
    private static int importExtract(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String file = StringArgumentType.getString(ctx, "file").trim().replace("\"", "");
        java.nio.file.Path pbf = java.nio.file.Path.of(file);
        if (!java.nio.file.Files.isRegularFile(pbf)) {
            source.sendFailure(Component.literal("No such file: " + pbf.toAbsolutePath()));
            return 0;
        }
        java.nio.file.Path extracts = OrbisMod.dataDir().resolve("extracts");
        var server = source.getServer();
        final long freeMb = (Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())) >> 20;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Importing %s (%,d MB) in the background; %,d MB of heap free. A whole country needs 2-3 GB: if the game runs out of memory, run the importer outside the game instead (README, 'Country map worlds').",
                pbf.getFileName(), fileSizeMb(pbf), freeMb)), false);
        Thread t = new Thread(() -> {
            try {
                com.berg.orbis.osm.extract.ExtractImporter.Summary s = com.berg.orbis.osm.extract.ExtractImporter.importFile(
                        pbf, extracts, com.berg.orbis.osm.extract.ExtractImporter.Profile.MAP,
                        msg -> server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] " + msg))));
                com.berg.orbis.osm.extract.LocalExtractStore.get(extracts).rescan();
                server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Import finished. " + s.describe()
                        + " New worlds at 8 m per block or coarser use it automatically; a running world uses it for regions it has not loaded yet.")));
            } catch (Throwable e) {
                server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Import failed: " + e)));
            }
        }, "Orbis-Import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** /orbis import-places <file.geojson>: Overture Places into the places store, merged with OSM from now on. */
    private static int importPlaces(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String file = StringArgumentType.getString(ctx, "file").trim().replace("\"", "");
        java.nio.file.Path geojson = java.nio.file.Path.of(file);
        if (!java.nio.file.Files.isRegularFile(geojson)) {
            source.sendFailure(Component.literal("No such file: " + geojson.toAbsolutePath()));
            return 0;
        }
        java.nio.file.Path places = OrbisMod.dataDir().resolve("places");
        var server = source.getServer();
        source.sendSuccess(() -> Component.literal("Importing places from " + geojson.getFileName() + " in the background..."), false);
        Thread t = new Thread(() -> {
            try {
                com.berg.orbis.osm.PlacesImporter.Summary s = com.berg.orbis.osm.PlacesImporter.importFile(geojson, places,
                        msg -> server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] " + msg))));
                server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Places import finished. " + s.describe()
                        + " Regions loaded from now on include them; already generated chunks keep what they have.")));
            } catch (Throwable e) {
                server.execute(() -> source.sendSystemMessage(Component.literal("[Orbis Terrarum] Places import failed: " + e)));
            }
        }, "Orbis-Places");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static long fileSizeMb(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.size(p) >> 20;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /** Rebuilds the landmark advancement datapack of this world and reloads data packs. */
    private static int landmarks(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        try {
            int n = com.berg.orbis.worldgen.Landmarks.regenerate(server);
            if (n < 0) return reply(ctx, Component.literal("This is not an Orbis Terrarum world (or its map data is off)."));
            return reply(ctx, Component.literal("Landmark datapack rebuilt with " + n + " advancements; data packs are reloading."));
        } catch (Exception e) {
            return reply(ctx, Component.literal("Landmarks failed: " + e.getMessage()));
        }
    }

    private static int listExtracts(CommandContext<CommandSourceStack> ctx) {
        var store = com.berg.orbis.osm.extract.LocalExtractStore.get(OrbisMod.dataDir().resolve("extracts"));
        store.rescan();
        if (store.extracts().isEmpty()) {
            return reply(ctx, Component.literal("No local extracts in " + store.root() + ". Import one with /orbis import <file.osm.pbf>."));
        }
        StringBuilder sb = new StringBuilder("Local extracts:");
        for (var e : store.extracts()) sb.append("\n - ").append(e.describe());
        return reply(ctx, Component.literal(sb.toString()));
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
            // a cubic world's border is nearer than vanilla's (Cubic Chunks packs positions with more bits for height), so far places
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

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Orbis Terrarum isn't initialised."));
            return 0;
        }
        String regions = model.regions() == null ? "OSM disabled" : model.regions().stats();
        String msg = String.format(Locale.ROOT, "Orbis Terrarum origin %.5f, %.5f | 1 block = %.2f m | sea level Y=%d | %s | landmarks loaded: %d",
                model.cfg().originLat, model.cfg().originLon, model.cfg().metersPerBlock, model.cfg().seaLevelY, regions,
                model.landmarks() == null ? 0 : model.landmarks().placements().size());
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null) {
            source.sendFailure(Component.literal("Orbis Terrarum isn't initialised."));
            return 0;
        }
        int x = (int) Math.floor(source.getPosition().x), z = (int) Math.floor(source.getPosition().z);
        double[] ll = model.mapper().toLatLon(x, z);
        double elev = model.elevation(x, z);
        BiomeClassifier.Climate climate = model.climate(x, z, elev);
        StringBuilder sb = new StringBuilder();
        double shift = model.vertical().shiftBlocks(x, z);
        sb.append(String.format(Locale.ROOT, "%.5f, %.5f | elevation %.1f m -> Y %d%s | climate %s (%.1f C)%s",
                ll[0], ll[1], elev, Double.isNaN(elev) ? model.cfg().seaLevelY : model.blockY(elev, x, z),
                shift > 0 ? String.format(Locale.ROOT, " (relief shift -%.0f)", shift) : "",
                climate.zone(), climate.meanTempC(), climate.snowy() ? " snowy" : ""));
        RegionRaster r = model.regions() == null ? null : model.regions().get(model.regions().regionCoord(x), model.regions().regionCoord(z), false);
        if (r == null) {
            sb.append(" | OSM region not loaded");
        } else {
            int idx = r.index(x, z);
            if (idx >= 0) {
                sb.append(" | cover ").append(r.landCoverAt(idx));
                RoadFeature rf = r.roadAt(idx);
                if (rf != null) sb.append(" | road ").append(rf.highway != null ? rf.highway : rf.kind).append(rf.name != null ? " '" + rf.name + "'" : "");
                WaterFeature wf = r.waterAt(idx);
                if (wf != null) sb.append(" | water ").append(wf.kind).append(wf.name != null ? " '" + wf.name + "'" : "");
                if (r.hasCoastline && r.isSea(idx)) sb.append(" | sea");
                BuildingFeature bf = r.buildingAt(idx);
                if (bf != null) {
                    sb.append(" | building ").append(bf.type).append(bf.name != null ? " '" + bf.name + "'" : "")
                            .append(" h=").append(bf.heightBlocks).append(" (").append(bf.heightSource).append(") roof=").append(bf.roofShape)
                            .append('/').append(bf.roof.getBlock().getDescriptionId().replace("block.minecraft.", ""));
                }
                sb.append(" | decor ").append(r.decorAt(idx));
                sb.append(" | imagery ").append(com.berg.orbis.imagery.GroundClass.byCode(r.groundClassAt(idx)));
            }
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        // The place name arrives a moment later from the reverse geocoder.
        Geocoder.reverse(ll[0], ll[1]).whenComplete((name, error) -> source.getServer().execute(() -> {
            if (error == null && name != null) source.sendSuccess(() -> Component.literal("Place: " + name), false);
        }));
        return 1;
    }

    private static int prefetch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        WorldModel model = OrbisMod.model();
        if (model == null || model.regions() == null) {
            source.sendFailure(Component.literal("OSM streaming is not enabled."));
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int x = (int) Math.floor(source.getPosition().x), z = (int) Math.floor(source.getPosition().z);
        int rx = model.regions().regionCoord(x), rz = model.regions().regionCoord(z);
        model.regions().prefetchAround(rx, rz, radius);
        source.sendSuccess(() -> Component.literal("Prefetching OSM regions within " + radius + " of region " + rx + "," + rz
                + " (" + ((2 * radius + 1) * (2 * radius + 1) - 1) + " regions) in the background."), false);
        return 1;
    }
}
