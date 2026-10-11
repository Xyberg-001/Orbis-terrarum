package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's answer to a {@link MapRequestPayload}: its kind ("here", "about", "landmarks", "msg"), an {@code id} the map gave the
 * question (so a late answer for a place no longer shown is dropped), and lines of tab-separated fields.
 */
public record MapAnswerPayload(String kind, String id, List<String> lines) implements CustomPacketPayload {
    public static final Type<MapAnswerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "map_answer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapAnswerPayload> CODEC = CustomPacketPayload.codec(MapAnswerPayload::write,
            MapAnswerPayload::read);
    private static final int MAX_LINES = 512;

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(kind, 32);
        buf.writeUtf(id == null ? "" : id, 256);
        int n = Math.min(MAX_LINES, lines.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(lines.get(i), 2048);
    }

    private static MapAnswerPayload read(RegistryFriendlyByteBuf buf) {
        String kind = buf.readUtf(32), id = buf.readUtf(256);
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LINES) throw new IllegalArgumentException("too many lines: " + n);
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(buf.readUtf(2048));
        return new MapAnswerPayload(kind, id, lines);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
