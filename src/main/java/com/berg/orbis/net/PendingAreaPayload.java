package com.berg.orbis.net;

import com.berg.orbis.worldgen.ChunkSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The pre-generation not finished yet, for the world map's purple shading: its whole area and the last chunk row done (the sweep
 * goes north to south, so the rows after it are what is left). A sweep's progress only moves {@code doneRow}, so it comes without
 * the area ({@code area} null: keep the one sent before). An empty area: nothing is pending.
 */
public record PendingAreaPayload(int doneRow, ChunkSelection area) implements CustomPacketPayload {
    public static final Type<PendingAreaPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("orbisterrarum", "pending_area"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PendingAreaPayload> CODEC = CustomPacketPayload.codec(PendingAreaPayload::write,
            PendingAreaPayload::read);
    public static final PendingAreaPayload NONE = new PendingAreaPayload(0, new ChunkSelection());

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(AllowedAreaPayload.zig(doneRow));
        buf.writeBoolean(area != null);
        if (area != null) AllowedAreaPayload.writeArea(buf, area);
    }

    private static PendingAreaPayload read(RegistryFriendlyByteBuf buf) {
        int doneRow = AllowedAreaPayload.unzig(buf.readVarInt());
        return new PendingAreaPayload(doneRow, buf.readBoolean() ? AllowedAreaPayload.readArea(buf) : null);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
