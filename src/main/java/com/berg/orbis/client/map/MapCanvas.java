package com.berg.orbis.client.map;

import com.berg.orbis.client.MapTiles;
import com.berg.orbis.client.PreviewPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * A pannable, zoomable web map drawn into a rectangle of a screen: Esri tiles in Web Mercator, drawn in physical
 * pixels so they stay sharp at any GUI scale, with a zoomed-in parent tile standing in until a tile arrives.
 * The view is a centre in Mercator units (0..1) and a scale in physical pixels per Mercator unit. Positions are
 * converted with {@link #gui} and {@link #latLonAt} (GUI coordinates) for markers and clicks.
 */
public final class MapCanvas {
    public final MapTiles tiles = new MapTiles();
    public MapTiles.Layer layer;
    public double cx = 0.5, cy = 0.5, scale = 1024;
    private int left, top, right, bottom, gs = 1;

    public MapCanvas(MapTiles.Layer layer) {
        this.layer = layer;
    }

    /** The map's rectangle in GUI coordinates, and the GUI scale (physical pixels per GUI pixel). */
    public void setBounds(int left, int top, int right, int bottom, int guiScale) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.gs = Math.max(1, guiScale);
    }

    public int left() {
        return left;
    }

    public int top() {
        return top;
    }

    public int right() {
        return right;
    }

    public int bottom() {
        return bottom;
    }

    /** Width and height in physical pixels. */
    public int w() {
        return Math.max(1, (right - left) * gs);
    }

    public int h() {
        return Math.max(1, (bottom - top) * gs);
    }

    public boolean contains(double x, double y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public void clamp() {
        scale = Math.max(256 * 2, Math.min(256.0 * (1 << MapTiles.MAX_ZOOM) * 4, scale));
        cy = Math.max(0, Math.min(1, cy));
    }

    public void centerOn(double lat, double lon) {
        cx = PreviewPlan.mercX(lon);
        cy = PreviewPlan.mercY(lat);
        clamp();
    }

    /** Zoom so that {@code metres} of ground span the map's width at latitude {@code lat}. */
    public void showMetresAcross(double lat, double metres) {
        scale = w() / (Math.max(1, metres) * PreviewPlan.mercPerMeter(lat));
        clamp();
    }

    public void pan(double dxGui, double dyGui) {
        cx -= dxGui * gs / scale;
        cy -= dyGui * gs / scale;
        clamp();
    }

    public void zoomAt(double x, double y, double factor) {
        double px = (x - left) * gs - w() / 2.0, py = (y - top) * gs - h() / 2.0;
        double mx = cx + px / scale, my = cy + py / scale;
        scale *= factor;
        clamp();
        cx = mx - px / scale;
        cy = my - py / scale;
    }

    /** GUI coordinates of a place (may lie outside the map). */
    public double[] gui(double lat, double lon) {
        return new double[]{left + ((PreviewPlan.mercX(lon) - cx) * scale + w() / 2.0) / gs,
                top + ((PreviewPlan.mercY(lat) - cy) * scale + h() / 2.0) / gs};
    }

    /** Latitude and longitude under a GUI position. */
    public double[] latLonAt(double x, double y) {
        double mx = cx + ((x - left) * gs - w() / 2.0) / scale, my = cy + ((y - top) * gs - h() / 2.0) / scale;
        return new double[]{PreviewPlan.latOf(Math.max(0, Math.min(1, my))), PreviewPlan.lonOf(mx)};
    }

    /** Physical-pixel position of a place relative to the map's corner, for drawing between begin/endOverlay. */
    public double[] phys(double lat, double lon) {
        return new double[]{(PreviewPlan.mercX(lon) - cx) * scale + w() / 2.0, (PreviewPlan.mercY(lat) - cy) * scale + h() / 2.0};
    }

    /** Clip to the map and draw in physical pixels from its corner (see {@link #phys}). */
    public void beginOverlay(GuiGraphicsExtractor g) {
        g.enableScissor(left, top, right, bottom);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(1f / gs, 1f / gs);
    }

    public void endOverlay(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
        g.disableScissor();
    }

    public int guiScale() {
        return gs;
    }

    /** Metres of ground per GUI pixel at the centre of the view. */
    public double metresPerGuiPixel() {
        double lat = PreviewPlan.latOf(cy);
        return gs / (scale * PreviewPlan.mercPerMeter(lat));
    }

    /** Draws the tiles (and the layer's place names on top), clipped to the map rectangle. */
    public void draw(GuiGraphicsExtractor g, Minecraft mc) {
        tiles.pump(mc);
        g.enableScissor(left, top, right, bottom);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(1f / gs, 1f / gs);
        drawTiles(g, layer.service, true);
        if (layer.labels != null) drawTiles(g, layer.labels, false);
        g.pose().popMatrix();
        g.disableScissor();
    }

    private void drawTiles(GuiGraphicsExtractor g, String service, boolean base) {
        int mw = w(), mh = h();
        int z = (int) Math.round(Math.log(scale / 256) / Math.log(2));
        z = Math.max(1, Math.min(MapTiles.MAX_ZOOM, z));
        int n = 1 << z;
        double tpx = scale / n;
        double l = cx * scale - mw / 2.0, t = cy * scale - mh / 2.0;
        int tx0 = (int) Math.floor(l / tpx), tx1 = (int) Math.floor((l + mw) / tpx);
        int ty0 = Math.max(0, (int) Math.floor(t / tpx)), ty1 = Math.min(n - 1, (int) Math.floor((t + mh) / tpx));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int sx0 = (int) Math.round(tx * tpx - l), sx1 = (int) Math.round((tx + 1) * tpx - l);
                int sy0 = (int) Math.round(ty * tpx - t), sy1 = (int) Math.round((ty + 1) * tpx - t);
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

    public void close(Minecraft mc) {
        tiles.close(mc);
    }
}
