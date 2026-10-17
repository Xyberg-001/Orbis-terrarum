package com.berg.orbis.command;

import com.berg.orbis.OrbisMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * /orbis settings, the one command left: the performance and network settings for a server's operators (a server has no Mod Menu).
 * Everything else is on the world map since 1.1.0 (Teleport, What's here and the player's position, the World window's switches,
 * Download map data, Draw all, Export for Aternos, Select area, Generate, Stop and Resume).
 */
public class TeleportCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

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
                                                    StringArgumentType.getString(ctx, "value"))))))));
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
                {"liveStatusBar", "a 'Map around you' bar for each player while the map data around them comes in"},
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

}
