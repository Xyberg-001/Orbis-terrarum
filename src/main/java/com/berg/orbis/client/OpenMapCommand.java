package com.berg.orbis.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import com.berg.orbis.mc.McClient;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /orbismap}, a client-side command: opens the world's generated-area map ({@code orbis-pregen/map.html}
 * in the world folder) in the default browser. The "Open map" button in the map's chat messages runs it.
 *
 * <p>The server cannot do this itself: Minecraft refuses file links in chat sent by a server, and the file is on
 * the host's computer anyway. So the command runs in the player's own game and works for whoever hosts the
 * world in singleplayer (or opened it to LAN). It takes no path from the server: it opens only that one file of
 * the world it is running, so a server cannot make a player open anything else.
 */
public final class OpenMapCommand {
    public static final String NAME = "orbismap";

    private OpenMapCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(ClientCommands.literal(NAME).executes(ctx -> open(ctx.getSource()))));
    }

    private static int open(FabricClientCommandSource source) {
        Minecraft mc = source.getClient();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            source.sendError(Component.literal("The map is in the world folder on the host's computer; only the player hosting the world can open it from here."));
            return 0;
        }
        Path page = server.getWorldPath(LevelResource.ROOT).resolve("orbis-pregen").resolve("map.html").toAbsolutePath().normalize();
        if (!Files.exists(page)) {
            source.sendError(Component.literal("No map yet: run /orbis pregen map first."));
            return 0;
        }
        McClient.openPath(page);
        source.sendFeedback(Component.literal("Opening the map in your browser. The world map (B) shows the same live: the haze is what is not generated yet."
                + " This command goes in the next release.").withStyle(net.minecraft.ChatFormatting.GRAY));
        return 1;
    }
}
