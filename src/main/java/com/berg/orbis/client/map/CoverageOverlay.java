package com.berg.orbis.client.map;

import com.berg.orbis.config.DataSources;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where Orbis has national data, shaded on a zoomed-out map in two levels: stronger where buildings get their real
 * heights (lidar surface models, building registers), lighter where only other national data is read (aerial photos,
 * roads, lakes, rock, snow, forest), each edged with a line; from the same outlines that decide which services are
 * asked. Worked out on a grid of the screen, row by row across each outline (only again when the view moves). Zoomed
 * in, a whole view is usually one country and a shade said nothing, so the world generator map draws it only when the
 * view is wide (see AreaPreviewScreen) and says in its side panel what is better at the cursor.
 */
public final class CoverageOverlay {
    /** Grid cell, in GUI pixels. */
    private static final int CELL = 4;
    private static final int TEAL = 0x14B8A6;
    private static final int[] ALPHA = {0, 0x30, 0x68};
    private static final int EDGE = 0xD02DD4BF;

    /** Converts a place to GUI coordinates on the map. */
    public interface Projection {
        double[] gui(double lat, double lon);
    }

    private final List<List<double[]>> outlines = new ArrayList<>();
    /** Per source: 2 where it gives buildings their real heights, else 1. */
    private final List<Integer> levels = new ArrayList<>();

    private long viewKey = Long.MIN_VALUE;
    private int left, top, cols, rows;
    /** Per cell: 0 (worldwide only), 1 (national data) or 2 (real building heights). */
    private byte[] level = new byte[0];
    /** The edges between cells of different levels, as {x0, y0, x1, y1} on the screen. */
    private final List<int[]> edges = new ArrayList<>();

    public CoverageOverlay() {
        var cfg = com.berg.orbis.OrbisMod.config();
        for (DataSources.Source s : DataSources.list(cfg)) {
            if (s.kind() != DataSources.Kind.NATIONAL) continue;
            outlines.add(DataSources.outline(s));
            levels.add(DataSources.topic(s, cfg) == DataSources.Topic.BUILDINGS ? 2 : 1);
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
            int runStart = 0, runLevel = level[r * cols];
            for (int c = 1; c <= cols; c++) {
                int lv = c < cols ? level[r * cols + c] : -1;
                if (lv == runLevel) continue;
                if (runLevel > 0) g.fill(left + runStart * CELL, y, Math.min(x1, left + c * CELL), Math.min(y1, y + CELL), ALPHA[runLevel] << 24 | TEAL);
                runStart = c;
                runLevel = lv;
            }
        }
        for (int[] e : edges) g.fill(e[0], e[1], Math.min(x1, e[2]), Math.min(y1, e[3]), EDGE);
    }

    /** The colour of a level's shade over a mid-grey map, for the key (1: national data, 2: real building heights). */
    public static int swatch(int lv) {
        double a = ALPHA[lv] / 255.0;
        int base = 0x80;
        int r = (int) Math.round(((TEAL >> 16) & 0xFF) * a + base * (1 - a)), gg = (int) Math.round(((TEAL >> 8) & 0xFF) * a + base * (1 - a));
        int b = (int) Math.round((TEAL & 0xFF) * a + base * (1 - a));
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    private void build(Projection p, int x0, int y0, int x1, int y1, long view) {
        viewKey = view;
        left = x0;
        top = y0;
        cols = Math.max(1, (x1 - x0 + CELL - 1) / CELL);
        rows = Math.max(1, (y1 - y0 + CELL - 1) / CELL);
        level = new byte[cols * rows];
        double[] xs = new double[64];
        for (int si = 0; si < outlines.size(); si++) {
            byte lv = (byte) (int) levels.get(si);
            for (double[] ring : outlines.get(si)) {
                // The ring on the screen (its box first: skip it when it lies off the map).
                int pts = (ring.length - 4) / 2;
                if (pts < 3) continue;
                double[] sx = new double[pts], sy = new double[pts];
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (int i = 0; i < pts; i++) {
                    double[] sc = p.gui(ring[4 + i * 2], ring[5 + i * 2]);
                    sx[i] = sc[0];
                    sy[i] = sc[1];
                    minX = Math.min(minX, sc[0]);
                    maxX = Math.max(maxX, sc[0]);
                    minY = Math.min(minY, sc[1]);
                    maxY = Math.max(maxY, sc[1]);
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
                            if (level[idx] < lv) level[idx] = lv;
                        }
                    }
                }
            }
        }
        // The edges: a line on the higher level's side wherever two neighbouring cells differ.
        edges.clear();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int lv = level[r * cols + c];
                if (lv == 0) continue;
                int x = x0 + c * CELL, y = y0 + r * CELL;
                if (c > 0 && level[r * cols + c - 1] < lv) edges.add(new int[]{x, y, x + 1, y + CELL});
                if (c + 1 < cols && level[r * cols + c + 1] < lv) edges.add(new int[]{x + CELL - 1, y, x + CELL, y + CELL});
                if (r > 0 && level[(r - 1) * cols + c] < lv) edges.add(new int[]{x, y, x + CELL, y + 1});
                if (r + 1 < rows && level[(r + 1) * cols + c] < lv) edges.add(new int[]{x, y + CELL - 1, x + CELL, y + CELL});
            }
        }
    }
}
