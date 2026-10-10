package com.berg.orbis.client.map;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.client.MapTiles;
import com.berg.orbis.net.Geocoder;
import com.berg.orbis.net.WorldInfoPayload;
import com.berg.orbis.osm.CoordinateMapper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The world map (key B by default): the real world under the Minecraft world, as a street map, satellite photos with place
 * names, or elevation in colour, lined up with the blocks through the world's own projection. Shows you (an arrow
 * facing where you look), other players in sight, the world spawn, your marks and a searched place; drag to
 * move, scroll to zoom, right-click to teleport, mark a place or copy its coordinates. Needs the server to run
 * Orbis Terrarum (it sends where the world sits on Earth) and only works in the overworld. Operators also get
 * {@link MapSelectTool}: select an area with a rectangle, ellipse or lasso and pre-generate it.
 */
public final class WorldMapScreen extends Screen {
    private static final int TOP_H = 24;
    private static final int BG = 0xFF1E2227, TOP_BG = 0xE8101418;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0, YELLOW = 0xFFFACC15, RED = 0xFFF87171, CYAN = 0xFF67E8F9, GREEN = 0xFF4ADE80;
    private static MapTiles.Layer lastLayer = MapTiles.Layer.STREET;
    private net.minecraft.client.gui.components.Button nightButton;
    /** The pre-generation's Stop and Resume (in its panel) and the hard limit's switch (above Generate): operators only. */
    private Button pregenStop, pregenResume, limitButton;
    private static double lastScale = -1;
    /** The Minecraft layer's opacity: 0 off, then 35%, 70%, 100%. */
    private static final int[] BLOCK_ALPHA = {0, 90, 180, 255};
    private static int blockAlpha = 2;
    private static boolean greyOut = true;
    private String layerHint;

    private final WorldInfoPayload info;
    private final CoordinateMapper mapper;
    private final MapCanvas canvas = new MapCanvas(lastLayer);
    private final String worldKey;
    private List<MapMarks.Mark> marks;
    private EditBox searchBox;
    private boolean viewSet, dragging, searching;
    private String status;
    private boolean statusError;
    private long statusUntil;
    private double[] found;
    private String foundName;

    /** The right-click menu: where it is, the place it acts on, and its entries. */
    private int menuX, menuY;
    private double[] menuAt;
    private MapMarks.Mark menuMark;
    private final List<String> menuItems = new ArrayList<>();

    /** Area selection for pre-generation (operators only), null otherwise. */
    private MapSelectTool select;
    /** A right-button press on the map while selecting: a drag pans, a click opens the menu. */
    private boolean rightDown, rightMoved;
    private double rightX, rightY;
    /** Space held while selecting: the left button pans instead of drawing. */
    private boolean spaceDown;

    public WorldMapScreen(WorldInfoPayload info) {
        super(Component.translatable("orbisterrarum.map.title"));
        this.info = info;
        this.mapper = info == null ? null : new CoordinateMapper(info.originLat(), info.originLon(), info.metersPerBlock(),
                CoordinateMapper.Projection.of(info.projection()));
        this.worldKey = MapMarks.worldKey(net.minecraft.client.Minecraft.getInstance());
        this.marks = MapMarks.get(worldKey);
    }

