package com.berg.orbis.net;

import com.berg.orbis.worldgen.ChunkSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The hard limit for the world map, sent by the server to players who have Orbis Terrarum installed: whether it is
 * on, and the chunks allowed to generate (the world's selection and every area pre-generated since), so the map can
 * shade what will never generate. Rows go as zigzag-encoded differences; an area with more runs than {@link #MAX_RUNS}
 * (far beyond any drawn selection) is sent as off rather than as a message too big for the connection.
 */
public record AllowedAreaPayload(boolean on, ChunkSelection area) implements CustomPacketPayload {
    public static final Type<AllowedAreaPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "allowed_area"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AllowedAreaPayload> CODEC = CustomPacketPayload.codec(AllowedAreaPayload::write, AllowedAreaPayload::read);
    public static final int MAX_RUNS = 150_000;

    /** The message for this area, or "off" when the limit is off or the area is too big to send. */
    public static AllowedAreaPayload of(boolean on, ChunkSelection area) {
        if (!on || area == null || area.isEmpty()) return new AllowedAreaPayload(false, new ChunkSelection());
        long runs = 0;
        for (int z = area.firstRow(); z <= area.lastRow(); z++) {
            int[] r = area.rawRuns(z);
            if (r != null) runs += r.length / 2;
        }
        return runs > MAX_RUNS ? new AllowedAreaPayload(false, new ChunkSelection()) : new AllowedAreaPayload(true, area);
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(on);
        int rows = 0;
        for (int z = area.firstRow(); z <= area.lastRow(); z++) if (area.rawRuns(z) != null) rows++;
        buf.writeVarInt(rows);
        int pz = 0;
        for (int z = area.firstRow(); z <= area.lastRow(); z++) {
            int[] r = area.rawRuns(z);
            if (r == null) continue;
            buf.writeVarInt(zig(z - pz));
            pz = z;
            buf.writeVarInt(r.length / 2);
            int px = 0;
            for (int v : r) {
                buf.writeVarInt(zig(v - px));
                px = v;
            }
        }
    }

    private static AllowedAreaPayload read(RegistryFriendlyByteBuf buf) {
        boolean on = buf.readBoolean();
        int rows = buf.readVarInt();
        if (rows < 0 || rows > MAX_RUNS) throw new IllegalArgumentException("too many rows: " + rows);
        ChunkSelection area = new ChunkSelection();
        int z = 0, total = 0;
        for (int k = 0; k < rows; k++) {
            z += unzig(buf.readVarInt());
            int n = buf.readVarInt();
            total += n;
            if (n < 0 || total > MAX_RUNS) throw new IllegalArgumentException("too many runs");
            int[] r = new int[n * 2];
            int px = 0;
            for (int i = 0; i < r.length; i++) {
                px += unzig(buf.readVarInt());
                r[i] = px;
            }
            area.addRow(z, r);
        }
        return new AllowedAreaPayload(on, area);
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
