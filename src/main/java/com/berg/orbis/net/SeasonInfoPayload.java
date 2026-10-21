package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Whether the world has real seasons, and how its block heights stand for real ones (sea level, metres per block of
 * height, the relief knee above which a relative-mode world lowers whole regions), sent as players with Orbis join: their
 * game then colours grass and leaves by the season of each place ({@link com.berg.orbis.client.LocalSeasons}) instead of
 * the world's one season in its data pack.
 */
public record SeasonInfoPayload(boolean on, int seaLevelY, double metresPerBlock, double reliefKneeMeters, boolean relative) implements CustomPacketPayload {
    public static final Type<SeasonInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "season_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeasonInfoPayload> CODEC = CustomPacketPayload.codec(SeasonInfoPayload::write, SeasonInfoPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(on);
        buf.writeInt(seaLevelY);
        buf.writeDouble(metresPerBlock);
        buf.writeDouble(reliefKneeMeters);
        buf.writeBoolean(relative);
    }

    private static SeasonInfoPayload read(RegistryFriendlyByteBuf buf) {
        return new SeasonInfoPayload(buf.readBoolean(), buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
