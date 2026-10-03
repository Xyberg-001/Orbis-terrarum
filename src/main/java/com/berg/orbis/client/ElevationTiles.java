package com.berg.orbis.client;

import com.berg.orbis.dem.DemTileProvider;
import com.berg.orbis.net.OrbisHttp;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The Elevation map layer: Mapterhorn's terrain tiles (the heights the world is built from, national lidar where it
 * exists and Copernicus GLO-30 elsewhere) coloured by height, with hillshading so ridges and valleys read at a
 * glance, and under the sea Open Waters' Seascape depths (the sea floor the world is built from: GEBCO with regional
 * surveys), shaded blue by depth. The tiles share the world generator's disk caches ({@code dem-cache/<host>}, the
 * same file names and {@code .missing} markers), so a place already generated or previewed needs no download.
 */
public final class ElevationTiles {

    /** The layer's service name in {@link MapTiles}. */
    public static final String SERVICE = "orbis-elevation";

    /** Heights kept per tile for the cursor readout: this many samples a side. */
    public static final int GRID = 128;

    /**
     * Colour stops: metres, then 0xRRGGBB. Under water from pale shallows to navy deeps; land from green lowlands
     * through tan and brown, then (high mountains, where a grey-to-white ramp told 3500 m from 8500 m apart only by
     * its shading) violet to white.
     */
    private static final double[] STOP_M = {-8000, -4000, -1000, -200, -50, 0, 0.5, 100, 300, 600, 1000, 1500, 2000, 3000, 4000,
            5000, 6000, 7000, 8500};
    private static final int[] STOP_RGB = {0x0B1F45, 0x163A73, 0x24589A, 0x3678B8, 0x5A9FD4, 0x9DD0EE, 0x3F8F4E, 0x6FAE58, 0xB5CB6E,
            0xE5D88A, 0xDDB66E, 0xC48F57, 0xA8714A, 0x8C6E5C, 0x74564F, 0x8A7896, 0xB4AAC8, 0xDCD7E8, 0xFFFFFF};

    /** Open Waters' depth tiles: regional detail to zoom 14, global below; a missing zoom falls back to its parent. */
    private static final int SEA_MAX_ZOOM = 14;

    /** Legend ticks (metres). */
    private static final int[] LEGEND = {-1000, 0, 300, 1000, 2000, 4000, 6000, 8000};

    private ElevationTiles() {
    }

    /** The terrain tile's bytes, or null when Mapterhorn has none at this zoom (the map then draws the parent's). */
    public static byte[] fetch(int z, int x, int y) throws IOException, InterruptedException {
        return fetch(DemTileProvider.MAPTERHORN_URL, "tiles.mapterhorn.com", z, x, y);
    }

