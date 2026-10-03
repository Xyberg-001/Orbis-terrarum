package com.berg.orbis.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A list to pick one entry from, with a search box (the preset locations, the world types). The settings screen's
 * own dropdown drew its list without a background, over the options below it, and could not be read.
 */
public final class PickScreen extends Screen {

    /** One entry: what it shows, and what picking it does (before the parent screen comes back). */
    public record Choice(Component label, Runnable onPick) {
    }

    private static final int ROW = 22, TOP = 52, BG = 0xF0101418, WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0;

    private final Screen parent;
    private final List<Choice> choices;
    private final List<Button> rows = new ArrayList<>();
    private List<Choice> shown = List.of();
    private EditBox search;
    private int scroll;

    public PickScreen(Screen parent, Component title, List<Choice> choices) {
        super(title);
        this.parent = parent;
        this.choices = List.copyOf(choices);
    }

    private int listWidth() {
        return Math.min(320, width - 40);
    }

    private int visibleRows() {
        return Math.max(1, (height - TOP - 34) / ROW);
    }

    @Override
    protected void init() {
        String query = search != null ? search.getValue() : "";
        int w = listWidth(), x = (width - w) / 2;
        search = new EditBox(font, x, 26, w, 18, Component.translatable("orbisterrarum.pick.search"));
        search.setValue(query);
        search.setHint(Component.translatable("orbisterrarum.pick.search"));
        search.setResponder(s -> {
            scroll = 0;
            layoutRows();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x, height - 26, w, 20).build());
        rows.clear();
        layoutRows();
    }

    /** The buttons for the entries matching the search, from the scroll position down. */
    private void layoutRows() {
        for (Button b : rows) removeWidget(b);
        rows.clear();
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        shown = choices.stream().filter(c -> q.isEmpty() || c.label().getString().toLowerCase(Locale.ROOT).contains(q)).toList();
        scroll = Math.max(0, Math.min(scroll, shown.size() - visibleRows()));
        int w = listWidth(), x = (width - w) / 2;
        for (int i = 0; i < visibleRows() && scroll + i < shown.size(); i++) {
            Choice c = shown.get(scroll + i);
            rows.add(addRenderableWidget(Button.builder(c.label(), b -> pick(c)).bounds(x, TOP + i * ROW, w, 20).build()));
        }
    }

    private void pick(Choice c) {
        c.onPick().run();
        onClose();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (sy != 0 && shown.size() > visibleRows()) {
            scroll -= (int) Math.signum(sy);
            layoutRows();
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        // Enter in the search box picks the first match.
        if ((e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) && search.isFocused() && !shown.isEmpty()) {
            pick(shown.get(0));
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, title, width / 2, 10, WHITE);
        if (shown.isEmpty()) g.centeredText(font, Component.translatable("orbisterrarum.pick.none"), width / 2, TOP + 6, GREY);
        if (scroll > 0) g.centeredText(font, "▲", width / 2, TOP - 10, GREY);
        if (scroll + visibleRows() < shown.size()) g.centeredText(font, "▼", width / 2, TOP + visibleRows() * ROW, GREY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
