package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player's own weather (real weather on), sent to players with Orbis every two minutes: whether it rains and thunders
 * where they are, the temperature there with the block height it stands for and the real metres one block of height
 * is (for rain or snow), and the report's sources for the log. Their game draws it in place of the world's one weather
 * ({@link com.berg.orbis.client.LocalWeather}). {@code on} false: back to the world's weather.
 */
public record LocalWeatherPayload(boolean on, boolean rain, boolean thunder, double tempC, double refY, double metresPerBlock, String why)
        implements CustomPacketPayload {
    public static final Type<LocalWeatherPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "local_weather"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LocalWeatherPayload> CODEC = CustomPacketPayload.codec(LocalWeatherPayload::write, LocalWeatherPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(on);
        buf.writeBoolean(rain);
        buf.writeBoolean(thunder);
        buf.writeDouble(tempC);
        buf.writeDouble(refY);
        buf.writeDouble(metresPerBlock);
        buf.writeUtf(why == null ? "" : why, 200);
    }

    private static LocalWeatherPayload read(RegistryFriendlyByteBuf buf) {
        return new LocalWeatherPayload(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(200));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
