package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Where the world sits on Earth, sent by the server to players who have Orbis Terrarum installed (for the world
 * map): the origin (block 0, 0), the scale and the projection. Players without the mod never get it (the server
 * checks that the client can receive it first), so they keep joining with plain Minecraft. {@code worldId} names
 * this world (not its name, which a new world can reuse), for the game's cache of the world map.
 */
public record WorldInfoPayload(double originLat, double originLon, double metersPerBlock, String projection, String worldId) implements CustomPacketPayload {
    public static final Type<WorldInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "world_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldInfoPayload> CODEC = CustomPacketPayload.codec(WorldInfoPayload::write, WorldInfoPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeDouble(originLat);
        buf.writeDouble(originLon);
        buf.writeDouble(metersPerBlock);
        buf.writeUtf(projection == null ? "equirectangular" : projection);
        buf.writeUtf(worldId == null ? "" : worldId);
    }

    private static WorldInfoPayload read(RegistryFriendlyByteBuf buf) {
        return new WorldInfoPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * The same facts for a server that does not run Orbis (an Aternos upload for a plain server): a chat type in the
     * world's data pack ({@code orbisterrarum:world_info}) whose translation key carries them. Chat types are sent to
     * every player as they join, by any server; plain Minecraft never uses this one, and Orbis reads it for its map.
     */
    public static final Identifier CHAT_TYPE = Identifier.fromNamespaceAndPath("orbisterrarum", "world_info");
    private static final String PREFIX = "orbisterrarum.world_info|1|";

    /** The translation key that carries a world's placement. */
    public static String encode(double originLat, double originLon, double metersPerBlock, String projection, String worldId) {
        return String.format(java.util.Locale.ROOT, "%s%.7f|%.7f|%s|%s|%s", PREFIX, originLat, originLon, Double.toString(metersPerBlock),
                projection == null ? "equirectangular" : projection, worldId == null ? "" : worldId);
    }

    /** The placement carried by a translation key, or null when it is not one. */
    public static WorldInfoPayload decode(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        String[] p = key.substring(PREFIX.length()).split("\\|", -1);
        if (p.length < 5) return null;
        try {
            return new WorldInfoPayload(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), p[3], p[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