    private int gs() {
        return Math.max(1, minecraft.getWindow().getGuiScale());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        canvas.setBounds(0, TOP_H, width, height, gs());
        String query = searchBox != null ? searchBox.getValue() : "";
        boolean markersInBar = width >= 560; // on a large GUI scale it sits under the bar, top left, instead
        // The bar, left to right with one gap everywhere: search box, Search, [Markers], Blocks, Grey, layer, Me. The box
        // takes the room up to 360; past that the gaps share what is left, so nothing bunches at one end.
        int[] bw = markersInBar ? new int[]{56, 80, 96, 72, 96, 46} : new int[]{56, 96, 72, 96, 46};
        int sum = 0;
        for (int w : bw) sum += w;
        int boxW = Math.max(80, Math.min(360, width - 8 - sum - bw.length * 4));
        int gap = Math.max(4, (width - 8 - boxW - sum) / bw.length);
        int[] bx = new int[bw.length];
        for (int i = 0, x = 4 + boxW + gap; i < bw.length; x += bw[i] + gap, i++) bx[i] = x;
        int bi = 0;
        searchBox = new EditBox(font, 4, 3, boxW, 18, Component.translatable("orbisterrarum.map.search"));
        searchBox.setMaxLength(200);
        searchBox.setValue(query);
        // The hint cut to the box (it is not clipped, and ran on under the Search button).
        String hint = Component.translatable("orbisterrarum.map.search.hint").getString();
        if (font.width(hint) > boxW - 10) hint = font.plainSubstrByWidth(hint, Math.max(0, boxW - 10 - font.width("..."))).stripTrailing() + "...";
        searchBox.setHint(Component.literal(hint));
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.search.go"), b -> search())
                .bounds(bx[bi++], 2, 56, 20).build());
        addRenderableWidget(Button.builder(markersLabel(), b -> {
            double s = OrbisMod.config().mapMarkerScale;
            int i = 0;
            while (i < MARKER_SCALES.length && MARKER_SCALES[i] <= s + 1e-6) i++;
            double next = MARKER_SCALES[i % MARKER_SCALES.length];
            OrbisMod.updateConfig(c -> c.mapMarkerScale = next);
            b.setMessage(markersLabel());
        }).bounds(markersInBar ? bx[bi++] : 4, markersInBar ? 2 : TOP_H + 4, 80, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.markers.tip"))).build());
        addRenderableWidget(Button.builder(blocksLabel(), b -> {
            blockAlpha = (blockAlpha + 1) % BLOCK_ALPHA.length;
            b.setMessage(blocksLabel());
        }).bounds(bx[bi++], 2, 96, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.blocks.tip"))).build());
        addRenderableWidget(Button.builder(greyLabel(), b -> {
            greyOut = !greyOut;
            b.setMessage(greyLabel());
        }).bounds(bx[bi++], 2, 72, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.grey.tip"))).build());
        if (canvas.layer.isStreet()) canvas.layer = MapTiles.Layer.street(OrbisMod.config().streetMapDark);
        addRenderableWidget(Button.builder(Component.literal(canvas.layer.label), b -> {
            canvas.layer = canvas.layer.next();
            lastLayer = canvas.layer;
            b.setMessage(Component.literal(canvas.layer.label));
            if (nightButton != null) nightButton.visible = canvas.layer.isStreet();
        }).bounds(bx[bi++], 2, 96, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.layer.tip"))).build());
        // Night mode for the street map: on the map's right edge, under the credit line.
        nightButton = addRenderableWidget(MapTiles.nightButton(() -> canvas.layer, l -> {
            canvas.layer = l;
            lastLayer = l;
        }, width - 24, TOP_H + 14));
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.me"), b -> centreOnPlayer())
                .bounds(bx[bi++], 2, 46, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.me.tip"))).build());
        if (select == null && mapper != null && MapSelectTool.allowed(minecraft)) {
            select = new MapSelectTool(minecraft, canvas, mapper, info.metersPerBlock(), worldKey, true, s -> say(s, false, 8000));
        }
        if (select != null) select.addWidgets(this::addRenderableWidget, 4, markersInBar ? TOP_H + 4 : TOP_H + 28, width, height);
        int[] panel = pregenPanelBounds();
        pregenStop = addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.pregen.stop"),
                        b -> control(com.berg.orbis.net.PregenControlPayload.Action.STOP))
                .bounds(panel[2] - 58, panel[1] + 12, 52, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.pregen.stop.tip"))).build());
        pregenResume = addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.pregen.resume"),
                        b -> control(com.berg.orbis.net.PregenControlPayload.Action.RESUME))
                .bounds(panel[2] - 58, panel[1] + 12, 52, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.pregen.resume.tip"))).build());
        limitButton = addRenderableWidget(Button.builder(limitLabel(), b -> {
                    com.berg.orbis.net.AllowedAreaPayload limit = com.berg.orbis.client.OrbisClient.allowedArea;
                    control(limit != null && limit.on() ? com.berg.orbis.net.PregenControlPayload.Action.LIMIT_OFF
                            : com.berg.orbis.net.PregenControlPayload.Action.LIMIT_ON);
                })
                .bounds(width - 104, height - 82, 100, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.limit.tip"))).build());
        refreshPregenControls();
        if (!viewSet && mapper != null) {
            centreOnPlayer();
            if (lastScale > 0) canvas.scale = lastScale;
            else {
                double[] ll = playerLatLon();
                canvas.showMetresAcross(ll[0], 1500 * info.metersPerBlock());
            }
            canvas.clamp();
            viewSet = true;
            status = Component.translatable("orbisterrarum.map.help", com.berg.orbis.client.OrbisClient.MAP_KEY == null ? Component.literal("B")
                    : com.berg.orbis.client.OrbisClient.MAP_KEY.getTranslatedKeyMessage()).getString();
            statusUntil = System.currentTimeMillis() + 5000;
        }
    }

    // ------------------------------------------------------------------ pre-generation (stage 1 of moving the commands onto the map)

    /** Operators of a server running Orbis can run its pre-generation from the map (the server checks again). */
    private boolean canControlPregen() {
        return mapper != null && MapSelectTool.allowed(minecraft)
                && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(com.berg.orbis.net.PregenControlPayload.TYPE);
    }

    private void control(com.berg.orbis.net.PregenControlPayload.Action action) {
        if (!canControlPregen()) return;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.berg.orbis.net.PregenControlPayload(action));
    }

    private Component limitLabel() {
        com.berg.orbis.net.AllowedAreaPayload limit = com.berg.orbis.client.OrbisClient.allowedArea;
        return Component.translatable("orbisterrarum.map.limit", Component.translatable(limit != null && limit.on()
                ? "orbisterrarum.map.limit.on" : "orbisterrarum.map.limit.off"));
    }

    /** The pre-generation panel, bottom centre above the readout: left, top, right, bottom. */
    private int[] pregenPanelBounds() {
        int w = Math.max(200, Math.min(320, width - 240));
        int left = (width - w) / 2;
        return new int[]{left, height - 64, left + w, height - 20};
    }

    /** Shows the buttons that fit what the server's pre-generation is doing now. */
    private void refreshPregenControls() {
        if (pregenStop == null) return;
        com.berg.orbis.net.PregenStatusPayload st = com.berg.orbis.client.OrbisClient.pregenStatus;
        boolean ops = canControlPregen() && (minecraft.level == null || minecraft.level.dimension() == Level.OVERWORLD);
        pregenStop.visible = ops && st.running();
        pregenResume.visible = ops && !st.running() && !st.resumable().isEmpty();
        limitButton.visible = ops;
        limitButton.setMessage(limitLabel());
    }

    /** What the server's pre-generation is doing: its name, a bar, and how far it has got; or that a stopped one can be resumed. */
    private void drawPregenPanel(GuiGraphicsExtractor g) {
        com.berg.orbis.net.PregenStatusPayload st = com.berg.orbis.client.OrbisClient.pregenStatus;
        if (!st.running() && st.resumable().isEmpty()) return;
        int[] p = pregenPanelBounds();
        boolean button = pregenStop.visible || pregenResume.visible;
        int textRight = button ? p[2] - 64 : p[2] - 6;
        int textW = textRight - p[0] - 6;
        g.fill(p[0], p[1], p[2], p[3], 0xD0000000);
        if (st.running()) {
            String title = Component.translatable("orbisterrarum.map.pregen.running", st.label()).getString()
                    + (st.waiting().isEmpty() ? "" : " (" + st.waiting() + ")");
            g.text(font, font.plainSubstrByWidth(title, textW), p[0] + 6, p[1] + 5, WHITE);
            int barTop = p[1] + 18, barW = textW;
            g.fill(p[0] + 6, barTop, p[0] + 6 + barW, barTop + 7, 0xFF404850);
            g.fill(p[0] + 6, barTop, p[0] + 6 + Math.round(barW * Math.max(0f, Math.min(1f, st.fraction()))), barTop + 7, 0xFF4ADE80);
            String pct = Math.round(st.fraction() * 100) + "%";
            g.text(font, pct, p[0] + 6 + (barW - font.width(pct)) / 2, barTop - 1, WHITE);
            g.text(font, font.plainSubstrByWidth(st.detail(), textW), p[0] + 6, p[1] + 30, 0xFFB0B8C0);
        } else {
            List<FormattedCharSequence> lines = font.split(Component.translatable("orbisterrarum.map.pregen.stopped", st.resumable()), textW);
            int y = p[1] + (p[3] - p[1] - lines.size() * 10) / 2 + 1;
            for (FormattedCharSequence l : lines) {
                g.text(font, l, p[0] + 6, y, WHITE);
                y += 10;
            }
        }
    }

    private Component blocksLabel() {
        return blockAlpha == 0 ? Component.translatable("orbisterrarum.map.blocks.off")
                : Component.translatable("orbisterrarum.map.blocks.on", Math.round(BLOCK_ALPHA[blockAlpha] * 100 / 255.0) + "%");
    }

    private static final double[] MARKER_SCALES = {0.5, 0.75, 1.0, 1.5, 2.0, 3.0};

    private Component markersLabel() {
        return Component.translatable("orbisterrarum.map.markers", Math.round(OrbisMod.config().mapMarkerScale * 100) + "%");
    }

    private static float markerScale() {
        return (float) OrbisMod.config().mapMarkerScale;
    }

    private Component greyLabel() {
        return Component.translatable(greyOut ? "orbisterrarum.map.grey.on" : "orbisterrarum.map.grey.off");
    }

    private double[] playerLatLon() {
        return mapper.toLatLonExact(minecraft.player.getX(), minecraft.player.getZ());
    }

    private void centreOnPlayer() {
        if (mapper == null || minecraft.player == null) return;
        double[] ll = playerLatLon();
        canvas.centerOn(ll[0], ll[1]);
    }

    private void search() {
        String q = searchBox.getValue().trim();
        if (q.isEmpty() || searching) return;
        searching = true;
        say(Component.translatable("orbisterrarum.map.searching", q).getString(), false, 0);
        Geocoder.lookup(q).whenComplete((r, error) -> minecraft.execute(() -> {
            searching = false;
            if (error != null || r == null) {
                say(Component.translatable("orbisterrarum.map.notfound", q).getString(), true, 5000);
                return;
            }
            found = new double[]{r.lat(), r.lon()};
            foundName = r.name().split(",")[0].trim();
            canvas.centerOn(r.lat(), r.lon());
            if (mapper != null) canvas.showMetresAcross(r.lat(), Math.max(800, 600 * info.metersPerBlock()));
            say(r.name(), false, 5000);
        }));
    }

    private void say(String s, boolean error, long forMs) {
        status = s;
        statusError = error;
        statusUntil = forMs > 0 ? System.currentTimeMillis() + forMs : 0;
    }

    @Override
    public void removed() {
        lastScale = canvas.scale;
        canvas.close(minecraft);
        super.removed();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (!menuItems.isEmpty()) {
            int i = menuItemAt(e.x(), e.y());
            if (i >= 0) runMenu(menuItems.get(i));
            menuItems.clear();
            return true;
        }
        if (super.mouseClicked(e, doubleClick)) return true;
        if (mapper == null || !canvas.contains(e.x(), e.y())) return false;
        setFocused(null);
        boolean selecting = select != null && select.active;
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (selecting) {
                rightDown = true;
                rightMoved = false;
                rightX = e.x();
                rightY = e.y();
            } else {
                openMenu((int) e.x(), (int) e.y());
            }
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_MIDDLE || (e.button() == InputConstants.MOUSE_BUTTON_LEFT && selecting && spaceDown)) {
            dragging = true;
            return true;
        }
        if (selecting && select.mousePressed(e, doubleClick)) return true;
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (doubleClick) canvas.zoomAt(e.x(), e.y(), 2);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (select != null && select.mouseDragged(e)) return true;
        if (rightDown && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (rightMoved || Math.hypot(e.x() - rightX, e.y() - rightY) > 3) {
                rightMoved = true;
                canvas.pan(dx, dy);
            }
            return true;
        }
        if (dragging) {
            canvas.pan(dx, dy);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (select != null && select.mouseReleased(e)) return true;
        if (rightDown && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            rightDown = false;
            if (!rightMoved) openMenu((int) rightX, (int) rightY);
            return true;
        }
        if (dragging && (e.button() == InputConstants.MOUSE_BUTTON_LEFT || e.button() == InputConstants.MOUSE_BUTTON_MIDDLE)) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (mapper != null && sy != 0 && canvas.contains(x, y)) {
            canvas.zoomAt(x, y, Math.pow(2, sy * 0.5));
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if ((e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) && searchBox.isFocused()) {
            search();
            return true;
        }
        if (!searchBox.isFocused() && select != null) {
            if (select.keyPressed(e)) return true;
            if (select.active && e.key() == InputConstants.KEY_SPACE) {
                spaceDown = true;
                return true;
            }
        }
        if (!searchBox.isFocused() && com.berg.orbis.client.OrbisClient.MAP_KEY != null && com.berg.orbis.client.OrbisClient.MAP_KEY.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (e.key() == InputConstants.KEY_SPACE) spaceDown = false;
        return super.keyReleased(e);
    }

    private void openMenu(int x, int y) {
        menuAt = canvas.latLonAt(x, y);
        menuMark = markNear(x, y);
        menuItems.clear();
        // /tpll (Orbis on the server) or vanilla /tp (a server without it) is for operators; the server only sends a
        // player the commands they may run.
        int[] at = mapper == null ? null : mapper.toBlock(menuAt[0], menuAt[1]);
        if (minecraft.player != null && (minecraft.player.connection.getCommands().getRoot().getChild("tpll") != null
                || minecraft.player.connection.getCommands().getRoot().getChild("tp") != null)
                && (at == null || minecraft.level == null || minecraft.level.getWorldBorder().isWithinBounds(at[0], at[1]))) {
            menuItems.add("teleport");
        }
        menuItems.add(menuMark == null ? "mark" : "unmark");
        menuItems.add("copy");
        int w = 0;
        for (String s : menuItems) w = Math.max(w, font.width(menuLabel(s)));
        menuX = Math.min(x, width - w - 12);
        menuY = Math.min(y, height - menuItems.size() * 12 - 6);
    }

    private String menuLabel(String item) {
        return Component.translatable("orbisterrarum.map.menu." + item).getString();
    }

    private int menuItemAt(double x, double y) {
        int w = 0;
        for (String s : menuItems) w = Math.max(w, font.width(menuLabel(s)));
        if (x < menuX || x > menuX + w + 10) return -1;
        int i = (int) Math.floor((y - menuY - 2) / 12);
        return i >= 0 && i < menuItems.size() ? i : -1;
    }

    private void runMenu(String item) {
        double lat = menuAt[0], lon = menuAt[1];
        int[] b = mapper.toBlock(lat, lon);
        switch (item) {
            case "teleport" -> {
                if (minecraft.player.connection.getCommands().getRoot().getChild("tpll") != null) {
                    minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tpll %.6f %.6f", lat, lon));
                } else {
                    // A server without Orbis (no /tpll): vanilla's teleport, keeping the height (operators only).
                    minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %d ~ %d", b[0], b[1]));
                }
                onClose();
            }
            case "mark" -> {
                String name = String.format(Locale.ROOT, "%d, %d", b[0], b[1]);
                MapMarks.Mark m = new MapMarks.Mark(name, lat, lon);
                MapMarks.add(worldKey, m);
                marks = MapMarks.get(worldKey);
                say(Component.translatable("orbisterrarum.map.marked", name).getString(), false, 3000);
            }
            case "unmark" -> {
                MapMarks.remove(worldKey, menuMark);
                marks = MapMarks.get(worldKey);
            }
            case "copy" -> {
                String text = String.format(Locale.ROOT, "%.6f, %.6f (block %d, %d)", lat, lon, b[0], b[1]);
                minecraft.keyboardHandler.setClipboard(text);
                say(Component.translatable("orbisterrarum.map.copied", text).getString(), false, 3000);
            }
            default -> { }
        }
    }

    private MapMarks.Mark markNear(double x, double y) {
        for (MapMarks.Mark m : marks) {
            double[] s = canvas.gui(m.lat(), m.lon());
            if (Math.abs(s[0] - x) <= 6 && Math.abs(s[1] - y) <= 6) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        canvas.setBounds(0, TOP_H, width, height, gs());
        if (mapper == null) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("orbisterrarum.map.nodata"), Math.min(360, width - 40));
            int y = height / 2 - lines.size() * 5;
            for (FormattedCharSequence l : lines) {
                g.centeredText(font, l, width / 2, y, WHITE);
                y += 10;
            }
        } else if (minecraft.level != null && minecraft.level.dimension() != Level.OVERWORLD) {
            g.centeredText(font, Component.translatable("orbisterrarum.map.overworld"), width / 2, height / 2, WHITE);
        } else {
            canvas.draw(g, minecraft);
            drawMinecraftLayer(g);
            drawOutsideLimit(g);
            drawOutsideBorder(g);
            if (select != null && select.active) select.draw(g, font, mouseX, mouseY);
            g.enableScissor(canvas.left(), canvas.top(), canvas.right(), canvas.bottom());
            drawSpawn(g);
            for (MapMarks.Mark m : marks) pin(g, canvas.gui(m.lat(), m.lon()), YELLOW, m.name());
            if (found != null) pin(g, canvas.gui(found[0], found[1]), RED, foundName);
            drawPlayers(g);
            g.disableScissor();
            drawScaleBar(g);
            drawLimitLegend(g);
            if (select != null) select.drawPanel(g, font, width, height);
            if (canvas.contains(mouseX, mouseY) && menuItems.isEmpty()) drawReadout(g, mouseX, mouseY);
            String credit = canvas.layer == com.berg.orbis.client.MapTiles.Layer.ELEVATION ? "Heights © Mapterhorn, names © Esri"
                    : canvas.layer == com.berg.orbis.client.MapTiles.Layer.STREET || canvas.layer == com.berg.orbis.client.MapTiles.Layer.STREET_DARK
                    ? com.berg.orbis.client.StreetTiles.CREDIT : "Map tiles © Esri";
            com.berg.orbis.client.MapTiles.drawCredit(g, font, credit, width - 4, TOP_H + 3, width);
            // Bottom right, above the bottom line (the readout).
            if (canvas.layer == com.berg.orbis.client.MapTiles.Layer.ELEVATION) {
                com.berg.orbis.client.ElevationTiles.drawLegend(g, font, width - 4, limitButton != null && limitButton.visible ? height - 86
                        : select != null && select.generateShown() ? height - 62 : height - 19);
            }
        }
        g.fill(0, 0, width, TOP_H, TOP_BG);
        drawStatus(g);
        refreshPregenControls();
        if (mapper != null && (minecraft.level == null || minecraft.level.dimension() == Level.OVERWORLD)) drawPregenPanel(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        MapTiles.drawNightIcon(g, nightButton, canvas.layer);
        if (select != null) select.drawIcons(g);
        if (!menuItems.isEmpty()) drawMenu(g, mouseX, mouseY);
    }

    /**
     * The Minecraft map over the real one (each block's real colour, shaded as on a map item, from the server), and a grey veil over
     * chunks not generated yet. Regions nearest the centre are asked for first.
     */
    private void drawMinecraftLayer(GuiGraphicsExtractor g) {
        layerHint = null;
        if ((blockAlpha == 0 && !greyOut) || !BlockMapClient.available()) return;
        BlockMapClient.beginFrame(minecraft);
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[][] corners = {{canvas.left(), canvas.top()}, {canvas.right(), canvas.top()}, {canvas.left(), canvas.bottom()}, {canvas.right(), canvas.bottom()}};
        for (double[] c : corners) {
            double[] ll = canvas.latLonAt(c[0], c[1]);
            double[] b = mapper.toBlockExact(ll[0], ll[1]);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxZ = Math.max(maxZ, b[1]);
        }
        int rx0 = (int) Math.floor(minX / 512), rx1 = (int) Math.floor(maxX / 512);
        int rz0 = (int) Math.floor(minZ / 512), rz1 = (int) Math.floor(maxZ / 512);
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 400) {
            layerHint = Component.translatable("orbisterrarum.map.zoomin").getString();
            return;
        }
        double blocksPerPx = canvas.metresPerGuiPixel() / canvas.guiScale() / info.metersPerBlock();
        int level = (int) Math.max(0, Math.min(4, Math.floor(Math.log(Math.max(1, blocksPerPx)) / Math.log(2))));
        int size = BlockMapClient.tileSize(level);
        // Nearest the centre first, so the part being looked at arrives first.
        double[] midLl = canvas.latLonAt((canvas.left() + canvas.right()) / 2.0, (canvas.top() + canvas.bottom()) / 2.0);
        double[] mid = mapper.toBlockExact(midLl[0], midLl[1]);
        List<int[]> order = new ArrayList<>();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) order.add(new int[]{rx, rz});
        }
        double mrx = mid[0] / 512 - 0.5, mrz = mid[1] / 512 - 0.5;
        order.sort((a, b) -> Double.compare(Math.hypot(a[0] - mrx, a[1] - mrz), Math.hypot(b[0] - mrx, b[1] - mrz)));
        int colour = (BLOCK_ALPHA[blockAlpha] << 24) | 0xFFFFFF;
        canvas.beginOverlay(g);
        for (int[] r : order) {
            double[] a = physOfBlock(r[0] * 512.0, r[1] * 512.0), b = physOfBlock((r[0] + 1) * 512.0, (r[1] + 1) * 512.0);
            int x0 = (int) Math.round(Math.min(a[0], b[0])), y0 = (int) Math.round(Math.min(a[1], b[1]));
            int x1 = (int) Math.round(Math.max(a[0], b[0])), y1 = (int) Math.round(Math.max(a[1], b[1]));
            if (x1 <= x0 || y1 <= y0) continue;
            if (blockAlpha > 0) {
                net.minecraft.resources.Identifier id = BlockMapClient.tile(r[0], r[1], level);
                if (id != null) {
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x0, y0, 0f, 0f, x1 - x0, y1 - y0, size, size, size, size, colour);
                }
            }
            if (greyOut) {
                net.minecraft.resources.Identifier grey = BlockMapClient.grey(r[0], r[1]);
                if (grey != null) {
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, grey, x0, y0, 0f, 0f, x1 - x0, y1 - y0, 32, 32, 32, 32, 0xFFFFFFFF);
                }
            }
        }
        canvas.endOverlay(g);
        BlockMapClient.send();
    }

    /** Outside the hard limit: a dark red tint (the not-generated haze is light grey, and never drawn there). */
    private static final int LIMIT_SHADE = 0x9C3A0A12, LIMIT_EDGE = 0xFFF87171, VEIL_SWATCH = 0xFFF5A04A;

    /**
     * With the server's hard limit on: everything outside the allowed area darkened (it never generates), with a red
     * line along its edge when zoomed in far enough to see chunks.
     */
    private void drawOutsideLimit(GuiGraphicsExtractor g) {
        com.berg.orbis.net.AllowedAreaPayload limit = com.berg.orbis.client.OrbisClient.allowedArea;
        if (limit == null || !limit.on() || limit.area().isEmpty()) return;
        com.berg.orbis.worldgen.ChunkSelection a = limit.area();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] c : new double[][]{{canvas.left(), canvas.top()}, {canvas.right(), canvas.top()}, {canvas.left(), canvas.bottom()}, {canvas.right(), canvas.bottom()}}) {
            double[] ll = canvas.latLonAt(c[0], c[1]);
            double[] b = mapper.toBlockExact(ll[0], ll[1]);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxZ = Math.max(maxZ, b[1]);
        }
        int cx0 = (int) Math.floor(minX / 16) - 1, cx1 = (int) Math.floor(maxX / 16) + 1;
        int r0 = (int) Math.floor(minZ / 16) - 1, r1 = (int) Math.floor(maxZ / 16) + 1;
        double rowPx = Math.abs(physOfBlock(0, 16)[1] - physOfBlock(0, 0)[1]);
        int step = Math.max(1, (int) Math.ceil(1.0 / Math.max(1e-6, rowPx)));
        int[] view = {cx0, cx1};
        canvas.beginOverlay(g);
        // North and south of the area: whole bands.
        if (r0 < a.firstRow()) shadeBlocks(g, cx0, r0, cx1, Math.min(r1, a.firstRow() - 1), LIMIT_SHADE);
        if (r1 > a.lastRow()) shadeBlocks(g, cx0, Math.max(r0, a.lastRow() + 1), cx1, r1, LIMIT_SHADE);
        int from = Math.max(r0, a.firstRow()), to = Math.min(r1, a.lastRow());
        for (int cz = from; cz <= to; cz += step) {
            int[] runs = a.rawRuns(cz);
            int[] out = runs == null ? view : com.berg.orbis.worldgen.ChunkSelection.difference(view, runs);
            int last = Math.min(to, cz + step - 1);
            for (int i = 0; i < out.length; i += 2) shadeBlocks(g, out[i], cz, out[i + 1], last, LIMIT_SHADE);
        }
        if (step == 1 && rowPx >= 2) {
            int t = Math.max(1, (int) Math.round(rowPx / 12));
            for (int cz = from; cz <= to; cz++) {
                int[] runs = a.rawRuns(cz);
                if (runs == null) continue;
                for (int i = 0; i < runs.length; i += 2) {
                    if (runs[i + 1] < cx0 || runs[i] > cx1) continue;
                    edgeLine(g, runs[i] * 16.0, cz * 16.0, runs[i] * 16.0, (cz + 1) * 16.0, t);
                    edgeLine(g, (runs[i + 1] + 1) * 16.0, cz * 16.0, (runs[i + 1] + 1) * 16.0, (cz + 1) * 16.0, t);
                }
                for (int side = 0; side < 2; side++) {
                    int[] other = a.rawRuns(side == 0 ? cz - 1 : cz + 1);
                    int[] open = other == null ? runs : com.berg.orbis.worldgen.ChunkSelection.difference(runs, other);
                    double ez = (side == 0 ? cz : cz + 1) * 16.0;
                    for (int i = 0; i < open.length; i += 2) {
                        int x0 = Math.max(open[i], cx0), x1 = Math.min(open[i + 1], cx1);
                        if (x0 <= x1) edgeLine(g, x0 * 16.0, ez, (x1 + 1) * 16.0, ez, t);
                    }
                }
            }
        }
        canvas.endOverlay(g);
    }

    /**
     * Beyond the world border, shaded as outside the allowed area: in a cubic world it is much nearer than vanilla's (Cubic Chunks packs
     * positions with more bits for height), and the far side of the Earth can lie beyond it.
     */
    private void drawOutsideBorder(GuiGraphicsExtractor g) {
        if (minecraft.level == null || mapper == null) return;
        net.minecraft.world.level.border.WorldBorder border = minecraft.level.getWorldBorder();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] c : new double[][]{{canvas.left(), canvas.top()}, {canvas.right(), canvas.top()}, {canvas.left(), canvas.bottom()}, {canvas.right(), canvas.bottom()}}) {
            double[] ll = canvas.latLonAt(c[0], c[1]);
            double[] b = mapper.toBlockExact(ll[0], ll[1]);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxZ = Math.max(maxZ, b[1]);
        }
        if (minX >= border.getMinX() && maxX <= border.getMaxX() && minZ >= border.getMinZ() && maxZ <= border.getMaxZ()) return;
        int cx0 = (int) Math.floor(minX / 16) - 1, cx1 = (int) Math.floor(maxX / 16) + 1;
        int cz0 = (int) Math.floor(minZ / 16) - 1, cz1 = (int) Math.floor(maxZ / 16) + 1;
        int bx0 = (int) Math.floor(border.getMinX() / 16), bx1 = (int) Math.ceil(border.getMaxX() / 16) - 1;
        int bz0 = (int) Math.floor(border.getMinZ() / 16), bz1 = (int) Math.ceil(border.getMaxZ() / 16) - 1;
        canvas.beginOverlay(g);
        if (cz0 < bz0) shadeBlocks(g, cx0, cz0, cx1, Math.min(cz1, bz0 - 1), LIMIT_SHADE);
        if (cz1 > bz1) shadeBlocks(g, cx0, Math.max(cz0, bz1 + 1), cx1, cz1, LIMIT_SHADE);
        int rowFrom = Math.max(cz0, bz0), rowTo = Math.min(cz1, bz1);
        if (rowFrom <= rowTo) {
            if (cx0 < bx0) shadeBlocks(g, cx0, rowFrom, Math.min(cx1, bx0 - 1), rowTo, LIMIT_SHADE);
            if (cx1 > bx1) shadeBlocks(g, Math.max(cx0, bx1 + 1), rowFrom, cx1, rowTo, LIMIT_SHADE);
        }
        canvas.endOverlay(g);
    }

    /** Fills the chunks cx0..cx1 by cz0..cz1 (inclusive), in the overlay's physical pixels. */
    private void shadeBlocks(GuiGraphicsExtractor g, int cx0, int cz0, int cx1, int cz1, int colour) {
        if (cx1 < cx0 || cz1 < cz0) return;
        double[] p = physOfBlock(cx0 * 16.0, cz0 * 16.0), q = physOfBlock((cx1 + 1) * 16.0, (cz1 + 1) * 16.0);
        int x0 = (int) Math.round(Math.max(-1, Math.min(p[0], q[0]))), x1 = (int) Math.round(Math.min(canvas.w() + 1, Math.max(p[0], q[0])));
        int y0 = (int) Math.round(Math.max(-1, Math.min(p[1], q[1]))), y1 = (int) Math.round(Math.min(canvas.h() + 1, Math.max(p[1], q[1])));
        if (x1 > x0 && y1 > y0) g.fill(x0, y0, x1, y1, colour);
    }

    /** A straight edge of the allowed area (block coordinates), {@code t} physical pixels thick. */
    private void edgeLine(GuiGraphicsExtractor g, double xa, double za, double xb, double zb, int t) {
        double[] p = physOfBlock(xa, za), q = physOfBlock(xb, zb);
        int x0 = (int) Math.round(Math.min(p[0], q[0])), x1 = (int) Math.round(Math.max(p[0], q[0]));
        int y0 = (int) Math.round(Math.min(p[1], q[1])), y1 = (int) Math.round(Math.max(p[1], q[1]));
        g.fill(x0 - t / 2, y0 - t / 2, Math.max(x1, x0 + 1) + (t + 1) / 2, Math.max(y1, y0 + 1) + (t + 1) / 2, LIMIT_EDGE);
    }

    private double[] physOfBlock(double x, double z) {
        double[] ll = mapper.toLatLonExact(x, z);
        return canvas.phys(ll[0], ll[1]);
    }

    private double[] guiOfBlock(double x, double z) {
        double[] ll = mapper.toLatLonExact(x, z);
        return canvas.gui(ll[0], ll[1]);
    }

    private void drawSpawn(GuiGraphicsExtractor g) {
        if (minecraft.level == null) return;
        BlockPos p = minecraft.level.getRespawnData().pos();
        double[] s = guiOfBlock(p.getX() + 0.5, p.getZ() + 0.5);
        g.pose().pushMatrix();
        g.pose().translate((float) s[0], (float) s[1]);
        g.pose().scale(markerScale(), markerScale());
        g.fill(-4, -4, 5, 5, 0xFF000000);
        g.fill(-3, -3, 4, 4, GREEN);
        label(g, 7, -4, Component.translatable("orbisterrarum.map.spawn").getString(), GREEN);
        g.pose().popMatrix();
    }

    private void drawPlayers(GuiGraphicsExtractor g) {
        if (minecraft.level == null || minecraft.player == null) return;
        for (AbstractClientPlayer p : minecraft.level.players()) {
            if (p == minecraft.player) continue;
            arrow(g, p.getX(), p.getZ(), p.getYRot(), CYAN, p.getName().getString());
        }
        arrow(g, minecraft.player.getX(), minecraft.player.getZ(), minecraft.player.getYRot(), WHITE, null);
    }

    /** An arrow at a block position pointing the way the player looks (yaw 0 = south), with an optional name. */
    private void arrow(GuiGraphicsExtractor g, double x, double z, float yaw, int colour, String name) {
        double r = Math.toRadians(yaw);
        double[] a = guiOfBlock(x, z), b = guiOfBlock(x - Math.sin(r) * 16, z + Math.cos(r) * 16);
        float theta = (float) Math.atan2(b[0] - a[0], -(b[1] - a[1]));
        float scale = markerScale();
        g.pose().pushMatrix();
        g.pose().translate((float) a[0], (float) a[1]);
        g.pose().scale(scale, scale);
        g.pose().pushMatrix();
        g.pose().rotate(theta);
        arrowHead(g, 10, 0xFF000000);
        arrowHead(g, 8, colour);
        g.pose().popMatrix();
        if (name != null) label(g, 10, -4, name, colour);
        g.pose().popMatrix();
    }

    /**
     * A slim arrowhead pointing up (the way the player faces), notched at the back like a map's position arrow:
     * {@code size} sets the scale, the tip sits well ahead of the centre so the direction reads at a glance.
     */
    private void arrowHead(GuiGraphicsExtractor g, int size, int colour) {
        double top = -size * 1.3, bottom = size * 0.75, notch = size * 0.2, half = size * 0.68;
        for (int y = (int) Math.floor(top); y <= (int) Math.ceil(bottom); y++) {
            double t = (y + 0.5 - top) / (bottom - top);
            if (t < 0 || t > 1) continue;
            double hw = half * t;
            if (y + 0.5 > notch) {
                // The notch at the back: a V cut out between the two barbs.
                double nw = half * (y + 0.5 - notch) / (bottom - notch);
                g.fill((int) Math.round(-hw), y, (int) Math.round(-nw) + 1, y + 1, colour);
                g.fill((int) Math.round(nw), y, (int) Math.round(hw) + 1, y + 1, colour);
            } else {
                g.fill((int) Math.round(-hw), y, (int) Math.round(hw) + 1, y + 1, colour);
            }
        }
    }

    private void pin(GuiGraphicsExtractor g, double[] s, int colour, String name) {
        g.pose().pushMatrix();
        g.pose().translate((float) s[0], (float) s[1]);
        g.pose().scale(markerScale(), markerScale());
        g.fill(-3, -9, 4, -2, 0xFF000000);
        g.fill(-2, -8, 3, -3, colour);
        g.fill(0, -3, 1, 1, 0xFF000000);
        if (name != null && !name.isEmpty()) label(g, 6, -10, name, colour);
        g.pose().popMatrix();
    }

    private void label(GuiGraphicsExtractor g, int x, int y, String text, int colour) {
        g.fill(x - 1, y - 1, x + font.width(text) + 1, y + 9, 0xA0000000);
        g.text(font, text, x, y, colour);
    }

    /** A bar of a round number of blocks, with the real distance it stands for. */
    /** Says what each shading is, above the scale bar: the hard limit's red, and the grey haze when it is on. */
    private void drawLimitLegend(GuiGraphicsExtractor g) {
        com.berg.orbis.net.AllowedAreaPayload limit = com.berg.orbis.client.OrbisClient.allowedArea;
        boolean red = limit != null && limit.on() && !limit.area().isEmpty();
        boolean grey = greyOut && BlockMapClient.available();
        if (!red && !grey) return;
        String redText = Component.translatable("orbisterrarum.map.hardlimit").getString();
        String greyText = Component.translatable("orbisterrarum.map.notyet").getString();
        int rows = (red ? 1 : 0) + (grey ? 1 : 0);
        // Above the scale bar (its box starts at height - 42), lined up with it; where the tool strip comes down that
        // far, beside the scale bar on the same bottom line instead of out over the map.
        int bottom = height - 46, top = bottom - rows * 12 - 2;
        int x = 8;
        if (select != null && select.textLeft(top, bottom, 8) != 8) {
            bottom = height - 25;
            top = bottom - rows * 12 - 2;
            x = scaleRight + 7;
        }
        top += 2;
        int w = Math.max(red ? font.width(redText) : 0, grey ? font.width(greyText) : 0);
        g.fill(x - 3, top - 2, x + w + 13, bottom, 0xA0000000);
        int y = top;
        if (red) {
            g.fill(x, y + 1, x + 7, y + 8, LIMIT_SHADE | 0xFF000000);
            g.outline(x, y + 1, 7, 7, LIMIT_EDGE);
            g.text(font, redText, x + 10, y, WHITE);
            y += 12;
        }
        if (grey) {
            g.fill(x, y + 1, x + 7, y + 8, VEIL_SWATCH);
            g.text(font, greyText, x + 10, y, WHITE);
        }
    }

    /** Right edge of the scale bar's box as last drawn (the legend sits beside it when the strip leaves no room above). */
    private int scaleRight = 8;

    private void drawScaleBar(GuiGraphicsExtractor g) {
        double mpb = info.metersPerBlock();
        double blocksPerPx = canvas.metresPerGuiPixel() / mpb;
        double target = 80 * blocksPerPx;
        double pow = Math.pow(10, Math.floor(Math.log10(target)));
        double nice = target / pow >= 5 ? 5 * pow : target / pow >= 2 ? 2 * pow : pow;
        int px = (int) Math.round(nice / blocksPerPx);
        int y = height - 30, x = select != null ? select.textLeft(y - 12, y + 5, 8) : 8;
        double metres = nice * mpb;
        String text = String.format(Locale.ROOT, "%,.0f blocks (%s)", nice, metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000) : String.format(Locale.ROOT, "%.0f m", metres));
        scaleRight = x + Math.max(px, font.width(text)) + 4;
        g.fill(x - 3, y - 12, scaleRight, y + 5, 0xA0000000);
        g.text(font, text, x, y - 10, WHITE);
        g.fill(x, y, x + px, y + 2, WHITE);
        g.fill(x, y - 3, x + 1, y + 2, WHITE);
        g.fill(x + px - 1, y - 3, x + px, y + 2, WHITE);
    }

    private void drawReadout(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        double[] ll = canvas.latLonAt(mouseX, mouseY);
        int[] b = mapper.toBlock(ll[0], ll[1]);
        List<String> parts = new ArrayList<>(List.of(
                String.format(Locale.ROOT, "%.5f, %.5f", ll[0], ll[1]),
                String.format(Locale.ROOT, "block %d, %d", b[0], b[1])));
        if (minecraft.player != null) {
            double dx = b[0] - minecraft.player.getX(), dz = b[1] - minecraft.player.getZ();
            parts.add(String.format(Locale.ROOT, "%,.0f blocks %s", Math.hypot(dx, dz), compass(dx, dz)));
        }
        if (canvas.layer == com.berg.orbis.client.MapTiles.Layer.ELEVATION) {
            double m = canvas.tiles.elevationAt(ll[0], ll[1], canvas.zoom());
            if (!Double.isNaN(m)) parts.add(String.format(Locale.ROOT, m < 0 ? "%,.0f m below sea level" : "%,.0f m above sea level", Math.abs(m)));
        }
        if (BlockMapClient.available()) {
            int cx = Math.floorDiv(b[0], 16), cz = Math.floorDiv(b[1], 16);
            long[] mask = BlockMapClient.mask(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
            if (mask != null) {
                int bit = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
                if ((mask[bit >> 6] & (1L << (bit & 63))) == 0) parts.add(Component.translatable("orbisterrarum.map.notgenerated").getString());
            }
        }
        String text = String.join("   ", parts);
        int x = select != null ? select.textLeft(height - 15, height - 2, 8) : 8;
        int w = Math.min(font.width(text), width - x - 8);
        g.fill(x - 4, height - 15, x + w + 4, height - 2, 0xC0000000);
        g.text(font, font.plainSubstrByWidth(text, width - x - 8), x, height - 12, WHITE);
    }

    private static String compass(double dx, double dz) {
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        return dirs[(int) Math.round(((a % 360) + 360) % 360 / 45) % 8];
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        if (statusUntil > 0 && System.currentTimeMillis() > statusUntil) {
            status = null;
            statusUntil = 0;
        }
        String s = status;
        if (s == null && mapper != null && canvas.tiles.loading() > 0) s = Component.translatable("orbisterrarum.preview.loadingmap").getString();
        if (s == null) s = layerHint;
        if (s == null) return;
        // Clear of the selection tool strip on the left.
        int left = select != null ? select.textLeft(TOP_H, height, 0) : 0;
        // On a large GUI scale the Markers button (80 wide) sits under the bar, top left, wider than the strip.
        if (width < 560) left = Math.max(left, 4 + 80 + 6);
        int maxW = Math.min(width - left - 24, 380);
        List<FormattedCharSequence> lines = font.split(Component.literal(s), maxW);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int x = left + (width - left - w) / 2, y = TOP_H + 16;
        g.fill(x - 6, y - 4, x + w + 6, y + lines.size() * 10 + 2, 0xD0000000);
        for (FormattedCharSequence l : lines) {
            g.text(font, l, x, y, statusError ? RED : WHITE);
            y += 10;
        }
    }

    private void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = 0;
        for (String s : menuItems) w = Math.max(w, font.width(menuLabel(s)));
        g.fill(menuX - 1, menuY - 1, menuX + w + 11, menuY + menuItems.size() * 12 + 5, 0xFF000000);
        g.fill(menuX, menuY, menuX + w + 10, menuY + menuItems.size() * 12 + 4, 0xF0202830);
        int hover = menuItemAt(mouseX, mouseY);
        for (int i = 0; i < menuItems.size(); i++) {
            int y = menuY + 2 + i * 12;
            if (i == hover) g.fill(menuX + 1, y - 1, menuX + w + 9, y + 11, 0xFF3B4A5A);
            g.text(font, menuLabel(menuItems.get(i)), menuX + 5, y + 1, WHITE);
        }
    }
}
