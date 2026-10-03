package com.berg.orbis.net;

import com.berg.orbis.worldgen.ChunkSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * "Generate this": the shapes an operator drew on the world map (block coordinates, in order, each added or cut
 * out). The server turns them into the same {@link ChunkSelection} the map showed and sweeps it. Vertices go as
 * zigzag-encoded differences, a few bytes each, since a message from the game to the server is capped at 32 KB.
 */
public record PregenSelectionPayload(List<ChunkSelection.Shape> shapes, boolean skipSea) implements CustomPacketPayload {
    public static final Type<PregenSelectionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenSelectionPayload> CODEC =
            CustomPacketPayload.codec(PregenSelectionPayload::write, PregenSelectionPayload::read);
    public static final int MAX_SHAPES = 128;
    public static final int MAX_VERTICES = 6000;

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(skipSea);
        buf.writeVarInt(shapes.size());
        for (ChunkSelection.Shape s : shapes) {
            buf.writeBoolean(s.subtract());
            int[] v = s.xz();
            buf.writeVarInt(v.length / 2);
            int px = 0, pz = 0;
            for (int i = 0; i + 1 < v.length; i += 2) {
                buf.writeVarInt(zig(v[i] - px));
                buf.writeVarInt(zig(v[i + 1] - pz));
                px = v[i];
                pz = v[i + 1];
            }
        }
    }

    private static PregenSelectionPayload read(RegistryFriendlyByteBuf buf) {
        boolean skipSea = buf.readBoolean();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_SHAPES) throw new IllegalArgumentException("too many shapes: " + n);
        List<ChunkSelection.Shape> shapes = new ArrayList<>(n);
        int total = 0;
        for (int k = 0; k < n; k++) {
            boolean subtract = buf.readBoolean();
            int m = buf.readVarInt();
            total += m;
            if (m < 0 || total > MAX_VERTICES) throw new IllegalArgumentException("too many vertices");
            int[] v = new int[m * 2];
            int px = 0, pz = 0;
            for (int i = 0; i < m; i++) {
                px += unzig(buf.readVarInt());
                pz += unzig(buf.readVarInt());
                v[2 * i] = px;
                v[2 * i + 1] = pz;
            }
            shapes.add(new ChunkSelection.Shape(subtract, v));
        }
        return new PregenSelectionPayload(shapes, skipSea);
    }

    private static int zig(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzig(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
