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
                    .then(Commands.literal("import-places")
                            .then(Commands.argument("file", StringArgumentType.greedyString())
                                    .executes(TeleportCommands::importPlaces)))
                    .then(Commands.literal("landmarks").executes(TeleportCommands::landmarks))
                    .then(Commands.literal("map")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("render").executes(ctx -> reply(ctx, com.berg.orbis.map.BlockMapService.renderAll(ctx.getSource().getServer()))))
                            .then(Commands.literal("status").executes(ctx -> reply(ctx, com.berg.orbis.map.BlockMapService.status()))))
                    .then(Commands.literal("pregen")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("stop").executes(ctx -> reply(ctx, PregenTask.stop())))
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

    /** /orbis import <file.osm.pbf>: turn a country extract into the local map-data store. */
    private static int importExtract(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String file = StringArgumentType.getString(ctx, "file").trim().replace("\"", "");
        java.nio.file.Path pbf = java.nio.file.Path.of(file);
        if (!java.nio.file.Files.isRegularFile(pbf)) {
            source.sendFailure(Component.literal("No such file: " + pbf.toAbsolutePath()));
            return 0;
        }
        java.nio.file.Path extracts = OrbisMod.configDir().resolve("extracts");
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
        java.nio.file.Path places = OrbisMod.configDir().resolve("places");
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
        var store = com.berg.orbis.osm.extract.LocalExtractStore.get(OrbisMod.configDir().resolve("extracts"));
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
        int surfaceY = source.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (surfaceY <= model.cfg().minY + 1) {
            // Chunk not generated yet: use the real elevation so we don't fall into the void.
            surfaceY = Math.max(model.terrainHeight(blockX, blockZ), model.cfg().seaLevelY) + 1;
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
