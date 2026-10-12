package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server's running pre-generation, for the world map's list: whether one runs, which task it is (PregenTasks' id, "" for none), what it
 * is, how far it has got (0 to 1), a line of detail and why it waits.
 */
public record PregenStatusPayload(boolean running, String task, String label, float fraction, String detail, String waiting)
        implements CustomPacketPayload {
    public static final Type<PregenStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenStatusPayload> CODEC = CustomPacketPayload.codec(PregenStatusPayload::write,
            PregenStatusPayload::read);
    public static final PregenStatusPayload IDLE = new PregenStatusPayload(false, "", "", 0f, "", "");

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(running);
        buf.writeUtf(task, 64);
        buf.writeUtf(label, 256);
        buf.writeFloat(fraction);
        buf.writeUtf(detail, 512);
        buf.writeUtf(waiting, 256);
    }

    private static PregenStatusPayload read(RegistryFriendlyByteBuf buf) {
        return new PregenStatusPayload(buf.readBoolean(), buf.readUtf(64), buf.readUtf(256), buf.readFloat(), buf.readUtf(512), buf.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
