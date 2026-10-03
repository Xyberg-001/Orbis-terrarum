package com.berg.orbis.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/**
 * A notification that stays up while a long job runs and shows how far it has got: a title, a line of text and a
 * bar. Any thread may update it; it hides itself a few seconds after {@link #finish}.
 */
public final class ProgressToast implements Toast {

    private static final int WIDTH = 220, HEIGHT = 40;
    private static final long LINGER_MS = 6_000;

    private volatile String title;
    private volatile String line = "";
    private volatile float progress = -1; // 0..1, or below 0 while the amount is not known yet
    private volatile boolean finished;
    private volatile long finishedAt;
    private long now;

    public ProgressToast(String title) {
        this.title = title;
    }

    /** New text and progress (0..1; negative for "working, amount unknown"). */
    public void set(String line, float progress) {
        this.line = line;
        this.progress = progress;
    }

    /** The job is over: show the outcome for a few seconds, then go. */
    public void finish(String title, String line) {
        this.title = title;
        this.line = line;
        this.progress = 1;
        this.finishedAt = System.currentTimeMillis();
        this.finished = true;
    }

    @Override
    public Visibility getWantedVisibility() {
        return finished && System.currentTimeMillis() - finishedAt > LINGER_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long time) {
        now = time;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, Font font, long time) {
        g.fill(0, 0, WIDTH, HEIGHT, 0xFF101418);
        g.fill(1, 1, WIDTH - 1, HEIGHT - 1, 0xFF262C33);
        g.text(font, font.plainSubstrByWidth(title, WIDTH - 14), 7, 6, 0xFFFFE070);
        g.text(font, font.plainSubstrByWidth(line, WIDTH - 14), 7, 17, 0xFFE8E8E8);
        int x0 = 7, x1 = WIDTH - 7, y0 = 30, y1 = 34;
        g.fill(x0, y0, x1, y1, 0xFF0A0C0E);
        float p = progress;
        if (p >= 0) {
            g.fill(x0, y0, x0 + Math.round((x1 - x0) * Math.min(1, p)), y1, finished ? 0xFF5FD068 : 0xFF4FA3E0);
        } else {
            // Not counted yet: a block sliding back and forth.
            int w = 40, span = x1 - x0 - w;
            double t = (System.currentTimeMillis() % 1600) / 800.0;
            int off = (int) Math.round(span * (t <= 1 ? t : 2 - t));
            g.fill(x0 + off, y0, x0 + off + w, y1, 0xFF4FA3E0);
        }
    }
}
