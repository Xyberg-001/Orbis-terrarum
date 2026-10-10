package com.berg.orbis.client.map;

import com.berg.orbis.net.MapAnswerPayload;
import com.berg.orbis.net.MapRequestPayload;
import com.berg.orbis.net.PregenControlPayload;
import com.berg.orbis.net.WorldInfoPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The world map's World window (its i button): the world's facts on the left (/orbis info, /wherell), and on the right its real sky
 * switches (/orbis sky ...) and, for operators, the hard limit (/orbis hardlimit), Draw all for the Blocks layer (/orbis map render) and
 * Download map data (/orbis mapdata, /orbis prefetch). Shown over the dimmed map and gone with Esc, its x or a click beside it, so the
 * map itself keeps only its one button. Asks the server again every two seconds while open, for progress.
 */
public final class WorldWindow extends Screen implements MapAnswerSink {
    private static final int TOP_H = 24, PAD = 8, LABEL_W = 74;
    private static final String[] SKY = {"daylight", "weather", "seasons", "snow", "clockhours"};
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0;

    private final WorldMapScreen parent;
    private final WorldInfoPayload info;
    private boolean returning;
    private long nextAsk;

    // the server's answer
    private boolean answered, op;
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final Map<String, String[]> sky = new LinkedHashMap<>();
    private boolean limitOn, drawing, downloading;
    private long allowedChunks;
    private String drawText = "", downloadLine = "";
    private float downloadFraction = -1;
    private String message;
    private long messageUntil;

    private final Map<String, Button> skyButtons = new LinkedHashMap<>();
    private Button limit, draw, download;
    private int[] box, close;

    WorldWindow(WorldMapScreen parent, WorldInfoPayload info) {
        super(Component.translatable("orbisterrarum.map.world.title"));
        this.parent = parent;
        this.info = info;
    }

    private boolean canAsk() {
        return ClientPlayNetworking.canSend(MapRequestPayload.TYPE);
    }

