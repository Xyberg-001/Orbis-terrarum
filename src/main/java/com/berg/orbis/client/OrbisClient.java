package com.berg.orbis.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client side. The world settings are reached through vanilla's own
 * "Customize" button on the Create New World screen (see
 * {@code mixin.client.WorldCreationUiStateMixin}) and through Mod Menu; the
 * spawn-data gate ({@link SpawnGate}) needs a tick to poll download progress.
 */
public class OrbisClient implements ClientModInitializer {
    /** The world map key (B by default; Controls > Orbis Terrarum). */
    public static net.minecraft.client.KeyMapping MAP_KEY;
    /** Where the current world sits on Earth, from the server (null when it does not run Orbis Terrarum). */
    public static volatile com.berg.orbis.net.WorldInfoPayload worldInfo;
    /** The hard limit of the server, for shading the world map (null: none, or the server has no Orbis). */
    public static volatile com.berg.orbis.net.AllowedAreaPayload allowedArea;
    /** The server's pre-generation, for the world map's progress bar (idle when none runs or the server has no Orbis). */
    public static volatile com.berg.orbis.net.PregenStatusPayload pregenStatus = com.berg.orbis.net.PregenStatusPayload.IDLE;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(SpawnGate::tick);
        OpenMapCommand.register();
        net.minecraft.client.KeyMapping.Category category = net.minecraft.client.KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("orbisterrarum", "keys"));
        MAP_KEY = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
                new net.minecraft.client.KeyMapping("key.orbisterrarum.map", com.mojang.blaze3d.platform.InputConstants.KEY_B, category)); // B by default (N until 5 Oct 2026)
        com.berg.orbis.compat.voxy.VoxyCompat.init(); // optional: only acts when Voxy is installed
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MAP_KEY.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) mc.setScreenAndShow(new com.berg.orbis.client.map.WorldMapScreen(worldInfo));
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.WorldInfoPayload.TYPE,
                (payload, context) -> worldInfo = payload);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.AllowedAreaPayload.TYPE,
                (payload, context) -> allowedArea = payload);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.PregenStatusPayload.TYPE,
                (payload, context) -> pregenStatus = payload);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.MapAnswerPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof com.berg.orbis.client.map.MapAnswerSink sink) sink.answer(payload);
                }));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.MapTilePayloads.Data.TYPE,
                (payload, context) -> context.client().execute(() -> com.berg.orbis.client.map.BlockMapClient.receive(context.client(), payload)));
        // A server without Orbis (an Aternos upload for a plain server) sends no world info; its data pack's chat
        // type carries it instead. A server with Orbis sends the message after this and replaces it.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (worldInfo != null) return;
            try {
                // The uploaded area too: outside it the map shades what will never be real terrain (the plain
                // server's flat sea), as the hard limit does on a server with Orbis.
                net.minecraft.network.chat.ChatType areaType = handler.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.CHAT_TYPE)
                        .getValue(com.berg.orbis.net.AllowedAreaPayload.CHAT_TYPE);
                com.berg.orbis.net.AllowedAreaPayload area = areaType == null ? null
                        : com.berg.orbis.net.AllowedAreaPayload.decode(areaType.chat().translationKey());
                if (area != null && allowedArea == null) allowedArea = area;
            } catch (RuntimeException e) {
                System.err.println("[orbis] Map: could not read the uploaded area from the server's data: " + e);
            }
            try {
                net.minecraft.network.chat.ChatType type = handler.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.CHAT_TYPE)
                        .getValue(com.berg.orbis.net.WorldInfoPayload.CHAT_TYPE);
                com.berg.orbis.net.WorldInfoPayload info = type == null ? null : com.berg.orbis.net.WorldInfoPayload.decode(type.chat().translationKey());
                if (info != null) {
                    worldInfo = info;
                    System.out.println("[orbis] Map: this server's world sits at " + info.originLat() + ", " + info.originLon()
                            + " (from its data pack)");
                }
            } catch (RuntimeException e) {
                System.err.println("[orbis] Map: could not read the world's placement from the server's data: " + e);
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            worldInfo = null;
            allowedArea = null;
            pregenStatus = com.berg.orbis.net.PregenStatusPayload.IDLE;
            mc.execute(() -> com.berg.orbis.client.map.BlockMapClient.clear(mc));
        });
        System.out.println("[orbis] Client ready: world settings via Create New World > Customize"
                + (WorldSettingsScreens.yaclPresent() ? "" : " (install YetAnotherConfigLib for the settings screen)"));
    }
}
