package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The real temperature for rain or snow ({@link com.berg.orbis.sky.RealPrecipitation}), sent by the server to players
 * with Orbis installed whenever the real weather is applied, and as they join: the temperature at a block height and
 * the real metres one block of height stands for. NaN temperature: none (real weather off), the biomes decide again.
 */
public record RealTemperaturePayload(double tempC, double refY, double metresPerBlock) implements CustomPacketPayload {
    public static final Type<RealTemperaturePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "real_temperature"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealTemperaturePayload> CODEC = CustomPacketPayload.codec(RealTemperaturePayload::write, RealTemperaturePayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeDouble(tempC);
        buf.writeDouble(refY);
        buf.writeDouble(metresPerBlock);
    }

    private static RealTemperaturePayload read(RegistryFriendlyByteBuf buf) {
        return new RealTemperaturePayload(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
