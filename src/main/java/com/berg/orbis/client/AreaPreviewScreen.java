package com.berg.orbis.client;

import com.berg.orbis.client.map.MapSelectTool;
import com.berg.orbis.client.map.MapView;
import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.net.Geocoder;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.worldgen.ChunkSelection;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The world generator's map, opened from the World tab before the world exists: real map tiles at the chosen
 * location, on which you select the area the new world will pre-generate. It starts with nothing selected. Select
 * with the tools on the left (hand, rectangle, ellipse, lasso; Shift adds, Alt cuts out), or type a place or a
 * radius in km at the top to add its outline the same way. The panel beside the map describes the selection: its
 * real area, size in blocks, crossing times, chunks, region files, disk size and generation time, and whether the
 * world generates it by itself when it is created. Right-click sets the spawn point.
 */
public final class AreaPreviewScreen extends Screen {
    private static final int TOP_H = 26;
    /** World scales offered by the + and - buttons (metres per block). */
    private static final double[] SCALES = {0.25, 0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64};
    private static final int BG = 0xFF1E2227, PANEL_BG = 0xF0151A20, TOP_BG = 0xE8101418;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0, GREEN = 0xFF4ADE80, RED = 0xFFF87171, AMBER = 0xFFF59E0B;
    private static MapTiles.Layer layer = MapTiles.Layer.STREET;

    private final Screen parent;
    /** The world's centre (block 0, 0), which is also where players spawn: right-click or a search moves it. */
    private double lat0, lon0;
    private double mpb;
    private final String projection;
    private CoordinateMapper mapper;
    private final Consumer<Double> onScale;
    private final BooleanSupplier generateOn;
    private final Consumer<Boolean> onGenerate;
    private final BooleanSupplier limitOn;
    private final Consumer<Boolean> onLimit;

    private EditBox findBox;
    private Button generateButton, limitButton;
    private final MapTiles tiles = new MapTiles();

    private boolean looking;
    private String status;
    private boolean statusError;
    /** A passing message disappears at this time; 0 = stays. */
    private long statusUntil;

    /** The view: centre in Web Mercator units, and physical pixels per Mercator unit. */
    private double cx, cy, scale;
    private boolean viewSet, dragging;

    private final Consumer<double[]> onCentre;

    /** The selection (the tool strip on the left); its numbers are shown in the panel. */
    private MapSelectTool select;
    private ChunkSelection statsFor;
    private long regionFiles;
    /** A right drag pans (a right click sets the spawn), as do the middle button, Space and the hand tool. */
    private boolean rightDown, rightMoved, spaceDown;
    private double rightX, rightY;

    /** This screen's map for the selection tool. */
    private final MapView view = new MapView() {
        @Override
        public int left() {
            return 0;
        }

        @Override
        public int top() {
            return TOP_H;
        }

        @Override
        public int right() {
            return mapRight();
        }

        @Override
        public int bottom() {
            return height;
        }

        @Override
        public int guiScale() {
            return gs();
        }

        @Override
        public double[] gui(double lat, double lon) {
            double[] s = screenOf(lat, lon);
            return new double[]{s[0] / gs(), s[1] / gs() + TOP_H};
        }

        @Override
        public double[] latLonAt(double x, double y) {
            return AreaPreviewScreen.this.latLonAt(x, y);
        }

        @Override
        public double[] phys(double lat, double lon) {
            return screenOf(lat, lon);
        }

        @Override
        public void beginOverlay(GuiGraphicsExtractor g) {
            g.enableScissor(0, TOP_H, mapRight(), height);
            g.pose().pushMatrix();
            g.pose().translate(0, TOP_H);
            g.pose().scale(1f / gs(), 1f / gs());
        }

        @Override
        public void endOverlay(GuiGraphicsExtractor g) {
            g.pose().popMatrix();
            g.disableScissor();
        }
    };

    public AreaPreviewScreen(Screen parent, double lat0, double lon0, double mpb, String projection, Consumer<Double> onScale,
                             Consumer<double[]> onCentre, BooleanSupplier generateOn, Consumer<Boolean> onGenerate,
                             BooleanSupplier limitOn, Consumer<Boolean> onLimit) {
        super(Component.translatable("orbisterrarum.preview.title"));
        this.parent = parent;
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.mpb = mpb;
        this.projection = projection;
        this.onScale = onScale;
        this.onCentre = onCentre;
        this.generateOn = generateOn;
        this.onGenerate = onGenerate;
        this.limitOn = limitOn;
        this.onLimit = onLimit;
        this.mapper = new CoordinateMapper(lat0, lon0, mpb, CoordinateMapper.Projection.of(projection));
    }

