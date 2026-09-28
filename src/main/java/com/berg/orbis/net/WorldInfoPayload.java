package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Where the world sits on Earth, sent by the server to players who have Orbis Terrarum installed (for the world
 * map): the origin (block 0, 0), the scale and the projection. Players without the mod never get it (the server
 * checks that the client can receive it first), so they keep joining with plain Minecraft.
 */
public record WorldInfoPayload(double originLat, double originLon, double metersPerBlock, String projection) implements CustomPacketPayload {
    public static final Type<WorldInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "world_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldInfoPayload> CODEC = CustomPacketPayload.codec(WorldInfoPayload::write, WorldInfoPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeDouble(originLat);
        buf.writeDouble(originLon);
        buf.writeDouble(metersPerBlock);
        buf.writeUtf(projection == null ? "equirectangular" : projection);
    }

    private static WorldInfoPayload read(RegistryFriendlyByteBuf buf) {
        return new WorldInfoPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
