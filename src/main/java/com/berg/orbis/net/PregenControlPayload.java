package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A button on the world map that runs the pre-generation (operators only, checked by the server): stop, resume, hard limit on or off. */
public record PregenControlPayload(Action action) implements CustomPacketPayload {
    public enum Action { STOP, RESUME, LIMIT_ON, LIMIT_OFF }

    public static final Type<PregenControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenControlPayload> CODEC = CustomPacketPayload.codec(PregenControlPayload::write,
            PregenControlPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
    }

    private static PregenControlPayload read(RegistryFriendlyByteBuf buf) {
        int i = buf.readVarInt();
        if (i < 0 || i >= Action.values().length) throw new IllegalArgumentException("unknown action " + i);
        return new PregenControlPayload(Action.values()[i]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