    // ------------------------------------------------------------------ layout

    private int gs() {
        return Math.max(1, minecraft.getWindow().getGuiScale());
    }

    /** The side panel: a third of the screen, 150 to 220 GUI pixels. */
    private int panelW() {
        return Math.max(150, Math.min(220, width / 3));
    }

    private int mapRight() {
        return width - panelW();
    }

    /** Map size in physical pixels. */
    private int mapW() {
        return Math.max(1, mapRight() * gs());
    }

    private int mapH() {
        return Math.max(1, (height - TOP_H) * gs());
    }

    private boolean onMap(double x, double y) {
        return x >= 0 && x < mapRight() && y >= TOP_H && y < height;
    }

    @Override
    protected void init() {
        String query = findBox != null ? findBox.getValue() : "";
        int boxX = 6, buttonW = 56;
        int boxW = Math.max(60, mapRight() - boxX - buttonW - 10);
        findBox = new EditBox(font, boxX, 4, boxW, 18, Component.translatable("orbisterrarum.preview.find"));
        findBox.setMaxLength(200);
        findBox.setValue(query);
        String hint = Component.translatable("orbisterrarum.preview.find.hint").getString();
        int room = boxW - 10;
        if (font.width(hint) > room) hint = font.plainSubstrByWidth(hint, Math.max(0, room - font.width("..."))).stripTrailing() + "...";
        findBox.setHint(Component.literal(hint));
        addRenderableWidget(findBox);
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.preview.findgo"), b -> find())
                .bounds(boxX + boxW + 4, 3, buttonW, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.findgo.tip")))
                .build());

        int px = mapRight() + 6, pw = panelW() - 12;
        // Minus lowers the ratio (fewer metres per block, 1:3 -> 1:2), plus raises it (1:2 -> 1:3).
        addRenderableWidget(Button.builder(Component.literal("<"), b -> stepScale(-1))
                .bounds(px, 4, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.lower")))
                .build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> stepScale(+1))
                .bounds(px + pw - 20, 4, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.higher")))
                .build());
        limitButton = addRenderableWidget(Button.builder(limitLabel(), b -> {
            onLimit.accept(!limitOn.getAsBoolean());
            b.setMessage(limitLabel());
        }).bounds(px, height - 96, pw, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.limit.tip"))).build());
        generateButton = addRenderableWidget(Button.builder(generateLabel(), b -> {
            onGenerate.accept(!generateOn.getAsBoolean());
            b.setMessage(generateLabel());
        }).bounds(px, height - 72, pw, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.generate.tip"))).build());
        addRenderableWidget(Button.builder(Component.literal(layer.label), b -> {
            layer = layer.next();
            b.setMessage(Component.literal(layer.label));
        }).bounds(px, height - 48, pw, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.layer.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(px, height - 24, pw, 20).build());

        if (!viewSet) {
            // The chosen location, about 30 km across; nothing selected until you select it.
            cx = PreviewPlan.mercX(lon0);
            cy = PreviewPlan.mercY(lat0);
            scale = mapW() / (30_000 * PreviewPlan.mercPerMeter(lat0));
            clampScale();
            viewSet = true;
        }
        if (select == null) {
            select = new MapSelectTool(minecraft, view, mapper, mpb, MapSelectTool.PREVIEW, false, s -> {
                status = s;
                statusError = false;
                statusUntil = System.currentTimeMillis() + 8000;
            });
            status = Component.translatable("orbisterrarum.preview.start").getString();
            statusUntil = System.currentTimeMillis() + 10000;
        }
        select.addWidgets(this::addRenderableWidget, 4, TOP_H + 4, mapRight(), height);
    }

    private Component limitLabel() {
        return Component.translatable(limitOn.getAsBoolean() ? "orbisterrarum.preview.limit.on" : "orbisterrarum.preview.limit.off");
    }

    private Component generateLabel() {
        return Component.translatable(generateOn.getAsBoolean() ? "orbisterrarum.preview.generate.on" : "orbisterrarum.preview.generate.off");
    }

    /**
     * One step along SCALES: +1 is more metres per block (a smaller world), -1 fewer (a bigger one). The selection
     * stays the same real place, so only its chunks are worked out again; the new scale is written back to the
     * Scale tab.
     */
    private void stepScale(int dir) {
        double next = mpb;
        if (dir > 0) {
            for (double v : SCALES) {
                if (v > mpb + 1e-9) {
                    next = v;
                    break;
                }
            }
        } else {
            for (int i = SCALES.length - 1; i >= 0; i--) {
                if (SCALES[i] < mpb - 1e-9) {
                    next = SCALES[i];
                    break;
                }
            }
        }
        if (next == mpb) return;
        mpb = next;
        mapper = new CoordinateMapper(lat0, lon0, mpb, CoordinateMapper.Projection.of(projection));
        select.setScale(mapper, mpb);
        onScale.accept(mpb);
    }

    /**
     * The find box: a place's outline, or a radius in km around the location, goes into the selection the way the
     * mode says (New, Add, Subtract), and the map moves to it.
     */
    private void find() {
        String q = findBox.getValue().trim();
        if (q.isEmpty() || looking) return;
        double[] at = Geocoder.parseCoordinates(q);
        if (at != null) {
            moveCentre(at);
            cx = PreviewPlan.mercX(lon0);
            cy = PreviewPlan.mercY(lat0);
            clampScale();
            spawnMessage();
            return;
        }
        Double km = AreaPreview.parseKm(q);
        if (km != null) {
            AreaOutline circle = AreaPreview.circle(lat0, lon0, km);
            select.addOutline(circle.polygons());
            fit(circle);
            return;
        }
        looking = true;
        status = Component.translatable("orbisterrarum.preview.finding", q).getString();
        statusError = false;
        statusUntil = 0;
        Geocoder.lookupArea(q).whenComplete((o, error) -> minecraft.execute(() -> {
            if (error != null || o == null) {
                // No outline (a summit, a building, an address): the place's point, and the spawn goes there.
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                String why = cause != null && cause.getMessage() != null ? cause.getMessage() : "?";
                Geocoder.lookup(q).whenComplete((p, e2) -> minecraft.execute(() -> {
                    looking = false;
                    if (p == null) {
                        status = Component.translatable("orbisterrarum.preview.notfound", q, why).getString();
                        statusError = true;
                        statusUntil = System.currentTimeMillis() + 8000;
                        return;
                    }
                    moveCentre(new double[]{p.lat(), p.lon()});
                    cx = PreviewPlan.mercX(lon0);
                    cy = PreviewPlan.mercY(lat0);
                    scale = mapW() / (10_000 * PreviewPlan.mercPerMeter(lat0));
                    clampScale();
                    status = Component.translatable("orbisterrarum.preview.pointfound", p.name().split(",")[0].trim(),
                            String.format(Locale.ROOT, "%.5f, %.5f", p.lat(), p.lon())).getString();
                    statusError = false;
                    statusUntil = System.currentTimeMillis() + 8000;
                }));
                return;
            }
            looking = false;
            boolean adding = !select.subtracting();
            select.addOutline(o.polygons());
            fit(o);
            String name = o.name().split(",")[0].trim(), km2 = String.format(Locale.ROOT, "%,.0f", o.totalKm2());
            status = Component.translatable("orbisterrarum.preview.found", name, km2).getString();
            statusUntil = System.currentTimeMillis() + 5000;
            // A place found elsewhere takes the spawn (and the world's centre) with it, at the place's own point.
            if (adding && !inside(o.polygons(), lat0, lon0)) {
                Geocoder.lookup(q).whenComplete((p, e2) -> minecraft.execute(() -> {
                    // The place's own point (a summit on a border can lie just outside its area's outline); the middle
                    // of the outline only when the point is not to be had, which can be far from anything named.
                    double[] ll = p != null ? new double[]{p.lat(), p.lon()}
                            : new double[]{(o.south() + o.north()) / 2, (o.west() + o.east()) / 2};
                    moveCentre(ll);
                    status = Component.translatable("orbisterrarum.preview.foundmoved", name, km2).getString();
                    statusUntil = System.currentTimeMillis() + 6000;
                }));
            }
        }));
    }

    /** Whether [lat, lon] lies inside the polygons (an odd number of edges crossed: holes count as outside). */
    private static boolean inside(List<double[][]> polygons, double lat, double lon) {
        boolean in = false;
        for (double[][] p : polygons) {
            for (int i = 0, j = p.length - 1; i < p.length; j = i++) {
                if ((p[i][0] > lat) != (p[j][0] > lat)
                        && lon < (p[j][1] - p[i][1]) * (lat - p[i][0]) / (p[j][0] - p[i][0]) + p[i][1]) in = !in;
            }
        }
        return in;
    }

    private void fit(AreaOutline o) {
        double mx0 = PreviewPlan.mercX(o.west()), mx1 = PreviewPlan.mercX(o.east());
        double my0 = PreviewPlan.mercY(o.north()), my1 = PreviewPlan.mercY(o.south());
        double dx = Math.max(1e-9, mx1 - mx0), dy = Math.max(1e-9, my1 - my0);
        scale = Math.min(mapW() / (dx * 1.12), mapH() / (dy * 1.12));
        cx = (mx0 + mx1) / 2;
        cy = (my0 + my1) / 2;
        clampScale();
    }

    private void clampScale() {
        scale = Math.max(256 * 2, Math.min(256.0 * (1 << MapTiles.MAX_ZOOM) * 4, scale));
        cy = Math.max(0, Math.min(1, cy));
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        tiles.close(minecraft);
        super.removed();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        if (!onMap(e.x(), e.y())) return false;
        setFocused(null);
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            rightDown = true;
            rightMoved = false;
            rightX = e.x();
            rightY = e.y();
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_MIDDLE || (e.button() == InputConstants.MOUSE_BUTTON_LEFT && spaceDown)) {
            dragging = true;
            return true;
        }
        if (select.mousePressed(e, doubleClick)) return true;
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // The hand tool.
            if (doubleClick) zoomAt(e.x(), e.y(), 2);
            dragging = true;
            return true;
        }
        return false;
    }

    /** Right-click: players spawn here, and the world is centred on it (block 0, 0). */
    private void setSpawn(double x, double y) {
        moveCentre(latLonAt(x, y));
        spawnMessage();
    }

    private void spawnMessage() {
        status = Component.translatable("orbisterrarum.preview.spawnset", String.format(Locale.ROOT, "%.5f, %.5f", lat0, lon0)).getString();
        statusError = false;
        statusUntil = System.currentTimeMillis() + 5000;
    }

    /** The spawn and the world's centre go to [lat, lon]: the same places selected, other block numbers. */
    private void moveCentre(double[] ll) {
        lat0 = ll[0];
        lon0 = ll[1];
        mapper = new CoordinateMapper(lat0, lon0, mpb, CoordinateMapper.Projection.of(projection));
        if (select != null) select.setScale(mapper, mpb);
        onCentre.accept(new double[]{lat0, lon0});
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (select.mouseDragged(e)) return true;
        if (rightDown && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (rightMoved || Math.hypot(e.x() - rightX, e.y() - rightY) > 3) {
                rightMoved = true;
                pan(dx, dy);
            }
            return true;
        }
        if (dragging) {
            pan(dx, dy);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    private void pan(double dx, double dy) {
        cx -= dx * gs() / scale;
        cy -= dy * gs() / scale;
        clampScale();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (select.mouseReleased(e)) return true;
        if (rightDown && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            rightDown = false;
            if (!rightMoved) setSpawn(rightX, rightY);
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
        if (sy != 0 && onMap(x, y)) {
            zoomAt(x, y, Math.pow(2, sy * 0.5));
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    private void zoomAt(double x, double y, double factor) {
        double px = x * gs() - mapW() / 2.0, py = (y - TOP_H) * gs() - mapH() / 2.0;
        double mx = cx + px / scale, my = cy + py / scale;
        scale *= factor;
        clampScale();
        cx = mx - px / scale;
        cy = my - py / scale;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if ((e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) && findBox.isFocused()) {
            find();
            return true;
        }
        if (!findBox.isFocused()) {
            if (select.keyPressed(e)) return true;
            if (e.key() == InputConstants.KEY_SPACE) {
                spaceDown = true;
                return true;
            }
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (e.key() == InputConstants.KEY_SPACE) spaceDown = false;
        return super.keyReleased(e);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        tiles.pump(minecraft);
        int gs = gs(), mw = mapW(), mh = mapH();

        g.enableScissor(0, TOP_H, mapRight(), height);
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        g.pose().translate(0, TOP_H * gs);
        drawTiles(g, layer.service, mw, mh, true);
        if (layer.labels != null) drawTiles(g, layer.labels, mw, mh, false);
        g.pose().popMatrix();
        g.disableScissor();
        select.draw(g, font, mouseX, mouseY);
        if (onMap(mouseX, mouseY)) drawHoveredChunk(g, mouseX, mouseY);
        g.enableScissor(0, TOP_H, mapRight(), height);
        drawSpawn(g);
        g.disableScissor();

        g.fill(0, 0, mapRight(), TOP_H, TOP_BG);
        drawPanel(g);
        String credit = layer == MapTiles.Layer.ELEVATION ? "Heights © Mapterhorn, names © Esri" : "Map tiles © Esri";
        g.text(font, credit, mapRight() - font.width(credit) - 4, TOP_H + 3, 0xC0000000, false);
        // Bottom right of the map; the cursor readout keeps clear of it.
        if (layer == MapTiles.Layer.ELEVATION) com.berg.orbis.client.ElevationTiles.drawLegend(g, font, mapRight() - 4, height - 4);
        drawStatus(g);
        if (onMap(mouseX, mouseY)) drawCursorReadout(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        select.drawIcons(g);
    }

    /** One layer of map tiles for the view, falling back to a zoomed-in parent tile until a tile arrives. */
    private void drawTiles(GuiGraphicsExtractor g, String service, int mw, int mh, boolean base) {
        int z = (int) Math.round(Math.log(scale / 256) / Math.log(2));
        z = Math.max(1, Math.min(MapTiles.MAX_ZOOM, z));
        int n = 1 << z;
        double tpx = scale / n;
        double left = cx * scale - mw / 2.0, top = cy * scale - mh / 2.0;
        int tx0 = (int) Math.floor(left / tpx), tx1 = (int) Math.floor((left + mw) / tpx);
        int ty0 = Math.max(0, (int) Math.floor(top / tpx)), ty1 = Math.min(n - 1, (int) Math.floor((top + mh) / tpx));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int sx0 = (int) Math.round(tx * tpx - left), sx1 = (int) Math.round((tx + 1) * tpx - left);
                int sy0 = (int) Math.round(ty * tpx - top), sy1 = (int) Math.round((ty + 1) * tpx - top);
                int x = Math.floorMod(tx, n);
                Identifier id = tiles.get(service, z, x, ty, true);
                if (id != null) {
                    g.blit(id, sx0, sy0, sx1, sy1, 0, 1, 0, 1);
                    continue;
                }
                boolean drawn = false;
                for (int k = 1; k <= 8 && z - k >= 0; k++) {
                    int pz = z - k, pxT = x >> k, pyT = ty >> k;
                    Identifier pid = tiles.get(service, pz, pxT, pyT, k == 1 && tiles.failed(service, z, x, ty));
                    if (pid == null) continue;
                    float sub = 1f / (1 << k);
                    float u0 = (x - (pxT << k)) * sub, v0 = (ty - (pyT << k)) * sub;
                    g.blit(pid, sx0, sy0, sx1, sy1, u0, u0 + sub, v0, v0 + sub);
                    drawn = true;
                    break;
                }
                if (!drawn && base) g.fill(sx0, sy0, sx1, sy1, 0xFF2A3038);
            }
        }
    }

    /** Physical-pixel position of a place relative to the map's corner. */
    private double[] screenOf(double lat, double lon) {
        return new double[]{(PreviewPlan.mercX(lon) - cx) * scale + mapW() / 2.0, (PreviewPlan.mercY(lat) - cy) * scale + mapH() / 2.0};
    }

    private double[] latLonAt(double guiX, double guiY) {
        double mx = cx + (guiX * gs() - mapW() / 2.0) / scale, my = cy + ((guiY - TOP_H) * gs() - mapH() / 2.0) / scale;
        return new double[]{PreviewPlan.latOf(Math.max(0, Math.min(1, my))), PreviewPlan.lonOf(mx)};
    }

    /** Zoomed in far enough to see chunks: the one under the cursor, outlined. */
    private void drawHoveredChunk(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        double[] ll = latLonAt(mouseX, mouseY);
        int[] b = mapper.toBlock(ll[0], ll[1]);
        int ccx = Math.floorDiv(b[0], 16), ccz = Math.floorDiv(b[1], 16);
        double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
        for (int[] c : new int[][]{{0, 0}, {16, 0}, {0, 16}, {16, 16}}) {
            double[] p = mapper.toLatLonExact(ccx * 16 + c[0], ccz * 16 + c[1]);
            double[] s = view.gui(p[0], p[1]);
            minX = Math.min(minX, s[0]);
            maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]);
            maxY = Math.max(maxY, s[1]);
        }
        if (maxX - minX < 6) return;
        int x0 = (int) Math.round(minX), y0 = (int) Math.round(minY), x1 = (int) Math.round(maxX), y1 = (int) Math.round(maxY);
        g.enableScissor(0, TOP_H, mapRight(), height);
        g.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), 0xFFFFFFFF);
        g.disableScissor();
    }

    /** The spawn, which is also the world's centre (block 0, 0), as a yellow cross. */
    private void drawSpawn(GuiGraphicsExtractor g) {
        marker(g, view.gui(lat0, lon0), 0xFFFACC15, Component.translatable("orbisterrarum.preview.spawn").getString());
    }

    private void marker(GuiGraphicsExtractor g, double[] s, int colour, String label) {
        int x = (int) Math.round(s[0]), y = (int) Math.round(s[1]);
        g.fill(x - 5, y - 1, x + 6, y + 2, 0xFF000000);
        g.fill(x - 1, y - 5, x + 2, y + 6, 0xFF000000);
        g.fill(x - 4, y, x + 5, y + 1, colour);
        g.fill(x, y - 4, x + 1, y + 5, colour);
        g.fill(x + 7, y - 5, x + 9 + font.width(label), y + 5, 0xA0000000);
        g.text(font, label, x + 8, y - 4, colour);
    }

    /** What is under the cursor, wrapped onto as many lines as the map's width needs, at the bottom of the map. */
    private void drawCursorReadout(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        double[] ll = latLonAt(mouseX, mouseY);
        int[] b = mapper.toBlock(ll[0], ll[1]);
        int ccx = Math.floorDiv(b[0], 16), ccz = Math.floorDiv(b[1], 16);
        ChunkSelection sel = select.selection();
        boolean in = sel != null && sel.contains(ccx, ccz);
        List<String> parts = new ArrayList<>(List.of(
                String.format(Locale.ROOT, "%.5f, %.5f", ll[0], ll[1]),
                String.format(Locale.ROOT, "block %d, %d", b[0], b[1]),
                String.format(Locale.ROOT, "chunk %d, %d", ccx, ccz),
                String.format(Locale.ROOT, "r.%d.%d.mca", Math.floorDiv(ccx, 32), Math.floorDiv(ccz, 32))));
        if (layer == MapTiles.Layer.ELEVATION) {
            int z = Math.max(1, Math.min(MapTiles.MAX_ZOOM, (int) Math.round(Math.log(scale / 256) / Math.log(2))));
            double m = tiles.elevationAt(ll[0], ll[1], z);
            if (!Double.isNaN(m)) parts.add(String.format(Locale.ROOT, m < 0 ? "%,.0f m below sea level" : "%,.0f m above sea level", Math.abs(m)));
        }
        String state = sel == null || sel.isEmpty() ? null : Component.translatable(in ? "orbisterrarum.preview.in" : "orbisterrarum.preview.out").getString();
        if (state != null) parts.add(state);
        int left = select != null ? select.textLeft(0, height, 4 + MapSelectTool.STRIP_W + 6) : 4 + MapSelectTool.STRIP_W + 6;
        int maxW = mapRight() - left - 12 - (layer == MapTiles.Layer.ELEVATION ? com.berg.orbis.client.ElevationTiles.legendWidth(font) + 6 : 0);
        int gap = font.width("   ");
        List<List<String>> lines = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curW = 0;
        for (String part : parts) {
            int pw = font.width(part);
            if (!cur.isEmpty() && curW + gap + pw > maxW) {
                lines.add(cur);
                cur = new ArrayList<>();
                curW = 0;
            }
            curW += (cur.isEmpty() ? 0 : gap) + pw;
            cur.add(part);
        }
        lines.add(cur);
        int boxW = 0;
        for (List<String> line : lines) {
            int w = 0;
            for (String part : line) w += (w == 0 ? 0 : gap) + font.width(part);
            boxW = Math.max(boxW, w);
        }
        int y = height - 4 - lines.size() * 10;
        g.fill(left, y - 3, Math.min(mapRight() - 4, left + boxW + 8), height - 3, 0xC0000000);
        for (List<String> line : lines) {
            int x = left + 4;
            for (String part : line) {
                g.text(font, part, x, y, part.equals(state) ? (in ? GREEN : GREY) : WHITE);
                x += font.width(part) + gap;
            }
            y += 10;
        }
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        if (statusUntil > 0 && System.currentTimeMillis() > statusUntil) {
            status = null;
            statusUntil = 0;
        }
        String s = status;
        if (s == null && tiles.loading() > 0) s = Component.translatable("orbisterrarum.preview.loadingmap").getString();
        if (s == null) return;
        // Clear of the tool strip on the left.
        int left = select != null ? select.textLeft(0, height, 4 + MapSelectTool.STRIP_W + 8) : 4 + MapSelectTool.STRIP_W + 8;
        int maxW = Math.min(mapRight() - left - 24, 360);
        List<FormattedCharSequence> lines = font.split(Component.literal(s), maxW);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int x = left + (mapRight() - left - w) / 2, y = TOP_H + 16;
        g.fill(x - 6, y - 4, x + w + 6, y + lines.size() * 10 + 2, 0xD0000000);
        for (FormattedCharSequence l : lines) {
            g.text(font, l, x, y, statusError ? RED : WHITE);
            y += 10;
        }
    }

    /**
     * The side panel. The scale control sits at the top and the buttons at the bottom; the text between is drawn
     * at the largest size that fits (in whole physical pixels per font pixel, so it stays sharp).
     */
    private void drawPanel(GuiGraphicsExtractor g) {
        int x0 = mapRight(), pw = panelW();
        g.fill(x0, 0, width, height, PANEL_BG);
        // The world's scale (not the map's zoom, which is the mouse wheel).
        g.centeredText(font, Component.translatable("orbisterrarum.preview.worldscale"), x0 + pw / 2, 4, GREY);
        g.centeredText(font, "1 block = " + PreviewPlan.fmt(mpb) + " m", x0 + pw / 2, 15, WHITE);
        if (generateButton != null) generateButton.setMessage(generateLabel());
        if (limitButton != null) limitButton.setMessage(limitLabel());
        int x = x0 + 8, w = pw - 16, top = 30, bottom = height - 100;
        int gs = gs();
        float ps = 1f;
        for (int k = gs; k >= 1; k--) {
            ps = (float) k / gs;
            if (ps < 0.5f && k < gs) {
                ps = (float) (k + 1) / gs;
                break;
            }
            if (panelText(g, false, 0, (int) (w / ps)) * ps <= bottom - top) break;
        }
        g.enableScissor(x0, top, width, bottom);
        g.pose().pushMatrix();
        g.pose().translate(x, top);
        g.pose().scale(ps, ps);
        panelText(g, true, 0, (int) (w / ps));
        g.pose().popMatrix();
        g.disableScissor();
    }

    /** Draws (or, with draw false, only measures) the panel's text at (0, y) in a column w wide; returns the height. */
    private int panelText(GuiGraphicsExtractor g, boolean draw, int y, int w) {
        int y0 = y;
        ChunkSelection sel = select == null ? null : select.selection();
        if (sel == null || sel.isEmpty()) {
            if (draw) g.text(font, Component.translatable("orbisterrarum.preview.none"), 0, y, WHITE);
            y += 14;
            for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.none.how"), w)) {
                if (draw) g.text(font, l, 0, y, GREY);
                y += 10;
            }
            y += 6;
            return help(g, draw, y, w) - y0;
        }
        if (sel != statsFor) {
            statsFor = sel;
            regionFiles = countRegions(sel);
        }
        long chunks = sel.count();
        int[] xr = sel.xRange();
        long widthBlocks = ((long) xr[1] - xr[0] + 1) * 16, heightBlocks = ((long) sel.lastRow() - sel.firstRow() + 1) * 16;
        double blocks2 = chunks * 256.0;
        double km2 = blocks2 * mpb * mpb / 1e6;
        if (draw) g.text(font, Component.translatable("orbisterrarum.preview.selection"), 0, y, WHITE);
        y += 14;
        y = row(g, draw, w, y, "orbisterrarum.preview.km2", String.format(Locale.ROOT, km2 >= 10 ? "%,.0f km²" : "%,.1f km²", km2));
        y = row(g, draw, w, y, "orbisterrarum.preview.extent", String.format(Locale.ROOT, "%,d × %,d", widthBlocks, heightBlocks));
        y = row(g, draw, w, y, "orbisterrarum.preview.blockarea", (blocks2 >= 1e9 ? String.format(Locale.ROOT, "%.2f B", blocks2 / 1e9)
                : blocks2 >= 1e6 ? String.format(Locale.ROOT, "%.1f M", blocks2 / 1e6) : String.format(Locale.ROOT, "%,.0f", blocks2)) + " blocks²");
        // Straight across the widest side, at vanilla walking (4.317) and sprinting (5.612) speed in blocks a second.
        long widest = Math.max(widthBlocks, heightBlocks);
        y = row(g, draw, w, y, "orbisterrarum.preview.walk", duration(widest / 4.317));
        y = row(g, draw, w, y, "orbisterrarum.preview.sprint", duration(widest / 5.612));
        y = row(g, draw, w, y, "orbisterrarum.preview.chunk", String.format(Locale.ROOT, "16 blocks = %s m", PreviewPlan.fmt(16 * mpb)));
        y = row(g, draw, w, y, "orbisterrarum.preview.chunks", String.format(Locale.ROOT, "%,d", chunks));
        y = row(g, draw, w, y, "orbisterrarum.preview.regions", String.format(Locale.ROOT, "%,d", regionFiles));
        double gb = chunks * PreviewPlan.KB_PER_CHUNK / 1048576.0;
        y = row(g, draw, w, y, "orbisterrarum.preview.size", "about " + (gb >= 1024 ? String.format(Locale.ROOT, "%,.1f TB", gb / 1024)
                : gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb) : String.format(Locale.ROOT, "%.0f MB", gb * 1024)));
        y = row(g, draw, w, y, "orbisterrarum.preview.time", "about " + duration(chunks / PreviewPlan.CHUNKS_PER_SECOND));
        final long edge = 29_999_984L;
        if ((long) xr[0] * 16 < -edge || ((long) xr[1] + 1) * 16 > edge || (long) sel.firstRow() * 16 < -edge || ((long) sel.lastRow() + 1) * 16 > edge) {
            for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.edge"), w)) {
                if (draw) g.text(font, l, 0, y, RED);
                y += 10;
            }
        }
        y += 4;
        boolean on = generateOn.getAsBoolean();
        for (FormattedCharSequence l : font.split(Component.translatable(on ? "orbisterrarum.preview.generate.note.on" : "orbisterrarum.preview.generate.note.off"), w)) {
            if (draw) g.text(font, l, 0, y, on ? GREEN : AMBER);
            y += 10;
        }
        if (limitOn.getAsBoolean()) {
            for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.limit.note"), w)) {
                if (draw) g.text(font, l, 0, y, AMBER);
                y += 10;
            }
        }
        y += 6;
        return help(g, draw, y, w) - y0;
    }

    private int help(GuiGraphicsExtractor g, boolean draw, int y, int w) {
        for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.help"), w)) {
            if (draw) g.text(font, l, 0, y, GREY);
            y += 10;
        }
        return y;
    }

