package com.berg.orbis.client;

import com.berg.orbis.osm.CoordinateMapper;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The area preview inside the game: a map you can drag and zoom, with real map tiles underneath, the chunks a
 * pre-generation would make drawn over them (with the chunk and region-file grid when zoomed in), the spawn
 * point, what is under the cursor, and the estimates (chunks, disk, time) beside it. Opened from the Location tab
 * with the location and scale being set up, before the world exists.
 */
public final class AreaPreviewScreen extends Screen {
    private static final int TOP_H = 26;
    /** World scales offered by the + and - buttons (metres per block). */
    private static final double[] SCALES = {0.25, 0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64};
    private static final int BG = 0xFF1E2227, PANEL_BG = 0xF0151A20, TOP_BG = 0xE8101418;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A8B0, GREEN = 0xFF4ADE80, RED = 0xFFF87171, AMBER = 0xFFF59E0B;
    private static final Identifier OVERLAY = Identifier.fromNamespaceAndPath("orbisterrarum", "preview/overlay");
    private static MapTiles.Layer layer = MapTiles.Layer.STREET;

    private final Screen parent;
    private final double lat0, lon0;
    private double mpb;
    private final String projection;
    private final Consumer<String> onArea;
    private CoordinateMapper mapper;
    private final Consumer<Double> onScale;
    private String area;

    private EditBox areaBox;
    private Button layerButton;
    private final MapTiles tiles = new MapTiles();

    private PreviewPlan plan;
    private int planToken;
    private boolean looking;
    private String status;
    private boolean statusError;
    /** A passing message (spawn set) disappears at this time; 0 = stays. */
    private long statusUntil;

    /** The view: centre in Web Mercator units, and physical pixels per Mercator unit. */
    private double cx, cy, scale;
    private boolean viewSet, dragging;
    private long viewChangedAt;

