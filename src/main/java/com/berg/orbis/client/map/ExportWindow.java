package com.berg.orbis.client.map;

import com.berg.orbis.net.MapRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Export for Aternos, opened from the World window: which server the upload is for (plain Minecraft, so friends' servers need nothing,
 * or one running Orbis Terrarum) and whether it must be a single zip, then Export. The export runs on the server (this game, which
 * hosts the world), writes into the game folder's orbis-exports and reports in chat.
 */
final class ExportWindow extends Screen {
    private static final int W = 300, PAD = 8;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0;

    private final WorldWindow parent;
    private boolean forOrbis, zipOnly;
    private Button server, zip;

    ExportWindow(WorldWindow parent) {
        super(Component.translatable("orbisterrarum.map.export.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - Math.min(W, width - 16)) / 2;
    }

    private int boxW() {
        return Math.min(W, width - 16);
    }

    @Override
    protected void init() {
        int x = left() + PAD, w = boxW() - 2 * PAD, y = 50;
        server = addRenderableWidget(Button.builder(Component.empty(), b -> {
            forOrbis = !forOrbis;
            label();
        }).bounds(x, y + 14, w, 20).build());
        zip = addRenderableWidget(Button.builder(Component.empty(), b -> {
            zipOnly = !zipOnly;
            label();
        }).bounds(x, y + 76, w, 20).build());
        int half = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.export.go"), b -> {
            if (ClientPlayNetworking.canSend(MapRequestPayload.TYPE)) {
                ClientPlayNetworking.send(new MapRequestPayload("export", (forOrbis ? "orbis" : "plain") + (zipOnly ? ",zip" : ""), 0, 0));
            }
            onClose();
        }).bounds(x, y + 138, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + half + 4, y + 138, half, 20).build());
        label();
    }

    private void label() {
        server.setMessage(Component.translatable(forOrbis ? "orbisterrarum.map.export.server.orbis" : "orbisterrarum.map.export.server.plain"));
        zip.setMessage(Component.translatable(zipOnly ? "orbisterrarum.map.export.zip.one" : "orbisterrarum.map.export.zip.split"));
    }

    private boolean returning;

    @Override
    public void onClose() {
        returning = true;
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        if (!returning) parent.releaseMap(); // closed some other way (a disconnect): the map's tiles go too
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        parent.extractBackground(g, -1, -1, partialTick);
        g.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x0 = left(), w = boxW(), y = 50, x = x0 + PAD, tw = w - 2 * PAD;
        g.fill(x0 - 1, y - 23, x0 + w + 1, y + 167, 0xFF4A5868);
        g.fill(x0, y - 22, x0 + w, y + 166, 0xF4141A20);
        g.text(font, title, x, y - 14, WHITE);
        g.text(font, Component.translatable("orbisterrarum.map.export.server"), x, y + 2, GREY);
        line(g, forOrbis ? "orbisterrarum.map.export.server.orbis.tip" : "orbisterrarum.map.export.server.plain.tip", x, y + 37, tw);
        g.text(font, Component.translatable("orbisterrarum.map.export.zip"), x, y + 64, GREY);
        line(g, zipOnly ? "orbisterrarum.map.export.zip.one.tip" : "orbisterrarum.map.export.zip.split.tip", x, y + 99, tw);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Up to two lines of explanation under a choice. */
    private void line(GuiGraphicsExtractor g, String key, int x, int y, int w) {
        List<FormattedCharSequence> lines = font.split(Component.translatable(key), w);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.text(font, lines.get(i), x, y + i * 10, GREY);
    }
}
