package com.berg.orbis.client.map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A small card on the world map, pinned to one place: a searched place, What's here, a landmark. A title, a few lines ("label\tvalue"
 * shows the label in grey) and up to three buttons; it shows only when asked for and goes with its x (or Esc), so the map stays clear.
 * It sits above its place, or below it near the top, and stays on screen when the place scrolls away.
 */
final class PlaceCard {
    /** A button on the card. */
    record Action(Component label, Component tip, Runnable run) {}

    private static final int PAD = 5, MAX_W = 230, MIN_W = 120;
    private final Font font;
    private final Button[] buttons = new Button[3];
    private final List<Action> actions = new ArrayList<>();

    double[] at;
    private String title = "";
    private List<String> lines = List.of();
    /** The card's box and its close cross as last drawn (GUI), null while hidden. */
    private int[] box, close;

    PlaceCard(Font font) {
        this.font = font;
    }

    /** Makes the buttons (again after a resize); the screen draws them over the card. */
    void addWidgets(Consumer<Button> add) {
        for (int i = 0; i < buttons.length; i++) {
            int n = i;
            buttons[i] = Button.builder(Component.empty(), b -> {
                if (n < actions.size()) actions.get(n).run().run();
            }).bounds(0, 0, 40, 20).build();
            add.accept(buttons[i]);
        }
        refreshButtons();
    }

    void show(double[] at, String title, List<String> lines, List<Action> actions) {
        this.at = at;
        set(title, lines, actions);
    }

    void set(String title, List<String> lines, List<Action> actions) {
        this.title = title == null ? "" : title;
        this.lines = new ArrayList<>(lines);
        this.actions.clear();
        this.actions.addAll(actions.subList(0, Math.min(actions.size(), buttons.length)));
        refreshButtons();
    }

    void hide() {
        at = null;
        box = close = null;
        actions.clear();
        refreshButtons();
    }

    boolean shown() {
        return at != null;
    }

    private void refreshButtons() {
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            if (b == null) continue;
            boolean on = at != null && i < actions.size();
            b.visible = on;
            if (!on) continue;
            Action a = actions.get(i);
            b.setMessage(a.label());
            b.setTooltip(a.tip() == null ? null : Tooltip.create(a.tip()));
            b.setWidth(Math.max(40, font.width(a.label()) + 12));
        }
    }

    /** True when a click lands on the card (its x closes it); the buttons have had their turn first. */
    boolean click(double x, double y) {
        if (box == null) return false;
        if (close != null && x >= close[0] && x < close[2] && y >= close[1] && y < close[3]) {
            hide();
            return true;
        }
        return x >= box[0] && x < box[2] && y >= box[1] && y < box[3];
    }

    /** Draws the card above its place within {@code top..bottom} of a {@code screenW} wide screen, and puts its buttons on it. */
    void draw(GuiGraphicsExtractor g, MapView canvas, int screenW, int top, int bottom) {
        box = close = null;
        if (at == null) return;
        int buttonsW = 0;
        for (int i = 0; i < actions.size(); i++) buttonsW += buttons[i].getWidth() + (i > 0 ? 4 : 0);
        int crossW = font.width("×") + 6;
        int want = font.width(title) + crossW;
        for (String l : lines) want = Math.max(want, font.width(l.replace("\t", ": ")));
        int w = Math.max(MIN_W, Math.min(Math.min(MAX_W, screenW - 8), Math.max(want, buttonsW) + 2 * PAD));
        List<FormattedCharSequence> rows = new ArrayList<>();
        for (String l : lines) rows.addAll(font.split(styled(l), w - 2 * PAD));
        if (rows.size() > 12) rows = rows.subList(0, 12);
        int h = PAD + 10 + 3 + rows.size() * 10 + (actions.isEmpty() ? 0 : 4 + 20) + PAD - 2;
        double[] a = canvas.gui(at[0], at[1]);
        int x = (int) Math.round(a[0]) - w / 2, y = (int) Math.round(a[1]) - 14 - h;
        if (y < top + 2) y = (int) Math.round(a[1]) + 8; // no room above: below the place
        x = Math.max(4, Math.min(screenW - 4 - w, x));
        y = Math.max(top + 2, Math.min(bottom - 2 - h, y));
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF4A5868);
        g.fill(x, y, x + w, y + h, 0xF0141A20);
        String t = title;
        int titleW = w - 2 * PAD - crossW;
        if (font.width(t) > titleW) t = font.plainSubstrByWidth(t, Math.max(0, titleW - font.width("..."))).stripTrailing() + "...";
        g.text(font, t, x + PAD, y + PAD, 0xFFFFFFFF);
        int cx = x + w - PAD - font.width("×");
        close = new int[]{cx - 3, y, x + w, y + PAD + 12};
        g.text(font, "×", cx, y + PAD, 0xFFB0B8C0);
        int ry = y + PAD + 13;
        for (FormattedCharSequence r : rows) {
            g.text(font, r, x + PAD, ry, 0xFFFFFFFF);
            ry += 10;
        }
        int bx = x + PAD, by = y + h - PAD + 2 - 20;
        for (int i = 0; i < actions.size(); i++) {
            buttons[i].setPosition(bx, by);
            bx += buttons[i].getWidth() + 4;
        }
        box = new int[]{x, y, x + w, y + h};
    }

    private static Component styled(String line) {
        int tab = line.indexOf('\t');
        if (tab < 0) return Component.literal(line).withStyle(ChatFormatting.GRAY);
        return Component.literal(line.substring(0, tab) + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(line.substring(tab + 1)).withStyle(ChatFormatting.WHITE));
    }
}