    private final ExecutorService overlayWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis area preview");
        t.setDaemon(true);
        return t;
    });
    private boolean overlayBusy, overlayShown;
    private volatile Object[] overlayDone;
    private double ocx, ocy, oscale, chunkPx;
    private int ow, oh;
    private PreviewPlan overlayPlan, overlayFailed;

    /** The custom spawn point (lat, lon), or null while players spawn at the location. */
    private double[] spawn;
    private final Consumer<double[]> onSpawn;

    public AreaPreviewScreen(Screen parent, String area, double lat0, double lon0, double mpb, String projection, Consumer<String> onArea,
                             Consumer<Double> onScale, double[] spawn, Consumer<double[]> onSpawn) {
        super(Component.translatable("orbisterrarum.preview.title"));
        this.parent = parent;
        this.area = area == null ? "" : area;
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.mpb = mpb;
        this.projection = projection;
        this.onArea = onArea;
        this.onScale = onScale;
        this.spawn = spawn;
        this.onSpawn = onSpawn;
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
        if (areaBox != null) area = areaBox.getValue();
        int boxX = 48, buttonW = 56;
        int boxW = Math.max(60, mapRight() - boxX - buttonW - 12);
        areaBox = new EditBox(font, boxX, 4, boxW, 18, Component.translatable("orbisterrarum.preview.area"));
        areaBox.setMaxLength(200);
        areaBox.setValue(area);
        // The box does not clip its hint: cut it to the box's width so it never runs under the Preview button.
        String hint = Component.translatable("orbisterrarum.preview.area.hint").getString();
        int room = boxW - 10;
        if (font.width(hint) > room) hint = font.plainSubstrByWidth(hint, Math.max(0, room - font.width("..."))).stripTrailing() + "...";
        areaBox.setHint(Component.literal(hint));
        addRenderableWidget(areaBox);
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.preview.go"), b -> lookUp())
                .bounds(boxX + boxW + 4, 3, buttonW, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.go.tip")))
                .build());

        int px = mapRight() + 6, pw = panelW() - 12, half = (pw - 4) / 2;
        // Minus lowers the ratio (fewer metres per block, 1:3 -> 1:2), plus raises it (1:2 -> 1:3).
        addRenderableWidget(Button.builder(Component.literal("-"), b -> stepScale(-1))
                .bounds(px, 4, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.lower")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> stepScale(+1))
                .bounds(px + pw - 20, 4, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.higher")))
                .build());
        layerButton = addRenderableWidget(Button.builder(layerLabel(), b -> {
            layer = layer.next();
            b.setMessage(layerLabel());
        }).bounds(px, height - 48, half, 20).tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.layer.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.preview.browser"), b -> openBrowser())
                .bounds(px + pw - half, height - 48, half, 20)
                .tooltip(Tooltip.create(Component.translatable("orbisterrarum.preview.browser.tip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(px, height - 24, pw, 20).build());

        if (!viewSet) {
            // Before the area is known: the chosen location, about 30 km across.
            cx = PreviewPlan.mercX(lon0);
            cy = PreviewPlan.mercY(lat0);
            scale = mapW() / (30_000 * PreviewPlan.mercPerMeter(lat0));
            viewSet = true;
        }
        if (plan == null && !looking) lookUp();
    }

    private Component layerLabel() {
        return Component.literal(layer.label);
    }

    /**
     * One step along SCALES: +1 is more metres per block (a smaller world), -1 fewer (a bigger one). The area
     * stays the same real place, so only its chunks are worked out again; the new scale is written back to the
     * Scale tab like the area is to the Location tab.
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
        onScale.accept(mpb);
        if (plan != null) replan(plan);
        else if (!looking) lookUp();
    }

    /** The same area at the current scale (no new lookup). */
    private void replan(PreviewPlan old) {
        int token = ++planToken;
        looking = true;
        statusError = false;
        status = counting(old.outline);
        CoordinateMapper m = mapper;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> PreviewPlan.of(old.outline, old.radiusKm, m, projection))
                .whenComplete((p, error) -> minecraft.execute(() -> {
                    if (token != planToken) return;
                    looking = false;
                    if (error != null || p == null) {
                        status = String.valueOf(error);
                        statusError = true;
                        return;
                    }
                    status = null;
                    plan = p;
                    viewChangedAt = 0;
                }));
    }

    private void lookUp() {
        area = areaBox.getValue().trim();
        onArea.accept(area);
        int token = ++planToken;
        looking = true;
        statusError = false;
        status = area.isEmpty() ? Component.translatable("orbisterrarum.preview.finding.here").getString()
                : Component.translatable("orbisterrarum.preview.finding", area).getString();
        AreaPreview.plan(area, lat0, lon0, mpb, projection, o -> minecraft.execute(() -> {
            if (token == planToken) status = counting(o);
        })).whenComplete((p, error) -> minecraft.execute(() -> {
            if (token != planToken) return;
            looking = false;
            if (error != null || p == null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                status = Component.translatable("orbisterrarum.preview.notfound", area,
                        cause != null && cause.getMessage() != null ? cause.getMessage() : "?").getString();
                statusError = true;
                return;
            }
            status = null;
            plan = p;
            overlayShown = false;
            fitView();
            if (Math.abs(p.mapper.metersPerBlock() - mpb) > 1e-9) replan(p);
        }));
    }

    private String counting(com.berg.orbis.net.AreaOutline o) {
        return Component.translatable("orbisterrarum.preview.counting", o.name().split(",")[0].trim(),
                String.format(Locale.ROOT, "%,.0f", o.totalKm2())).getString();
    }

    private void fitView() {
        double dx = Math.max(1e-9, plan.mx1 - plan.mx0), dy = Math.max(1e-9, plan.my1 - plan.my0);
        scale = Math.min(mapW() / (dx * 1.12), mapH() / (dy * 1.12));
        clampScale();
        cx = (plan.mx0 + plan.mx1) / 2;
        cy = (plan.my0 + plan.my1) / 2;
        viewChangedAt = System.currentTimeMillis();
    }

    private void clampScale() {
        scale = Math.max(256 * 2, Math.min(256.0 * (1 << MapTiles.MAX_ZOOM) * 4, scale));
        cy = Math.max(0, Math.min(1, cy));
    }

    private void openBrowser() {
        if (plan == null) return;
        try {
            AreaPreview.openInBrowser(plan);
        } catch (Exception e) {
            status = e.getMessage();
            statusError = true;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        tiles.close(minecraft);
        overlayWorker.shutdownNow();
        minecraft.getTextureManager().release(OVERLAY);
        super.removed();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        if (e.button() == 1 && onMap(e.x(), e.y())) {
            // Right-click: the spawn point goes here (written back to the Location tab).
            spawn = latLonAt(e.x(), e.y());
            onSpawn.accept(spawn);
            int[] b = mapper.toBlock(spawn[0], spawn[1]);
            status = Component.translatable("orbisterrarum.preview.spawnset", String.format(Locale.ROOT, "%.5f, %.5f", spawn[0], spawn[1]),
                    b[0] + ", " + b[1]).getString();
            statusError = false;
            statusUntil = System.currentTimeMillis() + 4000;
            return true;
        }
        if (e.button() == 0 && onMap(e.x(), e.y())) {
            setFocused(null);
            if (doubleClick) zoomAt(e.x(), e.y(), 2);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            cx -= dx * gs() / scale;
            cy -= dy * gs() / scale;
            clampScale();
            viewChangedAt = System.currentTimeMillis();
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
        viewChangedAt = System.currentTimeMillis();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if ((e.key() == 257 || e.key() == 335) && areaBox.isFocused()) {
            lookUp();
            return true;
        }
        return super.keyPressed(e);
    }

    // ------------------------------------------------------------------ overlay (computed off the render thread)

    private void updateOverlay() {
        Object[] done = overlayDone;
        if (done != null) {
            overlayDone = null;
            overlayBusy = false;
            PreviewRaster.Result r = (PreviewRaster.Result) done[0];
            if (r == null) {
                overlayFailed = (PreviewPlan) done[4];
                status = "Could not draw the chunks: " + done[5];
                statusError = true;
            } else if (done[4] == plan) {
                NativeImage img = MapTiles.image(r.width(), r.height(), r.argb());
                minecraft.getTextureManager().register(OVERLAY, new DynamicTexture(() -> "orbis area preview overlay", img));
                ocx = (double) done[1];
                ocy = (double) done[2];
                oscale = (double) done[3];
                ow = r.width();
                oh = r.height();
                chunkPx = r.chunkPx();
                overlayPlan = plan;
                overlayShown = true;
            }
        }
        if (plan == null || overlayBusy || overlayFailed == plan) return;
        boolean same = overlayShown && overlayPlan == plan && ocx == cx && ocy == cy && oscale == scale && ow == mapW() && oh == mapH();
        if (same || System.currentTimeMillis() - viewChangedAt < 60 && overlayShown) return;
        overlayBusy = true;
        PreviewPlan p = plan;
        double vcx = cx, vcy = cy, vs = scale;
        int w = mapW(), h = mapH();
        overlayWorker.execute(() -> {
            try {
                PreviewRaster.Result r = PreviewRaster.render(p, vcx, vcy, vs, w, h);
                MapTiles.toAbgr(r.argb());
                overlayDone = new Object[]{r, vcx, vcy, vs, p};
            } catch (Throwable t) {
                System.err.println("[orbis] Area preview overlay failed: " + t);
                overlayDone = new Object[]{null, vcx, vcy, vs, p, String.valueOf(t)};
            }
        });
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        tiles.pump(minecraft);
        updateOverlay();
        int gs = gs(), mw = mapW(), mh = mapH();

        g.enableScissor(0, TOP_H, mapRight(), height);
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        g.pose().translate(0, TOP_H * gs);
        drawTiles(g, layer.service, mw, mh, true);
        if (layer.labels != null) drawTiles(g, layer.labels, mw, mh, false);
        if (overlayShown && plan != null) {
            double x0 = (ocx - ow / 2.0 / oscale - cx) * scale + mw / 2.0, y0 = (ocy - oh / 2.0 / oscale - cy) * scale + mh / 2.0;
            double x1 = (ocx + ow / 2.0 / oscale - cx) * scale + mw / 2.0, y1 = (ocy + oh / 2.0 / oscale - cy) * scale + mh / 2.0;
            g.blit(OVERLAY, (int) Math.round(x0), (int) Math.round(y0), (int) Math.round(x1), (int) Math.round(y1), 0, 1, 0, 1);
        }
        if (plan != null && onMap(mouseX, mouseY) && overlayShown && chunkPx * scale / oscale >= 10) drawHoveredChunk(g, mouseX, mouseY, mw, mh);
        g.pose().popMatrix();
        drawSpawn(g, mw, mh, gs);
        g.disableScissor();

        g.fill(0, 0, mapRight(), TOP_H, TOP_BG);
        g.text(font, Component.translatable("orbisterrarum.preview.area"), 8, 9, WHITE);
        drawPanel(g);
        drawStatus(g);
        if (onMap(mouseX, mouseY)) drawCursorReadout(g, mouseX, mouseY);
        g.text(font, "Map tiles © Esri", mapRight() - font.width("Map tiles © Esri") - 4, TOP_H + 3, 0xC0000000, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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

    private double[] screenOf(double lat, double lon, int mw, int mh) {
        return new double[]{(PreviewPlan.mercX(lon) - cx) * scale + mw / 2.0, (PreviewPlan.mercY(lat) - cy) * scale + mh / 2.0};
    }

    private double[] latLonAt(double guiX, double guiY) {
        double mx = cx + (guiX * gs() - mapW() / 2.0) / scale, my = cy + ((guiY - TOP_H) * gs() - mapH() / 2.0) / scale;
        return new double[]{PreviewPlan.latOf(Math.max(0, Math.min(1, my))), PreviewPlan.lonOf(mx)};
    }

    private void drawHoveredChunk(GuiGraphicsExtractor g, int mouseX, int mouseY, int mw, int mh) {
        double[] ll = latLonAt(mouseX, mouseY);
        int[] b = mapper.toBlock(ll[0], ll[1]);
        int ccx = Math.floorDiv(b[0], 16), ccz = Math.floorDiv(b[1], 16);
        double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
        for (int[] c : new int[][]{{0, 0}, {16, 0}, {0, 16}, {16, 16}}) {
            double[] p = mapper.toLatLonExact(ccx * 16 + c[0], ccz * 16 + c[1]);
            double[] s = screenOf(p[0], p[1], mw, mh);
            minX = Math.min(minX, s[0]);
            maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]);
            maxY = Math.max(maxY, s[1]);
        }
        int x0 = (int) Math.round(minX), y0 = (int) Math.round(minY), x1 = (int) Math.round(maxX), y1 = (int) Math.round(maxY);
        g.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), 0xFFFFFFFF);
    }

    /** The location (block 0, 0) as a white cross, and a custom spawn point as a yellow one. */
    private void drawSpawn(GuiGraphicsExtractor g, int mw, int mh, int gs) {
        marker(g, screenOf(lat0, lon0, mw, mh), gs, WHITE,
                Component.translatable(spawn == null ? "orbisterrarum.preview.spawn" : "orbisterrarum.preview.origin").getString());
        if (spawn != null) {
            marker(g, screenOf(spawn[0], spawn[1], mw, mh), gs, 0xFFFACC15, Component.translatable("orbisterrarum.preview.spawnpoint").getString());
        }
    }

    private void marker(GuiGraphicsExtractor g, double[] s, int gs, int colour, String label) {
        int x = (int) Math.round(s[0] / gs), y = (int) Math.round(s[1] / gs) + TOP_H;
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
        boolean in = plan != null && plan.contains(ccx, ccz);
        List<String> parts = new java.util.ArrayList<>(List.of(
                String.format(Locale.ROOT, "%.5f, %.5f", ll[0], ll[1]),
                String.format(Locale.ROOT, "block %d, %d", b[0], b[1]),
                String.format(Locale.ROOT, "chunk %d, %d", ccx, ccz),
                String.format(Locale.ROOT, "r.%d.%d.mca", Math.floorDiv(ccx, 32), Math.floorDiv(ccz, 32))));
        String state = plan == null ? null : Component.translatable(in ? "orbisterrarum.preview.in" : "orbisterrarum.preview.out").getString();
        if (state != null) parts.add(state);
        int maxW = mapRight() - 16, gap = font.width("   ");
        List<List<String>> lines = new java.util.ArrayList<>();
        List<String> cur = new java.util.ArrayList<>();
        int curW = 0;
        for (String part : parts) {
            int pw = font.width(part);
            if (!cur.isEmpty() && curW + gap + pw > maxW) {
                lines.add(cur);
                cur = new java.util.ArrayList<>();
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
        g.fill(4, y - 3, Math.min(mapRight() - 4, 4 + boxW + 8), height - 3, 0xC0000000);
        for (List<String> line : lines) {
            int x = 8;
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
        int maxW = Math.min(mapRight() - 40, 360);
        List<FormattedCharSequence> lines = font.split(Component.literal(s), maxW);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int x = (mapRight() - w) / 2, y = TOP_H + 16;
        g.fill(x - 6, y - 4, x + w + 6, y + lines.size() * 10 + 2, 0xD0000000);
        for (FormattedCharSequence l : lines) {
            g.text(font, l, x, y, statusError ? RED : WHITE);
            y += 10;
        }
    }

    /**
     * The side panel. The scale control sits at the top and the buttons at the bottom; the text between is drawn
     * at the largest size that fits (in whole physical pixels per font pixel, so it stays sharp), so every number
     * shows at any GUI scale and window size.
     */
    private void drawPanel(GuiGraphicsExtractor g) {
        int x0 = mapRight(), pw = panelW();
        g.fill(x0, 0, width, height, PANEL_BG);
        String scaleText = "1 block = " + PreviewPlan.fmt(mpb) + " m";
        g.centeredText(font, scaleText, x0 + pw / 2, 10, WHITE);
        int x = x0 + 8, w = pw - 16, top = 30, bottom = height - 52;
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
        if (plan == null) {
            if (draw) g.text(font, Component.translatable(looking ? "orbisterrarum.preview.working" : "orbisterrarum.preview.none"), 0, y, GREY);
            return 10;
        }
        for (FormattedCharSequence l : font.split(Component.literal(plan.shortName()), w)) {
            if (draw) g.text(font, l, 0, y, WHITE);
            y += 10;
        }
        if (looking) {
            if (draw) g.text(font, Component.translatable("orbisterrarum.preview.working"), 0, y, AMBER);
            y += 10;
        }
        y += 4;
        double m = plan.mapper.metersPerBlock();
        double km2 = plan.outline.totalKm2();
        double blocks2 = km2 * 1e6 / (m * m);
        y = row(g, draw, w, y, "orbisterrarum.preview.km2", String.format(Locale.ROOT, "%,.0f km\u00b2", km2));
        y = row(g, draw, w, y, "orbisterrarum.preview.extent", String.format(Locale.ROOT, "%,d \u00d7 %,d", plan.widthBlocks(), plan.heightBlocks()));
        y = row(g, draw, w, y, "orbisterrarum.preview.blockarea", (blocks2 >= 1e9 ? String.format(Locale.ROOT, "%.2f B", blocks2 / 1e9)
                : blocks2 >= 1e6 ? String.format(Locale.ROOT, "%.1f M", blocks2 / 1e6) : String.format(Locale.ROOT, "%,.0f", blocks2)) + " blocks²");
        // Straight across the widest side, at vanilla walking (4.317) and sprinting (5.612) speed in blocks a second.
        long widest = Math.max(plan.widthBlocks(), plan.heightBlocks());
        y = row(g, draw, w, y, "orbisterrarum.preview.walk", duration(widest / 4.317));
        y = row(g, draw, w, y, "orbisterrarum.preview.sprint", duration(widest / 5.612));
        y = row(g, draw, w, y, "orbisterrarum.preview.chunk", String.format(Locale.ROOT, "16 blocks = %s m", PreviewPlan.fmt(16 * m)));
        y = row(g, draw, w, y, "orbisterrarum.preview.chunks", String.format(Locale.ROOT, "%,d", plan.chunks));
        y = row(g, draw, w, y, "orbisterrarum.preview.regions", String.format(Locale.ROOT, "%,d", plan.regionFiles));
        double gb = plan.gigabytes();
        y = row(g, draw, w, y, "orbisterrarum.preview.size", "about " + (gb >= 1024 * 1024 ? String.format(Locale.ROOT, "%,.1f PB", gb / 1048576)
                : gb >= 1024 ? String.format(Locale.ROOT, "%,.1f TB", gb / 1024) : gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb)
                : String.format(Locale.ROOT, "%.0f MB", gb * 1024)));
        y = row(g, draw, w, y, "orbisterrarum.preview.time", "about " + duration(plan.hours() * 3600));
        if (plan.beyondWorldEdge()) {
            for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.edge"), w)) {
                if (draw) g.text(font, l, 0, y, RED);
                y += 10;
            }
        }
        y += 4;
        if (draw) g.text(font, Component.translatable("orbisterrarum.preview.command"), 0, y, GREY);
        y += 10;
        for (FormattedCharSequence l : font.split(Component.literal(plan.command), w)) {
            if (draw) g.text(font, l, 0, y, AMBER);
            y += 10;
        }
        y += 6;
        y = legend(g, draw, y, 0xFF22C55E, "orbisterrarum.preview.legend.chunks");
        y = legend(g, draw, y, 0xFFEF4444, "orbisterrarum.preview.legend.outline");
        y = legend(g, draw, y, 0xFFF59E0B, "orbisterrarum.preview.legend.regions");
        y += 4;
        for (FormattedCharSequence l : font.split(Component.translatable("orbisterrarum.preview.help"), w)) {
            if (draw) g.text(font, l, 0, y, GREY);
            y += 10;
        }
        return y - y0;
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

    private int legend(GuiGraphicsExtractor g, boolean draw, int y, int color, String key) {
        if (draw) {
            g.fill(0, y + 1, 7, y + 8, color);
            g.text(font, Component.translatable(key), 11, y, GREY);
        }
        return y + 11;
    }
}
