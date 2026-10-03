package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.mixin.client.CreateWorldScreenInvoker;
import com.berg.orbis.osm.extract.MapDataJob;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * At Create: when no map data on disk covers the new world's area, offers Geofabrik's file for it (the smallest
 * region that holds the area, with its size), so generation reads the map from disk instead of the public Overpass
 * servers. Yes starts {@link MapDataJob} in the background and creation goes on at once; the world generates from
 * the online servers until the area's data is imported. Asked once per Create screen, and only where it helps (a
 * scale finer than 8 m per block, an area inside one region).
 */
final class MapDataOffer {

    private static CreateWorldScreen askedFor;

    private MapDataOffer() {
    }

    /** True when the look-up or the offer is on screen: creation waits for the answer, then resumes by itself. */
    static boolean offer(CreateWorldScreen screen) {
        if (askedFor == screen) return false;
        OrbisConfig cfg = OrbisMod.config();
        if (!cfg.offerMapDownloads || MapDataJob.busy()) return false;
        RealWorldChunkGenerator rw = SpawnGate.generatorForScreen(screen);
        if (rw == null) return false;
        WorldSettings s = rw.settings().isPresent() ? rw.settings().get() : OrbisMod.defaultWorldSettings();
        double mpb = s.metersPerBlock;
        if (mpb >= 8.0) return false;
        double[] box = areaOf(s, mpb);
        askedFor = screen;
        Minecraft mc = Minecraft.getInstance();
        Path data = OrbisMod.dataDir();
        Screen[] looking = new Screen[1];
        looking[0] = new ConfirmScreen(skip -> {
            if (skip) {
                proceed(screen);
            } else {
                askedFor = null; // back to the settings: asked again on the next Create
                mc.setScreenAndShow(screen);
            }
        }, Component.translatable("orbisterrarum.mapdata.title"), Component.translatable("orbisterrarum.mapdata.looking"),
                Component.translatable("orbisterrarum.mapdata.skip"), net.minecraft.network.chat.CommonComponents.GUI_BACK);
        mc.setScreenAndShow(looking[0]);
        CompletableFuture.supplyAsync(() -> {
            try {
                return MapDataJob.plan(data, box, mpb);
            } catch (Exception e) {
                System.err.println("[orbis] Map data look-up failed: " + e);
                return null;
            }
        }).completeOnTimeout(null, 20, TimeUnit.SECONDS).thenAccept(plan -> mc.execute(() -> {
            if (mc.gui.screen() != looking[0]) return; // skipped or gone back meanwhile
            if (plan == null) {
                proceed(screen);
                return;
            }
            mc.setScreenAndShow(new ConfirmScreen(go -> {
                if (go) start(plan, cfg.keepDownloadedMapFiles);
                proceed(screen);
            }, Component.translatable("orbisterrarum.mapdata.title"),
                    Component.translatable("orbisterrarum.mapdata.offer", plan.region().name(), plan.sizeText()),
                    Component.translatable("orbisterrarum.mapdata.download"), Component.translatable("orbisterrarum.mapdata.notnow")));
        }));
        return true;
    }

    private static void proceed(CreateWorldScreen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
        ((CreateWorldScreenInvoker) screen).orbis$invokeOnCreate();
    }

    /** The selected area, or the land around the spawn when nothing is selected. */
    private static double[] areaOf(WorldSettings s, double mpb) {
        double s0 = 90, w0 = 180, n0 = -90, e0 = -180;
        boolean any = false;
        if (s.pregenShapes != null) {
            for (OrbisConfig.PregenShape shape : s.pregenShapes) {
                if (shape == null || shape.subtract || shape.latLon == null) continue;
                for (double[] p : shape.latLon) {
                    s0 = Math.min(s0, p[0]);
                    n0 = Math.max(n0, p[0]);
                    w0 = Math.min(w0, p[1]);
                    e0 = Math.max(e0, p[1]);
                    any = true;
                }
            }
        }
        if (any) return new double[]{s0, w0, n0, e0};
        double lat = s.customSpawn ? s.spawnLat : s.originLat, lon = s.customSpawn ? s.spawnLon : s.originLon;
        return MapDataJob.boxAround(lat, lon, mpb);
    }

    /** Starts the download with a progress toast that stays up until it is done. */
    static void start(MapDataJob.Plan plan, boolean keepFile) {
        Minecraft mc = Minecraft.getInstance();
        ProgressToast toast = new ProgressToast(Component.translatable("orbisterrarum.mapdata.toast", plan.region().name()).getString());
        toast.set(Component.translatable("orbisterrarum.mapdata.starting").getString(), -1);
        mc.gui.toastManager().addToast(toast);
        MapDataJob.start(OrbisMod.dataDir(), plan, keepFile, new MapDataJob.Listener() {
            @Override
            public void progress(String line, float fraction) {
                toast.set(line, fraction);
            }

            @Override
            public void finished(String message, boolean ok) {
                System.out.println("[orbis] " + message);
                toast.finish(Component.translatable(ok ? "orbisterrarum.mapdata.done" : "orbisterrarum.mapdata.failed").getString(), message);
            }
        });
    }
}
