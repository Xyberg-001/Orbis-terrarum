package com.berg.orbis.client.map;

import com.berg.orbis.config.DataSources;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where Orbis has national data, shaded on a map: the countries and states whose own services it reads (lidar building
 * heights, aerial photos, building and road registers, lake surveys, Norway's rock, snow and forest maps...), more
 * strongly where more of them overlap, from the same outlines that decide which services are asked. Worked out on a grid
 * of the screen, row by row across each outline (only again when the view moves).
 */
public final class CoverageOverlay {
    /** Grid cell, in GUI pixels. */
    private static final int CELL = 4;
    private static final int TEAL = 0x14B8A6;
    private static final int[] ALPHA = {0, 0x30, 0x50, 0x70};

    /** Converts a place to GUI coordinates on the map. */
    public interface Projection {
        double[] gui(double lat, double lon);
    }

    private final List<DataSources.Source> sources = new ArrayList<>();
    private final List<List<double[]>> outlines = new ArrayList<>();

    private long viewKey = Long.MIN_VALUE;
    private int left, top, cols, rows;
    /** Per cell: how many sources cover it, and which (bits, the first 64). */
    private int[] count = new int[0];
    private long[] which = new long[0];

    public CoverageOverlay() {
        for (DataSources.Source s : DataSources.list(com.berg.orbis.OrbisMod.config())) {
            if (s.kind() != DataSources.Kind.NATIONAL) continue;
            sources.add(s);
            outlines.add(DataSources.outline(s));
        }
    }

    /**
     * Shades the map's area (GUI x0..x1, y0..y1). {@code view} identifies the view (centre, zoom and size) so the grid is
     * worked out again only when it changes.
     */
    public void draw(GuiGraphicsExtractor g, Projection p, int x0, int y0, int x1, int y1, long view) {
        if (view != viewKey || x0 != left || y0 != top) build(p, x0, y0, x1, y1, view);
        for (int r = 0; r < rows; r++) {
            int y = top + r * CELL;
            int runStart = 0, runLevel = level(count[r * cols]);
            for (int c = 1; c <= cols; c++) {
                int lv = c < cols ? level(count[r * cols + c]) : -1;
                if (lv == runLevel) continue;
                if (runLevel > 0) g.fill(left + runStart * CELL, y, Math.min(x1, left + c * CELL), Math.min(y1, y + CELL), ALPHA[runLevel] << 24 | TEAL);
                runStart = c;
                runLevel = lv;
            }
        }
    }

    private static int level(int n) {
        return Math.min(3, n);
    }

    /** The national sources at a point of the map (GUI coordinates), by name. */
    public List<String> namesAt(int x, int y) {
        List<String> out = new ArrayList<>();
        int c = (x - left) / CELL, r = (y - top) / CELL;
        if (c < 0 || r < 0 || c >= cols || r >= rows) return out;
        long bits = which[r * cols + c];
        for (int i = 0; i < Math.min(64, sources.size()); i++) {
            if ((bits & (1L << i)) != 0) out.add(sources.get(i).label());
        }
        return out;
    }

    /** The key: shade swatches for one, two and three or more sources, top right at (right, y). Returns its bottom. */
    public int drawLegend(GuiGraphicsExtractor g, Font font, int right, int y, String title) {
        String[] labels = {"1", "2", "3+"};
        int sw = 10, gap = 4;
        int w = font.width(title) + 8;
        int row = 0;
        for (String l : labels) row += sw + 3 + font.width(l) + gap;
        w = Math.max(w, row + 6);
        int x = right - w;
        g.fill(x, y, right, y + 26, 0xC0000000);
        g.text(font, title, x + 4, y + 3, 0xFFFFFFFF);
        int cx = x + 4;
        for (int i = 0; i < labels.length; i++) {
            g.fill(cx, y + 14, cx + sw, y + 22, 0xFF000000 | blend(i + 1));
            g.text(font, labels[i], cx + sw + 3, y + 14, 0xFFA0A8B0);
            cx += sw + 3 + font.width(labels[i]) + gap;
        }
        return y + 26;
    }

    /** The legend's swatch colour: the shade over a mid-grey map. */
    private static int blend(int level) {
        double a = ALPHA[level] / 255.0;
        int base = 0x80;
        int r = (int) Math.round(((TEAL >> 16) & 0xFF) * a + base * (1 - a)), gg = (int) Math.round(((TEAL >> 8) & 0xFF) * a + base * (1 - a));
        int b = (int) Math.round((TEAL & 0xFF) * a + base * (1 - a));
        return r << 16 | gg << 8 | b;
    }

    private void build(Projection p, int x0, int y0, int x1, int y1, long view) {
        viewKey = view;
        left = x0;
        top = y0;
        cols = Math.max(1, (x1 - x0 + CELL - 1) / CELL);
        rows = Math.max(1, (y1 - y0 + CELL - 1) / CELL);
        count = new int[cols * rows];
        which = new long[cols * rows];
        double[] xs = new double[64];
        for (int si = 0; si < sources.size(); si++) {
            long bit = si < 64 ? 1L << si : 0;
            for (double[] ring : outlines.get(si)) {
                // The ring on the screen (its box first: skip it when it lies off the map).
                int pts = (ring.length - 4) / 2;
                if (pts < 3) continue;
                double[] sx = new double[pts], sy = new double[pts];
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (int i = 0; i < pts; i++) {
                    double[] s = p.gui(ring[4 + i * 2], ring[5 + i * 2]);
                    sx[i] = s[0];
                    sy[i] = s[1];
                    minX = Math.min(minX, s[0]);
                    maxX = Math.max(maxX, s[0]);
                    minY = Math.min(minY, s[1]);
                    maxY = Math.max(maxY, s[1]);
                }
                if (maxX < x0 || minX > x1 || maxY < y0 || minY > y1) continue;
                // Row by row: where the row's middle crosses the ring's edges, inside between each pair.
                int r0 = Math.max(0, (int) Math.floor((minY - y0) / CELL)), r1 = Math.min(rows - 1, (int) Math.ceil((maxY - y0) / CELL));
                for (int r = r0; r <= r1; r++) {
                    double yy = y0 + r * CELL + CELL / 2.0;
                    int n = 0;
                    for (int i = 0, j = pts - 1; i < pts; j = i++) {
                        if ((sy[i] > yy) != (sy[j] > yy)) {
                            if (n == xs.length) xs = Arrays.copyOf(xs, n * 2);
                            xs[n++] = sx[i] + (yy - sy[i]) * (sx[j] - sx[i]) / (sy[j] - sy[i]);
                        }
                    }
                    Arrays.sort(xs, 0, n);
                    for (int k = 0; k + 1 < n; k += 2) {
                        int c0 = Math.max(0, (int) Math.ceil((xs[k] - x0) / CELL - 0.5)), c1 = Math.min(cols - 1, (int) Math.floor((xs[k + 1] - x0) / CELL - 0.5));
                        for (int c = c0; c <= c1; c++) {
                            int idx = r * cols + c;
                            // A source with several rings (islands) counts once per cell.
                            if ((which[idx] & bit) != 0 && bit != 0) continue;
                            count[idx]++;
                            which[idx] |= bit;
                        }
                    }
                }
            }
        }
    }
}
