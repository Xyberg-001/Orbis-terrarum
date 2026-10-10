package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Something the world map asks of the server (see map/MapRequests): what is at a place, about the world, its landmarks, or (operators
 * only, checked by the server) a world switch to change. {@code arg} says which or carries a value; {@code lat, lon} a place when it needs one.
 */
public record MapRequestPayload(String kind, String arg, double lat, double lon) implements CustomPacketPayload {
    public static final Type<MapRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "map_question"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapRequestPayload> CODEC = CustomPacketPayload.codec(MapRequestPayload::write,
            MapRequestPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(kind, 32);
        buf.writeUtf(arg == null ? "" : arg, 256);
        buf.writeDouble(lat);
        buf.writeDouble(lon);
    }

    private static MapRequestPayload read(RegistryFriendlyByteBuf buf) {
        return new MapRequestPayload(buf.readUtf(32), buf.readUtf(256), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