    private static byte[] fetch(String template, String host, int z, int x, int y) throws IOException, InterruptedException {
        Path dir = com.berg.orbis.OrbisMod.dataDir().resolve("dem-cache").resolve(host);
        Path tile = dir.resolve(z + "_" + x + "_" + y + ".tile"), missing = dir.resolve(z + "_" + x + "_" + y + ".missing");
        if (Files.isRegularFile(tile)) return Files.readAllBytes(tile);
        if (Files.exists(missing)) return null;
        String url = template.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "OrbisTerrarum/1.0 (Minecraft mod; elevation map)"), 20);
        if (r.status() == 404) {
            Files.createDirectories(dir);
            Files.write(missing, new byte[0]);
            return null;
        }
        if (r.status() != 200) throw new IOException("HTTP " + r.status());
        Files.createDirectories(dir);
        Path tmp = tile.resolveSibling(tile.getFileName() + ".tmp");
        Files.write(tmp, r.body());
        Files.move(tmp, tile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return r.body();
    }

    /** A decoded tile: its colours (ARGB) and a {@link #GRID}-sided grid of its heights in metres. */
    public record Rendered(int[] argb, int w, int h, short[] grid) {
    }

    /**
     * Where tile (z, x, y) is sea (the land tile's 0 m surface), the depths of the finest Seascape tile over it,
     * resampled to the land tile's pixels; the land heights elsewhere. Nothing is fetched for a tile with no sea.
     */
    private static void addSeaFloor(float[][] e, int z, int x, int y) {
        int h = e.length, w = e[0].length;
        boolean sea = false;
        for (float[] row : e) {
            for (float v : row) {
                if (Math.abs(v) <= 0.5f) {
                    sea = true;
                    break;
                }
            }
            if (sea) break;
        }
        if (!sea) return;
        float[][] floor = null;
        int d = 0;
        for (int zz = Math.min(z, SEA_MAX_ZOOM); zz >= 0 && floor == null; zz--) {
            d = z - zz;
            try {
                byte[] b = fetch(DemTileProvider.SEASCAPE_URL, "tiles.openwaters.io", zz, x >> d, y >> d);
                if (b != null) floor = DemTileProvider.decodeTerrarium(b);
            } catch (Exception ex) {
                return; // no sea floor to be had now: the water stays flat
            }
        }
        if (floor == null) return;
        int fh = floor.length, fw = floor[0].length, span = 1 << d;
        double ox = x & (span - 1), oy = y & (span - 1);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (Math.abs(e[j][i]) > 0.5f) continue;
                double u = (ox + (i + 0.5) / w) / span * fw - 0.5, v = (oy + (j + 0.5) / h) / span * fh - 0.5;
                int u0 = (int) Math.floor(u), v0 = (int) Math.floor(v);
                double fu = u - u0, fv = v - v0;
                int ua = Math.max(0, Math.min(fw - 1, u0)), ub = Math.max(0, Math.min(fw - 1, u0 + 1));
                int va = Math.max(0, Math.min(fh - 1, v0)), vb = Math.max(0, Math.min(fh - 1, v0 + 1));
                double depth = (floor[va][ua] * (1 - fu) + floor[va][ub] * fu) * (1 - fv) + (floor[vb][ua] * (1 - fu) + floor[vb][ub] * fu) * fv;
                if (depth < -0.5) e[j][i] = (float) depth;
            }
        }
    }

    /** Colours tile (z, x, y) from its terrain bytes: height tint (depth tint under the sea) times hillshade. */
    public static Rendered render(byte[] bytes, int z, int x, int y) throws IOException {
        float[][] e = DemTileProvider.decodeTerrarium(bytes);
        addSeaFloor(e, z, x, y);
        int h = e.length, w = e[0].length;
        // Metres per pixel at the tile's middle latitude, for the slopes.
        double latMid = (DemTileProvider.tileToLat(y, z) + DemTileProvider.tileToLat(y + 1, z)) / 2;
        double mpp = 40_075_016.686 * Math.cos(Math.toRadians(latMid)) / ((double) (1L << z) * w);
        // Gentle country needs a little exaggeration to show its relief; mountains need none.
        double exaggerate = 1.6;
        double alt = Math.toRadians(45), az = Math.toRadians(315);
        double lx = Math.cos(alt) * Math.sin(az), ly = -Math.cos(alt) * Math.cos(az), lz = Math.sin(alt);
        int[] argb = new int[w * h];
        for (int j = 0; j < h; j++) {
            int ju = Math.max(0, j - 1), jd = Math.min(h - 1, j + 1);
            for (int i = 0; i < w; i++) {
                int il = Math.max(0, i - 1), ir = Math.min(w - 1, i + 1);
                double v = e[j][i];
                double dzdx = (e[j][ir] - e[j][il]) / ((ir - il) * mpp) * exaggerate;
                double dzdy = (e[jd][i] - e[ju][i]) / ((jd - ju) * mpp) * exaggerate;
                double nx = -dzdx, ny = -dzdy, nz = 1, len = Math.sqrt(nx * nx + ny * ny + 1);
                double shade = Math.max(0, (nx * lx + ny * ly + nz * lz) / len);
                // Flat ground (shade 0.71) comes out near 1; shadows stay readable; the sea floor is shaded more gently.
                double f = v <= 0 ? 0.78 + 0.31 * shade : 0.6 + 0.56 * shade;
                int c = colour(v);
                int r = clamp((int) Math.round(((c >> 16) & 0xFF) * f));
                int g = clamp((int) Math.round(((c >> 8) & 0xFF) * f));
                int b = clamp((int) Math.round((c & 0xFF) * f));
                argb[j * w + i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        short[] grid = new short[GRID * GRID];
        for (int gy = 0; gy < GRID; gy++) {
            int sy = Math.min(h - 1, (int) ((gy + 0.5) * h / GRID));
            for (int gx = 0; gx < GRID; gx++) {
                int sx = Math.min(w - 1, (int) ((gx + 0.5) * w / GRID));
                grid[gy * GRID + gx] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(e[sy][sx])));
            }
        }
        return new Rendered(argb, w, h, grid);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** The tint for a height in metres (0xRRGGBB). */
    public static int colour(double m) {
        if (m <= STOP_M[0]) return STOP_RGB[0];
        for (int k = 1; k < STOP_M.length; k++) {
            if (m <= STOP_M[k]) {
                double t = (m - STOP_M[k - 1]) / (STOP_M[k] - STOP_M[k - 1]);
                int a = STOP_RGB[k - 1], b = STOP_RGB[k];
                int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
                int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
                int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
                return r << 16 | g << 8 | bl;
            }
        }
        return STOP_RGB[STOP_RGB.length - 1];
    }

    /** Rows of the colour key, one per tick, this far apart. */
    private static final int ROW = 11;

    /** How wide the colour key is (the readouts keep clear of it). */
    public static int legendWidth(Font font) {
        int widest = font.width("m");
        for (int k = 0; k < LEGEND.length; k++) widest = Math.max(widest, font.width(label(k)));
        return widest + 18;
    }

    private static String label(int k) {
        return k == LEGEND.length - 1 ? LEGEND[k] + "+" : String.valueOf(LEGEND[k]);
    }

    /**
     * The colour key, upright, its bottom right corner at (right, bottom): a bar from the deep sea at the bottom to
     * the highest peaks at the top with the heights beside it (not to scale: each tick gets the same room). Upright
     * and in a corner it stays narrow at any GUI scale; across the top it outgrew the map and ran under its tools.
     */
    public static void drawLegend(GuiGraphicsExtractor g, Font font, int right, int bottom) {
        int w = legendWidth(font), seg = LEGEND.length - 1;
        int barH = seg * ROW, x0 = right - w, top = bottom - barH - 18;
        g.fill(x0, top, right, bottom, 0xA0000000);
        g.text(font, "m", right - 4 - font.width("m"), top + 3, 0xFFFFFFFF);
        int bx = right - 10, by0 = top + 13; // the bar: from by0 (the highest) down barH
        for (int py = 0; py <= barH; py++) {
            double t = (barH - py) / (double) barH * seg;
            int k = Math.min(seg - 1, (int) t);
            double m = LEGEND[k] + (LEGEND[k + 1] - LEGEND[k]) * (t - k);
            g.fill(bx, by0 + py, bx + 6, by0 + py + 1, 0xFF000000 | colour(m));
        }
        for (int k = 0; k <= seg; k++) {
            int py = by0 + (seg - k) * ROW;
            String s = label(k);
            g.fill(bx - 2, py, bx, py + 1, 0xFFFFFFFF);
            g.text(font, s, bx - 4 - font.width(s), py - 4, 0xFFFFFFFF);
        }
    }
}
