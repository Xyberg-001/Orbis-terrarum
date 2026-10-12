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
 * The pre-generation on the world map: the running sweep's progress sent to players with the mod once a second (and once as it ends),
 * and the map's Resume, Pause, Stop and hard-limit buttons carried out (operators only; the list itself is PregenTasks).
 */
public final class PregenStatus {
    private static PregenStatusPayload lastSent = PregenStatusPayload.IDLE;
    private static int ticks;

    private PregenStatus() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PregenStatusPayload.TYPE, PregenStatusPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PregenControlPayload.TYPE, PregenControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PregenControlPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> control(ctx.player(), payload)));
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
        if (s == null) return PregenStatusPayload.IDLE;
        PregenTasks.Task t = PregenTasks.running();
        return new PregenStatusPayload(true, t != null ? t.id : "", s.label(), s.fraction(), s.detail(), s.waiting() == null ? "" : s.waiting());
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

    private static void control(ServerPlayer player, PregenControlPayload payload) {
        PregenControlPayload.Action action = payload.action();
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
            case STOP -> PregenTasks.control(server, "stop", payload.tasks());
            case PAUSE -> PregenTasks.control(server, "pause", payload.tasks());
            case RESUME -> PregenTasks.control(server, "resume", payload.tasks());
            case LIMIT_ON -> HardLimit.set(server, true);
            case LIMIT_OFF -> HardLimit.set(server, false);
        };
        System.out.println("[orbis] " + player.getName().getString() + " pressed " + action + " on the world map");
        player.sendSystemMessage(reply);
        broadcast(server, true);
    }
}