    /** Region files touched: per band of 32 chunk rows, the union of the rows' runs in region columns. */
    private static long countRegions(ChunkSelection sel) {
        long total = 0;
        for (int rz = Math.floorDiv(sel.firstRow(), 32); rz <= Math.floorDiv(sel.lastRow(), 32); rz++) {
            int[] band = new int[0];
            for (int cz = rz * 32; cz < rz * 32 + 32; cz++) {
                int[] runs = sel.rawRuns(cz);
                if (runs == null) continue;
                int[] cols = new int[runs.length];
                for (int i = 0; i < runs.length; i++) cols[i] = Math.floorDiv(runs[i], 32);
                band = ChunkSelection.union(band, cols);
            }
            for (int i = 0; i < band.length; i += 2) total += band[i + 1] - band[i] + 1;
        }
        return total;
    }

    /** "48 min", "1 h 13 min", "3.5 days" or "92 years". */
    private static String duration(double seconds) {
        long min = Math.max(1, Math.round(seconds / 60));
        if (min < 60) return min + " min";
        if (min < 48 * 60) return (min / 60) + " h " + (min % 60) + " min";
        double days = seconds / 86400;
        if (days < 365) return String.format(Locale.ROOT, "%.1f days", days);
        return String.format(Locale.ROOT, "%,.0f years", days / 365.25);
    }

    /** A label and its value on one line, or the value on the next line when both do not fit. */
    private int row(GuiGraphicsExtractor g, boolean draw, int w, int y, String key, String value) {
        Component label = Component.translatable(key);
        boolean wrap = font.width(label) + 8 + font.width(value) > w;
        if (draw) {
            g.text(font, label, 0, y, GREY);
            g.text(font, value, w - font.width(value), wrap ? y + 10 : y, WHITE);
        }
        return y + (wrap ? 21 : 11);
    }
}
