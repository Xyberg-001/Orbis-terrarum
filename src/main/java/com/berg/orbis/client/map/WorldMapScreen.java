package com.berg.orbis.client.map;

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
 * The world map (key N): the real world under the Minecraft world, as a street map, satellite photos with place
 * names, or a topographic map, lined up with the blocks through the world's own projection. Shows you (an arrow
 * facing where you look), other players in sight, the world spawn, your marks and a searched place; drag to
 * move, scroll to zoom, right-click to teleport, mark a place or copy its coordinates. Needs the server to run
 * Orbis Terrarum (it sends where the world sits on Earth) and only works in the overworld.
 */
public final class WorldMapScreen extends Screen {
    private static final int TOP_H = 24;
    private static final int BG = 0xFF1E2227, TOP_BG = 0xE8101418;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0, YELLOW = 0xFFFACC15, RED = 0xFFF87171, CYAN = 0xFF67E8F9, GREEN = 0xFF4ADE80;
    private static MapTiles.Layer lastLayer = MapTiles.Layer.STREET;
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
        int boxW = Math.max(80, Math.min(240, width - 250 - 176));
        searchBox = new EditBox(font, 4, 3, boxW, 18, Component.translatable("orbisterrarum.map.search"));
        searchBox.setMaxLength(200);
        searchBox.setValue(query);
        searchBox.setHint(Component.translatable("orbisterrarum.map.search.hint"));
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.search.go"), b -> search())
                .bounds(boxW + 8, 2, 56, 20).build());
        addRenderableWidget(Button.builder(blocksLabel(), b -> {
            blockAlpha = (blockAlpha + 1) % BLOCK_ALPHA.length;
            b.setMessage(blocksLabel());
        }).bounds(width - 326, 2, 96, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.blocks.tip"))).build());
        addRenderableWidget(Button.builder(greyLabel(), b -> {
            greyOut = !greyOut;
            b.setMessage(greyLabel());
        }).bounds(width - 226, 2, 72, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.grey.tip"))).build());
        addRenderableWidget(Button.builder(Component.literal(canvas.layer.label), b -> {
            canvas.layer = canvas.layer.next();
            lastLayer = canvas.layer;
            b.setMessage(Component.literal(canvas.layer.label));
        }).bounds(width - 150, 2, 96, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.layer.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.map.me"), b -> centreOnPlayer())
                .bounds(width - 50, 2, 46, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.map.me.tip"))).build());
        if (!viewSet && mapper != null) {
            centreOnPlayer();
            if (lastScale > 0) canvas.scale = lastScale;
            else {
                double[] ll = playerLatLon();
                canvas.showMetresAcross(ll[0], 1500 * info.metersPerBlock());
            }
            canvas.clamp();
            viewSet = true;
            status = Component.translatable("orbisterrarum.map.help").getString();
            statusUntil = System.currentTimeMillis() + 5000;
        }
    }

    private Component blocksLabel() {
        return blockAlpha == 0 ? Component.translatable("orbisterrarum.map.blocks.off")
                : Component.translatable("orbisterrarum.map.blocks.on", Math.round(BLOCK_ALPHA[blockAlpha] * 100 / 255.0) + "%");
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
        if (e.button() == 1) {
            openMenu((int) e.x(), (int) e.y());
            return true;
        }
        if (e.button() == 0) {
            if (doubleClick) canvas.zoomAt(e.x(), e.y(), 2);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            canvas.pan(dx, dy);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging && e.button() == 0) {
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
        if ((e.key() == 257 || e.key() == 335) && searchBox.isFocused()) {
            search();
            return true;
        }
        if (!searchBox.isFocused() && com.berg.orbis.client.OrbisClient.MAP_KEY != null && com.berg.orbis.client.OrbisClient.MAP_KEY.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    private void openMenu(int x, int y) {
        menuAt = canvas.latLonAt(x, y);
        menuMark = markNear(x, y);
        menuItems.clear();
        // /tpll is for operators; the server only sends a player the commands they may run.
        if (minecraft.player != null && minecraft.player.connection.getCommands().getRoot().getChild("tpll") != null) {
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
                minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tpll %.6f %.6f", lat, lon));
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
            g.enableScissor(canvas.left(), canvas.top(), canvas.right(), canvas.bottom());
            drawSpawn(g);
            for (MapMarks.Mark m : marks) pin(g, canvas.gui(m.lat(), m.lon()), YELLOW, m.name());
            if (found != null) pin(g, canvas.gui(found[0], found[1]), RED, foundName);
            drawPlayers(g);
            g.disableScissor();
            drawScaleBar(g);
            if (canvas.contains(mouseX, mouseY) && menuItems.isEmpty()) drawReadout(g, mouseX, mouseY);
            g.text(font, "Map tiles © Esri", width - font.width("Map tiles © Esri") - 4, TOP_H + 3, 0xC0000000, false);
        }
        g.fill(0, 0, width, TOP_H, TOP_BG);
        drawStatus(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (!menuItems.isEmpty()) drawMenu(g, mouseX, mouseY);
    }

    /**
     * The Minecraft map over the real one (block colours as on a map item, from the server), and a grey veil over
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
        int x = (int) Math.round(s[0]), y = (int) Math.round(s[1]);
        g.fill(x - 4, y - 4, x + 5, y + 5, 0xFF000000);
        g.fill(x - 3, y - 3, x + 4, y + 4, GREEN);
        label(g, x + 7, y - 4, Component.translatable("orbisterrarum.map.spawn").getString(), GREEN);
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
        g.pose().pushMatrix();
        g.pose().translate((float) a[0], (float) a[1]);
        g.pose().rotate(theta);
        triangle(g, 9, 0xFF000000);
        triangle(g, 7, colour);
        g.pose().popMatrix();
        if (name != null) label(g, (int) Math.round(a[0]) + 8, (int) Math.round(a[1]) - 4, name, colour);
    }

    /** A triangle pointing up, {@code size} from tip to base, centred on 0, 0. */
    private void triangle(GuiGraphicsExtractor g, int size, int colour) {
        int top = -size, bottom = size * 2 / 3;
        for (int y = top; y <= bottom; y++) {
            int hw = (int) Math.round((y - top) * 0.55);
            g.fill(-hw, y, hw + 1, y + 1, colour);
        }
    }

    private void pin(GuiGraphicsExtractor g, double[] s, int colour, String name) {
        int x = (int) Math.round(s[0]), y = (int) Math.round(s[1]);
        g.fill(x - 3, y - 9, x + 4, y - 2, 0xFF000000);
        g.fill(x - 2, y - 8, x + 3, y - 3, colour);
        g.fill(x, y - 3, x + 1, y + 1, 0xFF000000);
        if (name != null && !name.isEmpty()) label(g, x + 6, y - 10, name, colour);
    }

    private void label(GuiGraphicsExtractor g, int x, int y, String text, int colour) {
        g.fill(x - 1, y - 1, x + font.width(text) + 1, y + 9, 0xA0000000);
        g.text(font, text, x, y, colour);
    }

    /** A bar of a round number of blocks, with the real distance it stands for. */
    private void drawScaleBar(GuiGraphicsExtractor g) {
        double mpb = info.metersPerBlock();
        double blocksPerPx = canvas.metresPerGuiPixel() / mpb;
        double target = 80 * blocksPerPx;
        double pow = Math.pow(10, Math.floor(Math.log10(target)));
        double nice = target / pow >= 5 ? 5 * pow : target / pow >= 2 ? 2 * pow : pow;
        int px = (int) Math.round(nice / blocksPerPx);
        int x = 8, y = height - 30;
        double metres = nice * mpb;
        String text = String.format(Locale.ROOT, "%,.0f blocks (%s)", nice, metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000) : String.format(Locale.ROOT, "%.0f m", metres));
        g.fill(x - 3, y - 12, x + Math.max(px, font.width(text)) + 4, y + 5, 0xA0000000);
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
        if (BlockMapClient.available()) {
            int cx = Math.floorDiv(b[0], 16), cz = Math.floorDiv(b[1], 16);
            long[] mask = BlockMapClient.mask(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
            if (mask != null) {
                int bit = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
                if ((mask[bit >> 6] & (1L << (bit & 63))) == 0) parts.add(Component.translatable("orbisterrarum.map.notgenerated").getString());
            }
        }
        String text = String.join("   ", parts);
        int w = Math.min(font.width(text), width - 16);
        g.fill(4, height - 15, 4 + w + 8, height - 2, 0xC0000000);
        g.text(font, font.plainSubstrByWidth(text, width - 16), 8, height - 12, WHITE);
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
        int maxW = Math.min(width - 40, 380);
        List<FormattedCharSequence> lines = font.split(Component.literal(s), maxW);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int x = (width - w) / 2, y = TOP_H + 16;
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
