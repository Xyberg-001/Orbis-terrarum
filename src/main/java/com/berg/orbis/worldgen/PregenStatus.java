package com.berg.orbis.worldgen;

import com.berg.orbis.net.PregenControlPayload;
import com.berg.orbis.net.PregenStatusPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The pre-generation on the world map: its progress sent to players with the mod once a second while a sweep runs (and once as it ends),
 * and the map's Stop, Resume and hard-limit buttons carried out (operators only, as the /orbis pregen and /orbis hardlimit commands).
 */
public final class PregenStatus {
    private static PregenStatusPayload lastSent = PregenStatusPayload.IDLE;
    private static int ticks;

    private PregenStatus() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PregenStatusPayload.TYPE, PregenStatusPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PregenControlPayload.TYPE, PregenControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PregenControlPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> control(ctx.player(), payload.action())));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 20 == 0) broadcast(server, false);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (ServerPlayNetworking.canSend(handler.player, PregenStatusPayload.TYPE)) ServerPlayNetworking.send(handler.player, current(server));
        }));
    }

    private static boolean orbis(MinecraftServer server) {
        return server.overworld() != null && server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator;
    }

    private static PregenStatusPayload current(MinecraftServer server) {
        if (!orbis(server)) return PregenStatusPayload.IDLE;
        PregenTask.Snapshot s = PregenTask.snapshot();
        if (s != null) {
            return new PregenStatusPayload(true, s.label(), s.fraction(), s.detail(), s.waiting() == null ? "" : s.waiting(), "");
        }
        String resumable = PregenTask.resumable();
        if (resumable == null && AutoPregen.paused(server)) resumable = "the area chosen when the world was created";
        return resumable == null ? PregenStatusPayload.IDLE : new PregenStatusPayload(false, "", 0f, "", "", resumable);
    }

    /** Sends the progress to every player with the mod: each second while a sweep runs, else when it changed ({@code now}: at once). */
    private static void broadcast(MinecraftServer server, boolean now) {
        PregenStatusPayload status = current(server);
        if (!status.running() && status.equals(lastSent) && !now) return;
        lastSent = status;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(p, PregenStatusPayload.TYPE)) ServerPlayNetworking.send(p, status);
        }
    }

    private static void control(ServerPlayer player, PregenControlPayload.Action action) {
        MinecraftServer server = player.level().getServer();
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
            player.sendSystemMessage(Component.literal("Only operators can run the pre-generation."));
            return;
        }
        if (!orbis(server)) {
            player.sendSystemMessage(Component.literal("This world is not an Orbis Terrarum world."));
            return;
        }
        ServerLevel level = server.overworld();
        Component reply = switch (action) {
            case STOP -> PregenTask.stop();
            case RESUME -> PregenTask.resumable() != null ? PregenTask.resume(level) : AutoPregen.resume(server);
            case LIMIT_ON -> HardLimit.set(server, true);
            case LIMIT_OFF -> HardLimit.set(server, false);
        };
        System.out.println("[orbis] " + player.getName().getString() + " pressed " + action + " on the world map");
        player.sendSystemMessage(reply);
        broadcast(server, true);
    }
}
