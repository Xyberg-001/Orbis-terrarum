package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A button on the world map that runs the pre-generation (operators only, checked by the server): stop (end and remove), resume or pause
 * the pre-generations listed by id (PregenTasks), or switch the hard limit on or off (no ids).
 */
public record PregenControlPayload(Action action, List<String> tasks) implements CustomPacketPayload {
    /** SOURCE: switch the pre-generations named between Live and Download map data. */
    public enum Action { STOP, RESUME, LIMIT_ON, LIMIT_OFF, PAUSE, SOURCE }

    private static final int MAX_TASKS = 256;

    public PregenControlPayload(Action action) {
        this(action, List.of());
    }

    public static final Type<PregenControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenControlPayload> CODEC = CustomPacketPayload.codec(PregenControlPayload::write,
            PregenControlPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
        buf.writeVarInt(tasks.size());
        for (String id : tasks) buf.writeUtf(id, 64);
    }

    private static PregenControlPayload read(RegistryFriendlyByteBuf buf) {
        int i = buf.readVarInt();
        if (i < 0 || i >= Action.values().length) throw new IllegalArgumentException("unknown action " + i);
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_TASKS) throw new IllegalArgumentException("too many pre-generations: " + n);
        List<String> ids = new ArrayList<>(n);
        for (int k = 0; k < n; k++) ids.add(buf.readUtf(64));
        return new PregenControlPayload(Action.values()[i], ids);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
