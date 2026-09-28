package com.berg.orbis.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The world map's Minecraft layer: the game asks for the regions on screen ({@link Request}: a zoom level, and per
 * region the version it already has), the server answers each with {@link Data}: which chunks are generated,
 * and the block colours when asked for and changed.
 */
public final class MapTilePayloads {
    private MapTilePayloads() {}

    /** Regions wanted: level = log2 of blocks per pixel (0..4); blocks = also the colours, not only the mask. */
    public record Request(int level, boolean blocks, int[] rx, int[] rz, int[] known) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "map_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(Request::write, Request::read);
        public static final int MAX = 32;

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(level);
            buf.writeBoolean(blocks);
            buf.writeVarInt(rx.length);
            for (int i = 0; i < rx.length; i++) {
                buf.writeVarInt(rx[i]);
                buf.writeVarInt(rz[i]);
                buf.writeVarInt(known[i]);
            }
        }

        private static Request read(RegistryFriendlyByteBuf buf) {
            int level = buf.readVarInt();
            boolean blocks = buf.readBoolean();
            int n = Math.min(MAX, Math.max(0, buf.readVarInt()));
            int[] rx = new int[n], rz = new int[n], known = new int[n];
            for (int i = 0; i < n; i++) {
                rx[i] = buf.readVarInt();
                rz[i] = buf.readVarInt();
                known[i] = buf.readVarInt();
            }
            return new Request(level, blocks, rx, rz, known);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One region: its generated chunks (bit z*32+x), and when {@code colours} is not empty the deflated colours
     * (one vanilla packed map colour per pixel, (512 >> level) squared). {@code version} lets the game keep what
     * it has; an empty {@code colours} with {@code sameColours} means "yours is current".
     */
    public record Data(int rx, int rz, int level, int version, long[] mask, boolean sameColours, byte[] colours) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "map_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = CustomPacketPayload.codec(Data::write, Data::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(rx);
            buf.writeVarInt(rz);
            buf.writeVarInt(level);
            buf.writeVarInt(version);
            for (int i = 0; i < 16; i++) buf.writeLong(mask[i]);
            buf.writeBoolean(sameColours);
            buf.writeByteArray(colours);
        }

        private static Data read(RegistryFriendlyByteBuf buf) {
            int rx = buf.readVarInt(), rz = buf.readVarInt(), level = buf.readVarInt(), version = buf.readVarInt();
            long[] mask = new long[16];
            for (int i = 0; i < 16; i++) mask[i] = buf.readLong();
            boolean same = buf.readBoolean();
            byte[] colours = buf.readByteArray(1 << 20);
            return new Data(rx, rz, level, version, mask, same, colours);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
