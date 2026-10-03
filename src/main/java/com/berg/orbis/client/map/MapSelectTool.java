package com.berg.orbis.client.map;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.net.PregenSelectionPayload;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.worldgen.ChunkSelection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Selecting an area of the world map to pre-generate, the way an image editor selects part of a picture: a
 * rectangle, an ellipse or a lasso (drag freehand, or click corner after corner and close with a double-click,
 * a click on the first corner or Enter). A new shape replaces the selection, or with Shift held (or the Add mode)
 * joins it, and with Alt held (or the Subtract mode) is cut out of it. Holding Ctrl while dragging a rectangle or
 * ellipse keeps it square or round (Shift is for adding).
 * Skip open sea (a toggle, off by default) leaves chunks that are deep sea all over out of the area's
 * pre-generation; off, the area is generated sea and all.
 * Expand and Shrink grow or shrink the whole selection by a ring of chunks (about a twentieth of its size, in a
 * round number of metres; one chunk with Ctrl held), keeping holes and corners round.
 * Ctrl+Z undoes the last change, Ctrl+D deselects. The chunks it covers are shown and counted as
 * the shapes are drawn; Generate sends the shapes to the server, which sweeps exactly those chunks.
 * <p>
 * Shapes are kept per world (singleplayer save or server address) in {@code config/orbisterrarum/map-selections.json}.
 * The world preview (before a world exists) has the same tool without Generate: what is drawn there is saved
 * with the new world's settings and pre-generated when the world opens (World tab, "Pre-generate when the
 * world is created"; see worldgen/AutoPregen).
 */
public final class MapSelectTool {
    /** The world preview's own entry (it goes with the new world's settings when the world is created). */
    public static final String PREVIEW = "preview";

    enum Tool { HAND, RECTANGLE, ELLIPSE, LASSO }

    enum Mode { NEW, ADD, SUBTRACT }

    /** A drawn shape: its outline in lat/lon (to draw it) and in blocks (to select and send). */
    record Shape(boolean subtract, double[][] latLon, int[] blocks) {}

    private static final Gson GSON = new Gson();
    private static final int FILL = 0x5822C55E, DRAW = 0xFFFACC15, CUT = 0xFFF87171;
    /** Stored on disk: per world, the shapes' lat/lon outlines with an add/cut flag. */
    private record Stored(boolean subtract, double[][] latLon) {}

    private static Map<String, List<Stored>> saved;
    private static Tool lastTool = Tool.HAND;
    private static Mode lastMode = Mode.NEW;

    private final Minecraft mc;
    private final MapView canvas;
    private CoordinateMapper mapper;
    private double metersPerBlock;
    private final String worldKey;
    /** False in the world preview: there is no world to generate yet (and the tool is always on there). */
    private final boolean canGenerate;
    private final boolean alwaysOn;
    private final Consumer<String> say;

    boolean active;
    Tool tool = lastTool;
    Mode mode = lastMode;
    final List<Shape> shapes = new ArrayList<>();

    // a shape being drawn
    private boolean drawing, polygon;
    private boolean dragSubtract, dragReplace;
    private boolean constrain;
    private double[] startLl, endLl;
    private final List<double[]> lasso = new ArrayList<>();
    private double lastGx, lastGy;

    // the selection the shapes make (worked out off the render thread)
    private volatile ChunkSelection selection;
    private volatile long generatedKnown = -1;
    private int version;
    private boolean counted;

    private Button toggle, hand, rect, ellipse, lassoButton, modeButton, undo, clear, expand, contract, sea, generate;
    /** Skip open sea for this area: the world preview's goes with the new world, the world map's with Generate. Off at first. */
    private static boolean previewSkipSea, worldSkipSea;
    /** The shapes before each change, for Undo (the newest last). */
    private final java.util.ArrayDeque<List<Shape>> history = new java.util.ArrayDeque<>();
    private boolean resizing;

    public MapSelectTool(Minecraft mc, MapView canvas, CoordinateMapper mapper, double metersPerBlock, String worldKey, boolean canGenerate,
                         Consumer<String> say) {
        this.mc = mc;
        this.canvas = canvas;
        this.mapper = mapper;
        this.metersPerBlock = metersPerBlock;
        this.worldKey = worldKey;
        this.canGenerate = canGenerate;
        this.alwaysOn = !canGenerate;
        this.active = alwaysOn;
        this.say = say;
        for (Stored s : load().getOrDefault(worldKey, List.of())) {
            Shape shape = shape(s.subtract(), s.latLon());
            if (shape != null) shapes.add(shape);
        }
        recompute();
    }

