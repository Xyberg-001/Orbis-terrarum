package com.berg.orbis.net;

import com.berg.orbis.worldgen.ChunkSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's pre-generations not finished yet, for the world map's list (worldgen.PregenTasks): each with its name, state (running,
 * queued, paused), chunks done of all, and the last chunk row done. An area comes the first time only (null afterwards: keep the one
 * sent before); an empty list means none is left.
 */
public record PregenTasksPayload(List<Entry> tasks) implements CustomPacketPayload {
    public static final Type<PregenTasksPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pregen_tasks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PregenTasksPayload> CODEC = CustomPacketPayload.codec(PregenTasksPayload::write,
            PregenTasksPayload::read);
    /** Running, queued, paused, waiting for its map data to download (PregenTasks.State's order). */
    public static final int RUNNING = 0, QUEUED = 1, PAUSED = 2, DOWNLOADING = 3;
    private static final int MAX_TASKS = 256;

    public record Entry(String id, String label, int state, long total, long done, int doneRow, ChunkSelection area) {
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(tasks.size());
        for (Entry e : tasks) {
            buf.writeUtf(e.id(), 64);
            buf.writeUtf(e.label(), 256);
            buf.writeVarInt(e.state());
            buf.writeVarLong(e.total());
            buf.writeVarLong(e.done());
            buf.writeVarInt(AllowedAreaPayload.zig(e.doneRow()));
            buf.writeBoolean(e.area() != null);
            if (e.area() != null) AllowedAreaPayload.writeArea(buf, e.area());
        }
    }

    private static PregenTasksPayload read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_TASKS) throw new IllegalArgumentException("too many pre-generations: " + n);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(64), label = buf.readUtf(256);
            int state = buf.readVarInt();
            long total = buf.readVarLong(), done = buf.readVarLong();
            int doneRow = AllowedAreaPayload.unzig(buf.readVarInt());
            ChunkSelection area = buf.readBoolean() ? AllowedAreaPayload.readArea(buf) : null;
            list.add(new Entry(id, label, state, total, done, doneRow, area));
        }
        return new PregenTasksPayload(list);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
