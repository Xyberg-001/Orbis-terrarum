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
    /** The world map key (N by default; Controls > Orbis Terrarum). */
    public static net.minecraft.client.KeyMapping MAP_KEY;
    /** Where the current world sits on Earth, from the server (null when it does not run Orbis Terrarum). */
    public static volatile com.berg.orbis.net.WorldInfoPayload worldInfo;
    /** The hard limit of the server, for shading the world map (null: none, or the server has no Orbis). */
    public static volatile com.berg.orbis.net.AllowedAreaPayload allowedArea;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(SpawnGate::tick);
        OpenMapCommand.register();
        net.minecraft.client.KeyMapping.Category category = net.minecraft.client.KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("orbisterrarum", "keys"));
        MAP_KEY = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
                new net.minecraft.client.KeyMapping("key.orbisterrarum.map", com.mojang.blaze3d.platform.InputConstants.KEY_N, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MAP_KEY.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) mc.setScreenAndShow(new com.berg.orbis.client.map.WorldMapScreen(worldInfo));
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.WorldInfoPayload.TYPE,
                (payload, context) -> worldInfo = payload);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.AllowedAreaPayload.TYPE,
                (payload, context) -> allowedArea = payload);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.berg.orbis.net.MapTilePayloads.Data.TYPE,
                (payload, context) -> context.client().execute(() -> com.berg.orbis.client.map.BlockMapClient.receive(context.client(), payload)));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            worldInfo = null;
            allowedArea = null;
            mc.execute(() -> com.berg.orbis.client.map.BlockMapClient.clear(mc));
        });
        System.out.println("[orbis] Client ready: world settings via Create New World > Customize"
                + (WorldSettingsScreens.yaclPresent() ? "" : " (install YetAnotherConfigLib for the settings screen)"));
    }
}
