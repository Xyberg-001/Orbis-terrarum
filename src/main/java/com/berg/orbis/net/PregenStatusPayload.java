package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server's pre-generation, for the world map's progress bar: whether one runs, what it is, how far it has got (0 to 1), a line of
 * detail and why it waits; or, when none runs, the name of a stopped one that can be resumed ("" when there is none).
 */
public record PregenStatusPayload(boolean running, String label, float fraction, String detail, String waiting, String resumable)
        implements CustomPacketPayload {
    public static final Type<PregenStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenStatusPayload> CODEC = CustomPacketPayload.codec(PregenStatusPayload::write,
            PregenStatusPayload::read);
    public static final PregenStatusPayload IDLE = new PregenStatusPayload(false, "", 0f, "", "", "");

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(running);
        buf.writeUtf(label, 256);
        buf.writeFloat(fraction);
        buf.writeUtf(detail, 512);
        buf.writeUtf(waiting, 256);
        buf.writeUtf(resumable, 256);
    }

    private static PregenStatusPayload read(RegistryFriendlyByteBuf buf) {
        return new PregenStatusPayload(buf.readBoolean(), buf.readUtf(256), buf.readFloat(), buf.readUtf(512), buf.readUtf(256), buf.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