    /** A new world is being set up: its generator map starts with nothing selected. */
    public static void clearPreview() {
        synchronized (MapSelectTool.class) {
            previewSkipSea = false;
            if (load().remove(PREVIEW) == null) return;
            try {
                Files.writeString(file(), GSON.toJson(load()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("[orbis] Could not save map selections: " + e);
            }
        }
    }

    /** The world preview's Skip open sea button, for the new world's settings. */
    public static synchronized boolean previewSkipsSea() {
        return previewSkipSea;
    }

    private boolean skipSea() {
        return alwaysOn ? previewSkipSea : worldSkipSea;
    }

    private void toggleSea() {
        synchronized (MapSelectTool.class) {
            if (alwaysOn) previewSkipSea = !previewSkipSea;
            else worldSkipSea = !worldSkipSea;
        }
        refresh();
    }

    /** The selection drawn in the world preview, for the new world's settings. */
    public static synchronized List<com.berg.orbis.config.OrbisConfig.PregenShape> previewShapes() {
        List<com.berg.orbis.config.OrbisConfig.PregenShape> out = new ArrayList<>();
        for (Stored s : load().getOrDefault(PREVIEW, List.of())) out.add(new com.berg.orbis.config.OrbisConfig.PregenShape(s.subtract(), s.latLon()));
        return out;
    }

    /** The world preview changed its scale: the same places, other blocks. */
    public void setScale(CoordinateMapper mapper, double metersPerBlock) {
        this.mapper = mapper;
        this.metersPerBlock = metersPerBlock;
        for (int i = 0; i < shapes.size(); i++) {
            Shape s = shape(shapes.get(i).subtract(), shapes.get(i).latLon());
            if (s != null) shapes.set(i, s);
        }
        fitBudget();
        recompute();
    }

    public boolean active() {
        return active;
    }

    /** Whether the Generate button (bottom right of the world map) is showing. */
    public boolean generateShown() {
        return generate != null && generate.visible;
    }

    /** Whether a found place will be cut out of the selection (the Subtract mode) rather than added. */
    public boolean subtracting() {
        return mode == Mode.SUBTRACT;
    }

    /** The chunks selected now (null while the first one is being worked out). */
    public ChunkSelection selection() {
        return selection;
    }

    /**
     * Adds a place's outline (its polygons, [lat, lon] corners) the way the mode says: New replaces the selection,
     * Add joins it, Subtract cuts it out. The largest parts only, when a country has hundreds of islands.
     */
    public void addOutline(List<double[][]> polygons) {
        cancel();
        List<double[][]> parts = new ArrayList<>(polygons);
        parts.sort((a, b) -> Double.compare(bboxArea(b), bboxArea(a)));
        if (parts.size() > 60) parts = parts.subList(0, 60);
        boolean subtract = mode == Mode.SUBTRACT;
        remember();
        if (mode == Mode.NEW) shapes.clear();
        for (double[][] p : parts) {
            Shape s = shape(subtract, p);
            if (s == null || (subtract && shapes.isEmpty())) continue;
            shapes.add(s);
        }
        fitBudget();
        changed();
    }

    private static double bboxArea(double[][] p) {
        double a = 90, b = 180, c = -90, d = -180;
        for (double[] v : p) {
            a = Math.min(a, v[0]);
            c = Math.max(c, v[0]);
            b = Math.min(b, v[1]);
            d = Math.max(d, v[1]);
        }
        return (c - a) * (d - b);
    }

    /** Operators of a server (or singleplayer with cheats) running Orbis: they can pre-generate. */
    static boolean allowed(Minecraft mc) {
        if (mc.player == null || !ClientPlayNetworking.canSend(PregenSelectionPayload.TYPE)) return false;
        var orbis = mc.player.connection.getCommands().getRoot().getChild("orbis");
        return orbis != null && orbis.getChild("pregen") != null;
    }

    // ------------------------------------------------------------------ widgets

    /** Width of the tool strip (for screens keeping their messages clear of it). */
    public static final int STRIP_W = 20;

    private int stripLeft, stripTop, stripRight, stripBottom, toggleBottom;

    /**
     * Where text running from {@code top} to {@code bottom} (GUI pixels) may start on the left so the tool strip
     * does not cover it: beside the strip where they meet, else at {@code normal}.
     */
    public int textLeft(int top, int bottom, int normal) {
        if (toggle == null) return normal;
        int stripEnd = active ? stripBottom : alwaysOn ? stripTop : toggleBottom;
        return bottom >= stripTop && top <= stripEnd ? Math.max(normal, stripRight + 6) : normal;
    }

    /**
     * A narrow strip of icon buttons down the left of the map, as in an image editor's tool panel (hover for the
     * name and keys): select on/off, then rectangle, ellipse, lasso, the mode, undo and deselect.
     */
    public void addWidgets(Consumer<Button> add, int x, int y, int screenW, int screenH) {
        int s = STRIP_W;
        toggle = Button.builder(Component.translatable("orbisterrarum.map.select.off"), b -> setActive(!active)).bounds(x, y, s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.tip"))).build();
        // One 4-pixel gap everywhere: under the toggle, between rows and between the two columns.
        int p = s + 4;
        int y0 = alwaysOn ? y : y + p;
        // One column down the left when the screen is tall enough; else two side by side (large GUI scales): the
        // drawing tools, and beside them what acts on the selection. Either way clear of the bottom line.
        boolean one = y0 + 10 * p <= screenH - 20;
        int x2 = one ? x : x + p;
        // Two columns share one row grid, so each button sits level with its neighbour.
        int[] at = one ? new int[]{0, p, 2 * p, 3 * p, 4 * p, 5 * p, 6 * p, 7 * p, 8 * p, 9 * p}
                : new int[]{0, p, 2 * p, 3 * p, 4 * p, 0, p, 2 * p, 3 * p, 4 * p};
        stripLeft = x;
        stripTop = y;
        stripRight = (one ? x : x2) + s;
        stripBottom = y0 + (one ? at[9] : Math.max(at[4], at[9])) + s;
        toggleBottom = y + s;
        hand = Button.builder(Component.translatable("orbisterrarum.map.select.hand"), b -> pick(Tool.HAND)).bounds(x, y0 + at[0], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.hand.tip"))).build();
        rect = Button.builder(Component.translatable("orbisterrarum.map.select.rect"), b -> pick(Tool.RECTANGLE)).bounds(x, y0 + at[1], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.rect.tip"))).build();
        ellipse = Button.builder(Component.translatable("orbisterrarum.map.select.ellipse"), b -> pick(Tool.ELLIPSE)).bounds(x, y0 + at[2], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.ellipse.tip"))).build();
        lassoButton = Button.builder(Component.translatable("orbisterrarum.map.select.lasso"), b -> pick(Tool.LASSO)).bounds(x, y0 + at[3], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.lasso.tip"))).build();
        modeButton = Button.builder(Component.empty(), b -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            lastMode = mode;
            refresh();
        }).bounds(x, y0 + at[4], s, s).build();
        undo = Button.builder(Component.translatable("orbisterrarum.map.select.undo"), b -> undo()).bounds(x2, y0 + at[5], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.undo.tip"))).build();
        clear = Button.builder(Component.translatable("orbisterrarum.map.select.clear"), b -> deselect()).bounds(x2, y0 + at[6], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.clear.tip"))).build();
        expand = Button.builder(Component.translatable("orbisterrarum.map.select.expand"), b -> resize(true, mc.hasControlDown())).bounds(x2, y0 + at[7], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.expand.tip"))).build();
        contract = Button.builder(Component.translatable("orbisterrarum.map.select.shrink"), b -> resize(false, mc.hasControlDown())).bounds(x2, y0 + at[8], s, s)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.shrink.tip"))).build();
        sea = Button.builder(Component.translatable("orbisterrarum.map.select.sea"), b -> toggleSea()).bounds(x2, y0 + at[9], s, s).build();
        generate = Button.builder(Component.translatable("orbisterrarum.map.select.generate"), b -> send())
                .bounds(screenW - 104, screenH - 58, 100, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.generate.tip"))).build();
        if (!alwaysOn) add.accept(toggle);
        for (Button b : new Button[]{hand, rect, ellipse, lassoButton, modeButton, undo, clear, expand, contract, sea}) add.accept(b);
        if (canGenerate) add.accept(generate);
        refresh();
    }

    private void refresh() {
        if (toggle == null) return;
        Component modeName = Component.translatable("orbisterrarum.map.select.mode." + mode.name().toLowerCase(Locale.ROOT));
        modeButton.setMessage(modeName);
        modeButton.setTooltip(Tooltip.create(Component.translatable("orbisterrarum.map.select.mode.tip", modeName)));
        for (Button b : new Button[]{hand, rect, ellipse, lassoButton, modeButton, undo, clear, expand, contract, sea}) b.visible = active;
        sea.setTooltip(Tooltip.create(Component.translatable(skipSea() ? "orbisterrarum.map.select.sea.on" : "orbisterrarum.map.select.sea.off")));
        toggle.visible = !alwaysOn;
        undo.active = !shapes.isEmpty() || !history.isEmpty();
        clear.active = !shapes.isEmpty();
        ChunkSelection s = selection;
        expand.active = contract.active = !resizing && s != null && !s.isEmpty();
        generate.visible = canGenerate && active && s != null && !s.isEmpty();
    }

    // The buttons' labels name them for narration; the strip draws icons over them instead of the text.

    /** Draws the strip's icons over its buttons (call after the screen's widgets are drawn). */
    public void drawIcons(GuiGraphicsExtractor g) {
        if (toggle == null) return;
        if (!alwaysOn) {
            icon(g, toggle, active ? 0xFFFACC15 : 0xFFFFFFFF, (x, y, c) -> dashedSquare(g, x + 4, y + 4, 12, c));
            if (active) g.outline(toggle.getX() - 1, toggle.getY() - 1, STRIP_W + 2, STRIP_W + 2, 0xFFFACC15);
        }
        if (!active) return;
        icon(g, hand, 0xFFFFFFFF, (x, y, c) -> {
            // Four arrows: move the map.
            gline(g, x + 10, y + 4, x + 10, y + 16, c);
            gline(g, x + 4, y + 10, x + 16, y + 10, c);
            gline(g, x + 8, y + 6, x + 10, y + 4, c);
            gline(g, x + 12, y + 6, x + 10, y + 4, c);
            gline(g, x + 8, y + 14, x + 10, y + 16, c);
            gline(g, x + 12, y + 14, x + 10, y + 16, c);
            gline(g, x + 6, y + 8, x + 4, y + 10, c);
            gline(g, x + 6, y + 12, x + 4, y + 10, c);
            gline(g, x + 14, y + 8, x + 16, y + 10, c);
            gline(g, x + 14, y + 12, x + 16, y + 10, c);
        });
        icon(g, rect, 0xFFFFFFFF, (x, y, c) -> g.outline(x + 4, y + 6, 12, 9, c));
        icon(g, ellipse, 0xFFFFFFFF, (x, y, c) -> ring(g, x + 10, y + 10, 6.2, 5.0, c));
        icon(g, lassoButton, 0xFFFFFFFF, (x, y, c) -> {
            ring(g, x + 11, y + 8, 5.5, 3.6, c);
            gline(g, x + 7, y + 11, x + 5, y + 16, c);
        });
        icon(g, modeButton, 0xFFFFFFFF, (x, y, c) -> {
            switch (mode) {
                case NEW -> g.outline(x + 5, y + 5, 10, 10, c);
                case ADD -> {
                    g.outline(x + 3, y + 3, 9, 9, c);
                    g.outline(x + 8, y + 8, 9, 9, c);
                    g.fill(x + 13, y + 10, x + 14, y + 15, 0xFF4ADE80);
                    g.fill(x + 11, y + 12, x + 16, y + 13, 0xFF4ADE80);
                }
                case SUBTRACT -> {
                    g.outline(x + 3, y + 3, 9, 9, c);
                    g.fill(x + 8, y + 8, x + 17, y + 17, 0xFF202020);
                    g.outline(x + 8, y + 8, 9, 9, 0xFFF87171);
                    g.fill(x + 10, y + 12, x + 15, y + 13, 0xFFF87171);
                }
            }
        });
        icon(g, undo, 0xFFFFFFFF, (x, y, c) -> {
            // A hooked arrow pointing left.
            gline(g, x + 6, y + 8, x + 13, y + 8, c);
            gline(g, x + 13, y + 8, x + 15, y + 10, c);
            gline(g, x + 15, y + 10, x + 15, y + 12, c);
            gline(g, x + 15, y + 12, x + 13, y + 14, c);
            gline(g, x + 13, y + 14, x + 9, y + 14, c);
            gline(g, x + 6, y + 8, x + 9, y + 5, c);
            gline(g, x + 6, y + 8, x + 9, y + 11, c);
        });
        icon(g, clear, 0xFFFFFFFF, (x, y, c) -> {
            gline(g, x + 5, y + 5, x + 14, y + 14, c);
            gline(g, x + 14, y + 5, x + 5, y + 14, c);
        });
        icon(g, expand, expand.active ? 0xFFFFFFFF : 0xFF808080, (x, y, c) -> {
            // A small square with arrows out of its corners.
            g.outline(x + 7, y + 7, 6, 6, c);
            for (int[] d : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                int ex = x + 10 + d[0] * 7, ey = y + 10 + d[1] * 7;
                gline(g, x + 10 + d[0] * 4, y + 10 + d[1] * 4, ex, ey, c);
                gline(g, ex, ey, ex - d[0] * 2, ey, c);
                gline(g, ex, ey, ex, ey - d[1] * 2, c);
            }
        });
        icon(g, contract, contract.active ? 0xFFFFFFFF : 0xFF808080, (x, y, c) -> {
            // A large square with arrows pointing into it from its corners.
            g.outline(x + 3, y + 3, 14, 14, c);
            for (int[] d : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                int ex = x + 10 + d[0] * 2, ey = y + 10 + d[1] * 2;
                gline(g, x + 10 + d[0] * 5, y + 10 + d[1] * 5, ex, ey, c);
                gline(g, ex, ey, ex + d[0] * 2, ey, c);
                gline(g, ex, ey, ex, ey + d[1] * 2, c);
            }
        });
        icon(g, sea, skipSea() ? 0xFFFACC15 : 0xFFFFFFFF, (x, y, c) -> {
            // Three waves; crossed out unless on (open sea left out).
            for (int row = 0; row < 3; row++) {
                int yy = y + 6 + row * 4;
                for (int i = 0; i < 12; i++) g.fill(x + 4 + i, yy + ((i / 3) % 2 == 0 ? 0 : 1), x + 5 + i, yy + ((i / 3) % 2 == 0 ? 1 : 2), c);
            }
            if (skipSea()) gline(g, x + 4, y + 16, x + 16, y + 4, 0xFFF87171);
        });
        if (skipSea()) g.outline(sea.getX() - 1, sea.getY() - 1, STRIP_W + 2, STRIP_W + 2, 0xFFFACC15);
        Button chosen = tool == Tool.HAND ? hand : tool == Tool.RECTANGLE ? rect : tool == Tool.ELLIPSE ? ellipse : lassoButton;
        g.outline(chosen.getX() - 1, chosen.getY() - 1, STRIP_W + 2, STRIP_W + 2, 0xFFFACC15);
    }

    private interface IconPainter {
        void paint(int x, int y, int colour);
    }

    /** Covers the button's text with its face colour and paints the icon (greyed when the button is off). */
    private static void icon(GuiGraphicsExtractor g, Button b, int colour, IconPainter p) {
        if (!b.visible) return;
        int x = b.getX(), y = b.getY();
        g.fill(x + 2, y + 2, x + STRIP_W - 2, y + STRIP_W - 3, b.isHoveredOrFocused() && b.active ? 0xFF707070 : 0xFF5A5A5A);
        p.paint(x, y, b.active ? colour : 0xFF9A9A9A);
    }

    private static void dashedSquare(GuiGraphicsExtractor g, int x, int y, int s, int c) {
        for (int i = 0; i < s; i += 3) {
            g.fill(x + i, y, x + Math.min(i + 2, s), y + 1, c);
            g.fill(x + i, y + s - 1, x + Math.min(i + 2, s), y + s, c);
            g.fill(x, y + i, x + 1, y + Math.min(i + 2, s), c);
            g.fill(x + s - 1, y + i, x + s, y + Math.min(i + 2, s), c);
        }
    }

    private static void ring(GuiGraphicsExtractor g, double cx, double cy, double rx, double ry, int c) {
        for (int i = 0; i < 48; i++) {
            double t = 2 * Math.PI * i / 48;
            int x = (int) Math.floor(cx + rx * Math.cos(t)), y = (int) Math.floor(cy + ry * Math.sin(t));
            g.fill(x, y, x + 1, y + 1, c);
        }
    }

    private static void gline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int c) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + Math.round((x1 - x0) * i / (float) Math.max(1, n)), y = y0 + Math.round((y1 - y0) * i / (float) Math.max(1, n));
            g.fill(x, y, x + 1, y + 1, c);
        }
    }

    private void setActive(boolean on) {
        active = on;
        cancel();
        if (on) say.accept(Component.translatable("orbisterrarum.map.select.help").getString());
        refresh();
    }

    private void pick(Tool t) {
        cancel();
        tool = t;
        lastTool = t;
        refresh();
    }

    // ------------------------------------------------------------------ input

    /** True when a left press on the map belongs to the selection (the screen then does not pan). */
    public boolean mousePressed(MouseButtonEvent e, boolean doubleClick) {
        if (!active || e.button() != InputConstants.MOUSE_BUTTON_LEFT || (tool == Tool.HAND && !polygon)) return false;
        double[] ll = canvas.latLonAt(e.x(), e.y());
        if (polygon) {
            double[] first = canvas.gui(lasso.get(0)[0], lasso.get(0)[1]);
            if (doubleClick || (lasso.size() >= 3 && Math.hypot(first[0] - e.x(), first[1] - e.y()) <= 6)) {
                commitLasso();
            } else {
                lasso.add(ll);
            }
            return true;
        }
        dragSubtract = e.hasAltDown() || (!e.hasShiftDown() && mode == Mode.SUBTRACT);
        dragReplace = !e.hasShiftDown() && !e.hasAltDown() && mode == Mode.NEW;
        constrain = e.hasControlDown();
        drawing = true;
        startLl = endLl = ll;
        lasso.clear();
        lasso.add(ll);
        lastGx = e.x();
        lastGy = e.y();
        return true;
    }

    public boolean mouseDragged(MouseButtonEvent e) {
        if (!drawing) return false;
        constrain = e.hasControlDown(); // a square or a circle while Ctrl is held
        endLl = canvas.latLonAt(e.x(), e.y());
        if (tool == Tool.LASSO && Math.hypot(e.x() - lastGx, e.y() - lastGy) >= 2) {
            lasso.add(endLl);
            lastGx = e.x();
            lastGy = e.y();
        }
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent e) {
        if (!drawing || e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        drawing = false;
        double[] a = canvas.gui(startLl[0], startLl[1]);
        boolean click = Math.hypot(e.x() - a[0], e.y() - a[1]) < 3;
        if (tool == Tool.LASSO) {
            if (click || lasso.size() < 3) {
                // A click instead of a drag: corner by corner.
                polygon = true;
                lasso.clear();
                lasso.add(startLl);
                say.accept(Component.translatable("orbisterrarum.map.select.polygon").getString());
            } else {
                commitLasso();
            }
            return true;
        }
        if (click) {
            // A click outside any drag deselects, as in an image editor (only in the plain New mode).
            if (dragReplace && !shapes.isEmpty()) deselect();
            return true;
        }
        commit(outline(tool, startLl, endLl, constrain));
        return true;
    }

    /** Keys while selecting; true when used. */
    public boolean keyPressed(KeyEvent e) {
        if (!active) return false;
        int k = e.key();
        if (k == InputConstants.KEY_ESCAPE && (drawing || polygon)) {
            cancel();
            return true;
        }
        if (polygon && (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER)) {
            commitLasso();
            return true;
        }
        if (polygon && (k == InputConstants.KEY_BACKSPACE || k == InputConstants.KEY_DELETE)) {
            if (lasso.size() > 1) lasso.remove(lasso.size() - 1);
            else cancel();
            return true;
        }
        if (e.hasControlDown() && k == InputConstants.KEY_Z) {
            undo();
            return true;
        }
        if (e.hasControlDown() && k == InputConstants.KEY_D) {
            deselect();
            return true;
        }
        return false;
    }

    private void cancel() {
        drawing = false;
        polygon = false;
        lasso.clear();
    }

    // ------------------------------------------------------------------ shapes

    /** A rectangle or ellipse between two corners, as drawn on screen (north up), in lat/lon. */
    private double[][] outline(Tool t, double[] a, double[] b, boolean square) {
        double[] p = canvas.gui(a[0], a[1]), q = canvas.gui(b[0], b[1]);
        double x0 = p[0], y0 = p[1], x1 = q[0], y1 = q[1];
        if (square) {
            double s = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            x1 = x0 + Math.copySign(s, x1 - x0);
            y1 = y0 + Math.copySign(s, y1 - y0);
        }
        List<double[]> pts = new ArrayList<>();
        if (t == Tool.ELLIPSE) {
            double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, rx = Math.abs(x1 - x0) / 2, ry = Math.abs(y1 - y0) / 2;
            for (int i = 0; i < 96; i++) {
                double th = 2 * Math.PI * i / 96;
                pts.add(canvas.latLonAt(cx + rx * Math.cos(th), cy + ry * Math.sin(th)));
            }
        } else {
            double[][] c = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
            for (int i = 0; i < 4; i++) {
                double[] u = c[i], v = c[(i + 1) % 4];
                for (int j = 0; j < 8; j++) pts.add(canvas.latLonAt(u[0] + (v[0] - u[0]) * j / 8, u[1] + (v[1] - u[1]) * j / 8));
            }
        }
        return pts.toArray(new double[0][]);
    }

    private void commitLasso() {
        List<double[]> pts = new ArrayList<>(lasso);
        cancel();
        if (pts.size() < 3) return;
        commit(pts.toArray(new double[0][]));
    }

    private void commit(double[][] latLon) {
        Shape shape = shape(dragSubtract, latLon);
        if (shape == null) return;
        if (shape.subtract() && (dragReplace || shapes.isEmpty())) return; // nothing to cut from
        remember();
        if (dragReplace) shapes.clear();
        shapes.add(shape);
        fitBudget();
        changed();
    }

    /** The shape's outline in blocks, simplified to within a few blocks (fewer corners to send). */
    private Shape shape(boolean subtract, double[][] latLon) {
        if (latLon == null || latLon.length < 3) return null;
        double[][] b = new double[latLon.length][];
        for (int i = 0; i < latLon.length; i++) b[i] = mapper.toBlockExact(latLon[i][0], latLon[i][1]);
        int[] xz = simplify(b, 2.0);
        if (xz.length < 6) return null;
        return new Shape(subtract, latLon, xz);
    }

    /** Keeps the whole selection under the message size by simplifying the lassos harder when needed. */
    private void fitBudget() {
        double eps = 2.0;
        while (totalVertices() > PregenSelectionPayload.MAX_VERTICES && eps < 4096) {
            eps *= 2;
            for (int i = 0; i < shapes.size(); i++) {
                Shape s = shapes.get(i);
                if (s.blocks().length / 2 <= 96) continue;
                double[][] b = new double[s.latLon().length][];
                for (int j = 0; j < b.length; j++) b[j] = mapper.toBlockExact(s.latLon()[j][0], s.latLon()[j][1]);
                shapes.set(i, new Shape(s.subtract(), s.latLon(), simplify(b, eps)));
            }
        }
        while (shapes.size() > PregenSelectionPayload.MAX_SHAPES) shapes.remove(0);
    }

    private int totalVertices() {
        int n = 0;
        for (Shape s : shapes) n += s.blocks().length / 2;
        return n;
    }

    /** Douglas-Peucker on a closed outline, to {@code eps} blocks; returns x0, z0, x1, z1, ... */
    private static int[] simplify(double[][] p, double eps) {
        int n = p.length;
        if (n <= 4) return flat(p, null);
        boolean[] keep = new boolean[n];
        keep[0] = true;
        // Split at the vertex farthest from the first, then simplify both halves.
        int far = 0;
        double best = -1;
        for (int i = 1; i < n; i++) {
            double d = Math.hypot(p[i][0] - p[0][0], p[i][1] - p[0][1]);
            if (d > best) {
                best = d;
                far = i;
            }
        }
        keep[far] = true;
        dp(p, 0, far, eps, keep);
        dp(p, far, n, eps, keep);
        return flat(p, keep);
    }

    private static void dp(double[][] p, int a, int b, double eps, boolean[] keep) {
        int n = p.length;
        double[] pa = p[a], pb = p[b % n];
        double dx = pb[0] - pa[0], dz = pb[1] - pa[1], len = Math.hypot(dx, dz);
        int idx = -1;
        double max = eps;
        for (int i = a + 1; i < b; i++) {
            double d = len < 1e-9 ? Math.hypot(p[i][0] - pa[0], p[i][1] - pa[1])
                    : Math.abs(dx * (pa[1] - p[i][1]) - dz * (pa[0] - p[i][0])) / len;
            if (d > max) {
                max = d;
                idx = i;
            }
        }
        if (idx < 0) return;
        keep[idx] = true;
        dp(p, a, idx, eps, keep);
        dp(p, idx, b, eps, keep);
    }

    private static int[] flat(double[][] p, boolean[] keep) {
        int m = 0;
        for (int i = 0; i < p.length; i++) if (keep == null || keep[i]) m++;
        int[] out = new int[m * 2];
        int k = 0;
        for (int i = 0; i < p.length; i++) {
            if (keep != null && !keep[i]) continue;
            out[k++] = (int) Math.round(p[i][0]);
            out[k++] = (int) Math.round(p[i][1]);
        }
        return out;
    }

    /** Keeps the shapes as they are before a change, for Undo. */
    private void remember() {
        history.addLast(new ArrayList<>(shapes));
        while (history.size() > 50) history.removeFirst();
    }

    /** Back to the selection before the last change (after a restart, when there is no history: drops the last shape). */
    private void undo() {
        cancel();
        if (!history.isEmpty()) {
            shapes.clear();
            shapes.addAll(history.removeLast());
        } else if (!shapes.isEmpty()) {
            shapes.remove(shapes.size() - 1);
        } else {
            return;
        }
        changed();
    }

    private void deselect() {
        cancel();
        if (shapes.isEmpty()) return;
        remember();
        shapes.clear();
        changed();
    }

    /**
     * Grows (or shrinks) the selection by a ring of chunks: about a twentieth of its width, rounded to 1, 2 or 5
     * times a power of ten metres, at least one chunk; one chunk exactly for a fine step (Ctrl held). The new selection replaces the shapes as outlines along chunk
     * edges (holes cut out), which select exactly the grown chunks.
     */
    private void resize(boolean grow, boolean fine) {
        cancel();
        ChunkSelection s = selection;
        if (s == null || s.isEmpty() || resizing) return;
        double chunkM = 16 * metersPerBlock;
        double target = 0.05 * 2 * Math.sqrt(s.count() / Math.PI) * chunkM;
        double pow = Math.pow(10, Math.floor(Math.log10(Math.max(target, 1))));
        double nice = target / pow >= 5 ? 5 * pow : target / pow >= 2 ? 2 * pow : pow;
        int k = fine ? 1 : Math.max(1, (int) Math.round(nice / chunkM));
        double metres = k * chunkM;
        String by = metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000) : String.format(Locale.ROOT, "%.0f m", metres);
        resizing = true;
        refresh();
        int v = version;
        CompletableFuture.supplyAsync(() -> grow ? s.grown(k) : s.shrunk(k)).whenComplete((r, err) -> mc.execute(() -> {
            resizing = false;
            if (err != null || r == null || v != version) {
                refresh();
                return;
            }
            if (r.isEmpty()) {
                say.accept(Component.translatable("orbisterrarum.map.select.toosmall").getString());
                refresh();
                return;
            }
            List<Shape> next = new ArrayList<>();
            for (ChunkSelection.Shape o : r.outlines()) {
                if (next.size() == PregenSelectionPayload.MAX_SHAPES) break; // the smallest go (largest come first)
                int[] xz = o.xz();
                double[][] ll = new double[xz.length / 2][];
                for (int i = 0; i < ll.length; i++) ll[i] = mapper.toLatLonExact(xz[2 * i], xz[2 * i + 1]);
                next.add(new Shape(o.subtract(), ll, xz));
            }
            remember();
            shapes.clear();
            shapes.addAll(next);
            fitBudget();
            changed();
            say.accept(Component.translatable(grow ? "orbisterrarum.map.select.expanded" : "orbisterrarum.map.select.shrunk",
                    by, Component.translatable(k == 1 ? "orbisterrarum.map.select.chunk" : "orbisterrarum.map.select.chunks",
                            String.format(Locale.ROOT, "%,d", k))).getString());
        }));
    }

    private void changed() {
        if (toggle == null) {
            save();
            recompute();
            return;
        }
        save();
        recompute();
        refresh();
    }

    private void recompute() {
        int v = ++version;
        List<ChunkSelection.Shape> list = new ArrayList<>();
        for (Shape s : shapes) list.add(new ChunkSelection.Shape(s.subtract(), s.blocks()));
        CompletableFuture.supplyAsync(() -> ChunkSelection.of(list)).thenAccept(sel -> mc.execute(() -> {
            if (v != version) return;
            selection = sel;
            generatedKnown = -1;
            counted = false;
            refresh();
        }));
    }

    private void send() {
        ChunkSelection s = selection;
        if (s == null || s.isEmpty()) return;
        List<ChunkSelection.Shape> list = new ArrayList<>();
        for (Shape shape : shapes) list.add(new ChunkSelection.Shape(shape.subtract(), shape.blocks()));
        ClientPlayNetworking.send(new PregenSelectionPayload(list, skipSea()));
        say.accept(Component.translatable("orbisterrarum.map.select.sent").getString());
    }

    // ------------------------------------------------------------------ drawing

    /** The selected chunks (tinted, with marching ants round them) and the shape being drawn. */
    public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        ChunkSelection s = selection;
        canvas.beginOverlay(g);
        if (s != null && !s.isEmpty()) drawSelection(g, s);
        if (drawing || polygon) drawInProgress(g, mouseX, mouseY);
        canvas.endOverlay(g);
    }

    private double[] phys(double bx, double bz) {
        double[] ll = mapper.toLatLonExact(bx, bz);
        return canvas.phys(ll[0], ll[1]);
    }

    private void drawSelection(GuiGraphicsExtractor g, ChunkSelection s) {
        // Only the rows in view, and at most one per physical pixel row (a whole country zoomed out).
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE, minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (double[] c : new double[][]{{canvas.left(), canvas.top()}, {canvas.right(), canvas.top()}, {canvas.left(), canvas.bottom()}, {canvas.right(), canvas.bottom()}}) {
            double[] ll = canvas.latLonAt(c[0], c[1]);
            double[] b = mapper.toBlockExact(ll[0], ll[1]);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxZ = Math.max(maxZ, b[1]);
        }
        int r0 = Math.max(s.firstRow(), (int) Math.floor(minZ / 16) - 1), r1 = Math.min(s.lastRow(), (int) Math.floor(maxZ / 16) + 1);
        if (r0 > r1) return;
        int cx0 = (int) Math.floor(minX / 16) - 1, cx1 = (int) Math.floor(maxX / 16) + 1;
        double rowPx = Math.abs(phys(0, 16)[1] - phys(0, 0)[1]);
        int step = Math.max(1, (int) Math.ceil(1.0 / Math.max(1e-6, rowPx)));
        long t = System.currentTimeMillis() / 120;
        boolean ants = step == 1 && rowPx >= 2;
        for (int cz = Math.floorDiv(r0, step) * step; cz <= r1; cz += step) {
            int[] runs = s.rawRuns(cz);
            if (runs == null) continue;
            for (int i = 0; i < runs.length; i += 2) {
                int a = Math.max(runs[i], cx0), b = Math.min(runs[i + 1], cx1);
                if (a > b) continue;
                double[] p = phys(a * 16.0, cz * 16.0), q = phys((b + 1) * 16.0, (cz + step) * 16.0);
                int x0 = (int) Math.round(Math.min(p[0], q[0])), x1 = (int) Math.round(Math.max(p[0], q[0]));
                int y0 = (int) Math.round(Math.min(p[1], q[1])), y1 = (int) Math.round(Math.max(p[1], q[1]));
                if (x1 <= x0) x1 = x0 + 1;
                if (y1 <= y0) y1 = y0 + 1;
                g.fill(x0, y0, x1, y1, FILL);
                if (ants) {
                    antsV(g, x0, y0, y1, t);
                    antsV(g, x1 - 1, y0, y1, t);
                }
            }
            if (ants) {
                // Top and bottom edges: where this row is selected and the neighbouring row is not.
                edges(g, runs, s.rawRuns(cz - 1), cz, cx0, cx1, false, t);
                edges(g, runs, s.rawRuns(cz + 1), cz + 1, cx0, cx1, true, t);
            }
        }
    }

    private void edges(GuiGraphicsExtractor g, int[] row, int[] other, int zEdge, int cx0, int cx1, boolean bottom, long t) {
        int[] open = other == null ? row : ChunkSelection.difference(row, other);
        for (int i = 0; i < open.length; i += 2) {
            int a = Math.max(open[i], cx0), b = Math.min(open[i + 1], cx1);
            if (a > b) continue;
            double[] p = phys(a * 16.0, zEdge * 16.0), q = phys((b + 1) * 16.0, zEdge * 16.0);
            int y = (int) Math.round((p[1] + q[1]) / 2) - (bottom ? 1 : 0);
            antsH(g, (int) Math.round(Math.min(p[0], q[0])), (int) Math.round(Math.max(p[0], q[0])), y, t);
        }
    }

    private static void antsH(GuiGraphicsExtractor g, int x0, int x1, int y, long t) {
        for (int x = x0; x < x1; x += 4) {
            boolean dark = Math.floorMod(x / 4 + t, 2) == 0;
            g.fill(x, y, Math.min(x + 4, x1), y + 1, dark ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    private static void antsV(GuiGraphicsExtractor g, int x, int y0, int y1, long t) {
        for (int y = y0; y < y1; y += 4) {
            boolean dark = Math.floorMod(y / 4 + t, 2) == 0;
            g.fill(x, y, x + 1, Math.min(y + 4, y1), dark ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    private void drawInProgress(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int colour = dragSubtract ? CUT : DRAW;
        List<double[]> pts = new ArrayList<>();
        boolean closed = true;
        if (tool == Tool.LASSO) {
            for (double[] ll : lasso) pts.add(canvas.phys(ll[0], ll[1]));
            if (polygon) {
                double[] m = canvas.latLonAt(mouseX, mouseY);
                pts.add(canvas.phys(m[0], m[1]));
            }
            closed = !polygon;
        } else if (startLl != null && endLl != null) {
            for (double[] ll : outline(tool, startLl, endLl, constrain)) pts.add(canvas.phys(ll[0], ll[1]));
        }
        int edges = closed && pts.size() > 2 ? pts.size() : pts.size() - 1;
        for (int i = 0; i < edges; i++) {
            double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
            line(g, a[0], a[1], b[0], b[1], colour);
        }
        if (polygon && !lasso.isEmpty()) {
            double[] f = canvas.phys(lasso.get(0)[0], lasso.get(0)[1]);
            int gs = canvas.guiScale();
            g.fill((int) f[0] - 3 * gs, (int) f[1] - 3 * gs, (int) f[0] + 3 * gs, (int) f[1] + 3 * gs, colour);
        }
    }

    private void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int colour) {
        int w = Math.max(1, canvas.guiScale());
        double len = Math.hypot(x1 - x0, y1 - y0);
        int n = Math.max(1, (int) Math.ceil(len / w));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * i / n), y = (int) Math.round(y0 + (y1 - y0) * i / n);
            g.fill(x, y, x + w, y + w, colour);
        }
    }

    /** The count, size and time of the selection, above the Generate button. */
    public void drawPanel(GuiGraphicsExtractor g, Font font, int screenW, int screenH) {
        if (!active || !canGenerate) return;
        ChunkSelection s = selection;
        List<String> lines = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return;
        } else {
            long n = s.count();
            if (!counted) countGenerated(s);
            double km2 = n * 256.0 * metersPerBlock * metersPerBlock / 1e6;
            lines.add(Component.translatable("orbisterrarum.map.select.count", String.format(Locale.ROOT, "%,d", n),
                    String.format(Locale.ROOT, km2 >= 10 ? "%,.0f" : "%,.1f", km2)).getString());
            lines.add(Component.translatable("orbisterrarum.map.select.size", size((long) (n * com.berg.orbis.client.PreviewPlan.KB_PER_CHUNK * 1024)),
                    time(n / com.berg.orbis.client.PreviewPlan.CHUNKS_PER_SECOND)).getString());
            long gen = generatedKnown;
            if (gen > 0) lines.add(Component.translatable("orbisterrarum.map.select.generated", String.format(Locale.ROOT, "%,d", gen)).getString());
            if (!canGenerate) lines.add(Component.translatable("orbisterrarum.map.select.preview.note").getString());
            ceilingLines(s, n, lines);
        }
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        int x = screenW - w - 10, y = screenH - (canGenerate ? 62 : 40) - lines.size() * 10;
        g.fill(x - 4, y - 4, screenW - 2, y + lines.size() * 10 + 1, 0xC0000000);
        for (String l : lines) {
            g.text(font, l, x, y, l.startsWith("\u26A0") ? 0xFFFFB020 : 0xFFFFFFFF);
            y += 10;
        }
    }

    /** The selection the ceiling check was made for, and its result (null while it runs or when it fits). */
    private volatile String peakKey;
    private volatile com.berg.orbis.worldgen.WorldHeight.Peak peak;

    /**
     * Two warning lines when the selection's mountains are higher than this world allows (see
     * PregenTask.warnIfTooLow, which tells the players again when the sweep starts). Worked out in the background
     * for each new selection; only in singleplayer, where the game's own world model is at hand.
     */
    private void ceilingLines(ChunkSelection s, long count, List<String> lines) {
        com.berg.orbis.worldgen.WorldModel model = com.berg.orbis.OrbisMod.model();
        if (model == null || !mc.hasSingleplayerServer()) return;
        String key = System.identityHashCode(s) + ":" + count;
        if (!key.equals(peakKey)) {
            peakKey = key;
            peak = null;
            java.util.concurrent.CompletableFuture.supplyAsync(() -> com.berg.orbis.worldgen.WorldHeight.peak(model, s), net.minecraft.util.Util.backgroundExecutor())
                    .thenAccept(p -> {
                        if (key.equals(peakKey)) peak = p;
                    });
        }
        com.berg.orbis.worldgen.WorldHeight.Peak p = peak;
        if (p == null || p.squeezedBy() <= com.berg.orbis.worldgen.PregenTask.SQUEEZE_WARNING_BLOCKS) return;
        lines.add("\u26A0 " + Component.translatable("orbisterrarum.map.select.ceiling", String.format(Locale.ROOT, "%,d", Math.round(p.unsqueezedY())),
                String.format(Locale.ROOT, "%,d", model.cfg().maxY())).getString());
        lines.add("\u26A0 " + Component.translatable("orbisterrarum.map.select.ceiling2", String.format(Locale.ROOT, "%,d", p.squeezedBy())).getString());
    }

    /** Chunks of the selection already generated, as far as the map knows (the regions it has seen). */
    private void countGenerated(ChunkSelection s) {
        counted = true;
        if (!BlockMapClient.available() || s.count() > 4_000_000) return;
        long n = 0;
        for (int cz = s.firstRow(); cz <= s.lastRow(); cz++) {
            int[] runs = s.rawRuns(cz);
            if (runs == null) continue;
            for (int i = 0; i < runs.length; i += 2) {
                for (int cx = runs[i]; cx <= runs[i + 1]; cx++) {
                    long[] mask = BlockMapClient.mask(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
                    if (mask == null) continue;
                    int bit = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
                    if ((mask[bit >> 6] & (1L << (bit & 63))) != 0) n++;
                }
            }
        }
        generatedKnown = n;
    }

    private static String size(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
    }

    private static String time(double seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%.1f h", seconds / 3600) : String.format(Locale.ROOT, "%.0f min", Math.max(1, seconds / 60));
    }

    // ------------------------------------------------------------------ storage

    private static Path file() {
        return OrbisMod.configDir().resolve("map-selections.json");
    }

    private static synchronized Map<String, List<Stored>> load() {
        if (saved != null) return saved;
        saved = new HashMap<>();
        try {
            if (Files.exists(file())) {
                Map<String, List<Stored>> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, List<Stored>>>() {}.getType());
                if (m != null) saved.putAll(m);
            }
        } catch (Exception e) {
            System.err.println("[orbis] Could not read map selections: " + e);
        }
        return saved;
    }

    private void save() {
        synchronized (MapSelectTool.class) {
            saveLocked();
        }
    }

    private void saveLocked() {
        List<Stored> list = new ArrayList<>();
        for (Shape s : shapes) list.add(new Stored(s.subtract(), s.latLon()));
        Map<String, List<Stored>> all = load();
        if (list.isEmpty()) all.remove(worldKey);
        else all.put(worldKey, list);

        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[orbis] Could not save map selections: " + e);
        }
    }
}