    private void ask() {
        nextAsk = System.currentTimeMillis() + 2000;
        if (canAsk()) ClientPlayNetworking.send(new MapRequestPayload("about", "", 0, 0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        skyButtons.clear();
        for (String word : SKY) {
            skyButtons.put(word, addRenderableWidget(Button.builder(Component.empty(), b -> {
                String[] s = sky.get(word);
                if (s == null) return;
                ClientPlayNetworking.send(new MapRequestPayload("sky", word + ":" + (s[2].equals("1") ? "off" : "on"), 0, 0));
            }).bounds(0, 0, 60, 20).build()));
        }
        limit = addRenderableWidget(Button.builder(Component.empty(), b -> {
            ClientPlayNetworking.send(new PregenControlPayload(limitOn ? PregenControlPayload.Action.LIMIT_OFF : PregenControlPayload.Action.LIMIT_ON));
            nextAsk = System.currentTimeMillis() + 300;
        }).bounds(0, 0, 60, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.limit.tip"))).build());
        draw = addRenderableWidget(Button.builder(Component.empty(), b -> ClientPlayNetworking.send(new MapRequestPayload("drawall", "", 0, 0)))
                .bounds(0, 0, 60, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.world.draw.tip"))).build());
        download = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (downloading) {
                ClientPlayNetworking.send(new MapRequestPayload("mapdata-stop", "", 0, 0));
                return;
            }
            double[] sel = parent.selectionBox();
            double[] mid = parent.middle();
            String arg = sel == null ? "" : String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", sel[0], sel[1], sel[2], sel[3]);
            ClientPlayNetworking.send(new MapRequestPayload("mapdata", arg, mid[0], mid[1]));
        }).bounds(0, 0, 60, 20).build());
        layoutAndLabel();
        if (!answered) ask();
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() >= nextAsk) ask();
    }

    @Override
    public void answer(MapAnswerPayload p) {
        switch (p.kind()) {
            case "about" -> read(p.lines());
            case "msg" -> {
                if (!p.lines().isEmpty()) {
                    message = p.lines().get(0);
                    messageUntil = System.currentTimeMillis() + 8000;
                }
            }
            default -> parent.answer(p);
        }
    }

    private void read(List<String> lines) {
        answered = true;
        facts.clear();
        sky.clear();
        for (String l : lines) {
            String[] f = l.split("\t", -1);
            switch (f[0]) {
                case "op" -> op = f.length > 1 && f[1].equals("1");
                case "info" -> {
                    if (f.length >= 3) facts.put(f[1], f[2]);
                }
                case "sky" -> {
                    if (f.length >= 6) sky.put(f[1], f);
                }
                case "limit" -> {
                    limitOn = f.length > 1 && f[1].equals("1");
                    try {
                        allowedChunks = f.length > 2 ? Long.parseLong(f[2]) : 0;
                    } catch (NumberFormatException e) {
                        allowedChunks = 0;
                    }
                }
                case "draw" -> {
                    drawing = f.length > 1 && f[1].equals("1");
                    drawText = f.length > 2 ? f[2] : "";
                }
                case "mapdata" -> {
                    downloading = f.length > 1 && f[1].equals("1");
                    try {
                        downloadFraction = f.length > 2 ? Float.parseFloat(f[2]) : -1;
                    } catch (NumberFormatException e) {
                        downloadFraction = -1;
                    }
                    downloadLine = f.length > 3 ? f[3] : "";
                }
                default -> { }
            }
        }
        layoutAndLabel();
    }

    /** The facts the map knows itself (all of them on a server without Orbis, which cannot answer). */
    private Map<String, String> allFacts() {
        Map<String, String> out = new LinkedHashMap<>();
        if (facts.isEmpty() && info != null) {
            out.put(t("centre"), String.format(Locale.ROOT, "%.5f, %.5f", info.originLat(), info.originLon()));
            out.put(t("scale"), String.format(Locale.ROOT, "1 block = %s m", info.metersPerBlock()));
        } else {
            out.putAll(facts);
        }
        if (info != null) out.put(t("projection"), info.projection().replace('_', ' '));
        if (minecraft != null && minecraft.player != null && info != null) {
            double[] ll = parent.playerLatLon();
            if (ll != null) out.put(t("you"), String.format(Locale.ROOT, "%.5f, %.5f (block %d, %d)", ll[0], ll[1],
                    (int) Math.floor(minecraft.player.getX()), (int) Math.floor(minecraft.player.getZ())));
        }
        return out;
    }

    private static String t(String key) {
        return Component.translatable("orbisterrarum.map.world." + key).getString();
    }

    // ------------------------------------------------------------------ layout

    private int boxW() {
        return Math.min(width - 16, 460);
    }

    private boolean twoColumns() {
        return boxW() >= 340;
    }

    private int colW() {
        return twoColumns() ? (boxW() - 3 * PAD) / 2 : boxW() - 2 * PAD;
    }

    private int left() {
        return (width - boxW()) / 2;
    }

    private int top() {
        return TOP_H + 6;
    }

    /** Where the right-hand column (the switches) starts, under the facts when there is one column. */
    private int[] controlsAt() {
        int x0 = left() + PAD, y0 = top() + PAD + 14;
        if (twoColumns()) return new int[]{x0 + colW() + PAD, y0};
        return new int[]{x0, y0 + factsHeight() + 6};
    }

    private int factsHeight() {
        int h = 0, valueW = colW() - LABEL_W;
        for (Map.Entry<String, String> e : allFacts().entrySet()) h += Math.max(1, font.split(Component.literal(e.getValue()), valueW).size()) * 10 + 2;
        return h;
    }

    /** Puts the buttons in the right column and gives them their labels; operators only for the switches. */
    private void layoutAndLabel() {
        if (limit == null) return;
        int[] at = controlsAt();
        int cw = colW(), half = (cw - 4) / 2, x = at[0], y = at[1] + 12;
        boolean controls = op && answered;
        int i = 0;
        for (String word : SKY) {
            Button b = skyButtons.get(word);
            String[] s = sky.get(word);
            b.visible = controls && s != null;
            if (s == null) continue;
            boolean on = s[2].equals("1"), installationOff = s[3].equals("1");
            b.setMessage(Component.translatable("orbisterrarum.map.world.switch", t("sky." + word),
                    Component.translatable(on ? "orbisterrarum.map.limit.on" : "orbisterrarum.map.limit.off")));
            Component tip = Component.literal(s[4] + ": " + s[5] + ".");
            if (installationOff) tip = tip.copy().append(Component.translatable("orbisterrarum.map.world.sky.installoff"));
            b.setTooltip(Tooltip.create(tip));
            b.setWidth(half);
            b.setPosition(x + (i % 2) * (half + 4), y + (i / 2) * 22);
            i++;
        }
        y += ((i + 1) / 2) * 22 + 14;
        for (Button b : new Button[]{limit, draw, download}) {
            b.visible = controls;
            b.setWidth(cw);
        }
        limit.setMessage(Component.translatable("orbisterrarum.map.limit", Component.translatable(limitOn ? "orbisterrarum.map.limit.on" : "orbisterrarum.map.limit.off")));
        limit.active = limitOn || allowedChunks > 0;
        limit.setPosition(x, y);
        draw.setMessage(drawing ? Component.literal(fit(drawText, cw - 10)) : Component.translatable("orbisterrarum.map.world.draw"));
        draw.active = !drawing;
        draw.setPosition(x, y + 22);
        String pct = downloadFraction >= 0 ? " (" + Math.round(downloadFraction * 100) + "%)" : "";
        download.setMessage(downloading ? Component.translatable("orbisterrarum.map.world.download.stop", pct)
                : Component.translatable(parent.selectionBox() != null ? "orbisterrarum.map.world.download.selection" : "orbisterrarum.map.world.download"));
        download.setTooltip(Tooltip.create(downloading
                ? Component.literal(downloadLine.isEmpty() ? "" : downloadLine + " - ").append(Component.translatable("orbisterrarum.map.world.download.stop.tip"))
                : Component.translatable("orbisterrarum.map.world.download.tip")));
        download.setPosition(x, y + 44);
    }

    private String fit(String s, int w) {
        if (font.width(s) <= w) return s;
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("..."))).stripTrailing() + "...";
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        if (close != null && e.x() >= close[0] && e.x() < close[2] && e.y() >= close[1] && e.y() < close[3]) {
            onClose();
            return true;
        }
        if (box != null && (e.x() < box[0] || e.x() >= box[2] || e.y() < box[1] || e.y() >= box[3])) {
            onClose(); // a click beside the window: back to the map
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (com.berg.orbis.client.OrbisClient.MAP_KEY != null && com.berg.orbis.client.OrbisClient.MAP_KEY.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        returning = true;
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        if (!returning) parent.release(); // closed some other way (a disconnect): the map's tiles go too
        super.removed();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        parent.extractBackground(g, -1, -1, partialTick);
        parent.extractRenderState(g, -1, -1, partialTick);
        g.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        layoutAndLabel();
        int x0 = left(), y0 = top(), w = boxW(), cw = colW();
        int[] at = controlsAt();
        int controlsBottom = at[1] + 12;
        boolean controls = op && answered;
        if (controls) controlsBottom += ((sky.size() + 1) / 2) * 22 + 14 + 3 * 22;
        else if (!sky.isEmpty()) controlsBottom += sky.size() * 10 + 4;
        else controlsBottom = at[1];
        int factsBottom = y0 + PAD + 14 + factsHeight();
        int bottom = Math.max(factsBottom, controlsBottom) + PAD - 2;
        boolean showMessage = message != null && System.currentTimeMillis() < messageUntil;
        List<FormattedCharSequence> msg = showMessage ? font.split(Component.literal(message), w - 2 * PAD) : List.of();
        if (msg.size() > 3) msg = msg.subList(0, 3);
        bottom += msg.size() * 10 + (msg.isEmpty() ? 0 : 4);
        g.fill(x0 - 1, y0 - 1, x0 + w + 1, bottom + 1, 0xFF4A5868);
        g.fill(x0, y0, x0 + w, bottom, 0xF4141A20);
        box = new int[]{x0, y0, x0 + w, bottom};
        g.text(font, title, x0 + PAD, y0 + PAD, WHITE);
        int cx = x0 + w - PAD - font.width("×");
        close = new int[]{cx - 4, y0, x0 + w, y0 + PAD + 12};
        g.text(font, "×", cx, y0 + PAD, GREY);
        // the facts
        int y = y0 + PAD + 14, valueW = cw - LABEL_W;
        for (Map.Entry<String, String> e : allFacts().entrySet()) {
            g.text(font, fit(e.getKey(), LABEL_W - 4), x0 + PAD, y, GREY);
            List<FormattedCharSequence> v = font.split(Component.literal(e.getValue()), valueW);
            for (FormattedCharSequence l : v) {
                g.text(font, l, x0 + PAD + LABEL_W, y, WHITE);
                y += 10;
            }
            if (v.isEmpty()) y += 10;
            y += 2;
        }
        if (!answered && canAsk()) g.text(font, t("asking"), x0 + PAD, y, GREY);
        // the switches
        if (!sky.isEmpty()) {
            g.text(font, t("sky"), at[0], at[1], GREY);
            if (!controls) {
                int sy = at[1] + 12;
                for (String word : SKY) {
                    String[] s = sky.get(word);
                    if (s == null) continue;
                    g.text(font, t("sky." + word) + ": " + Component.translatable(s[2].equals("1") ? "orbisterrarum.map.limit.on"
                            : "orbisterrarum.map.limit.off").getString(), at[0], sy, WHITE);
                    sy += 10;
                }
            } else {
                g.text(font, t("tools"), at[0], at[1] + 12 + ((sky.size() + 1) / 2) * 22 + 2, GREY);
            }
        }
        int my = bottom - PAD + 2 - msg.size() * 10;
        for (FormattedCharSequence l : msg) {
            g.text(font, l, x0 + PAD, my, 0xFFFACC15);
            my += 10;
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
