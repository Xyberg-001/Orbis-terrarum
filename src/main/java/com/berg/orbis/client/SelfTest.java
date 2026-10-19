package com.berg.orbis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A check of the client's season colours that runs itself, for the developer: when {@code selftest-seasons.txt} is in
 * the config folder as the game starts (it is deleted at once, so it runs one time), the game, once in a world, waits
 * for local seasons, logs the season and the grass and leaf colours at the player and at places 2, 20 and 200 km
 * away (with the colour the season pack alone gives), takes a screenshot, takes another with local seasons switched
 * off, opens Minecraft's own video settings (the far view options' mixin), and quits. Every line starts
 * "[orbis] Self test".
 */
public final class SelfTest {
    private static boolean on;
    private static int step;
    private static long stepAt;
    private static Boolean pauseOnLostFocus;
    /** The world's clock before the test set it to noon (singleplayer), and its game time then; restored at the end. */
    private static long clockBefore = -1, gameTimeBefore;

    private SelfTest() {
    }

    public static void init(Path configDir) {
        Path trigger = configDir.resolve("selftest-seasons.txt");
        try {
            if (Files.deleteIfExists(trigger)) {
                on = true;
                log("on: will check local season colours once a world is open, then quit");
            }
        } catch (Exception ignored) {
        }
    }

    public static void tick(Minecraft mc) {
        if (!on) return;
        long now = System.currentTimeMillis();
        try {
            switch (step) {
                case 0 -> { // in a world
                    if (mc.level == null) {
                        confirmExperimental(mc);
                        return;
                    }
                    if (mc.player == null) return;
                    if (pauseOnLostFocus == null) {
                        pauseOnLostFocus = mc.options.pauseOnLostFocus;
                        mc.options.pauseOnLostFocus = false; // the window may be behind another one: keep playing
                    }
                    noon(mc);
                    next(now, "in the world at " + mc.player.blockPosition().toShortString());
                }
                case 1 -> { // local seasons on, or a minute and a half gone
                    if (mc.gui.screen() != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreenAndShow(null);
                    if (!LocalSeasons.active() && now - stepAt < 90_000) return;
                    next(now, "local seasons " + (LocalSeasons.active() ? "active" : "NOT active after 90 s") + "; letting the chunks draw");
                }
                case 2 -> { // 25 s for the view to draw, then the report and a screenshot
                    if (now - stepAt < 25_000) return;
                    report(mc);
                    Screenshot.grab(mc, true);
                    next(now, "screenshot with local seasons taken");
                }
                case 3 -> {
                    if (now - stepAt < 3_000) return;
                    LocalSeasons.suspended = true;
                    LocalSeasons.recolour();
                    next(now, "local seasons switched off for a comparison");
                }
                case 4 -> {
                    if (now - stepAt < 20_000) return;
                    Screenshot.grab(mc, true);
                    LocalSeasons.suspended = false;
                    LocalSeasons.recolour();
                    next(now, "screenshot with the season pack alone taken; local seasons back on");
                }
                case 5 -> {
                    if (now - stepAt < 5_000) return;
                    mc.setScreenAndShow(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
                    next(now, "Minecraft's video settings opened (the far view mixin loaded with it)");
                }
                case 6 -> {
                    if (now - stepAt < 3_000) return;
                    Screenshot.grab(mc, true);
                    mc.setScreenAndShow(null);
                    next(now, "screenshot of the video settings taken");
                }
                case 7 -> {
                    if (now - stepAt < 3_000) return;
                    if (pauseOnLostFocus != null) mc.options.pauseOnLostFocus = pauseOnLostFocus;
                    restoreClock(mc);
                    next(now, "options and clock put back");
                }
                case 8 -> {
                    if (now - stepAt < 2_000) return;
                    on = false;
                    log("done: quitting");
                    mc.stop();
                }
                default -> on = false;
            }
        } catch (Throwable t) {
            log("FAILED at step " + step + ": " + t);
            t.printStackTrace();
            if (pauseOnLostFocus != null) mc.options.pauseOnLostFocus = pauseOnLostFocus;
            restoreClock(mc);
            on = false;
            mc.stop();
        }
    }

    /**
     * Minecraft asks before opening a world with custom world generation (every Orbis world): the test answers "I Know
     * What I'm Doing", as a player opening it does, so it can run unattended.
     */
    private static boolean confirmed;

    private static void confirmExperimental(Minecraft mc) {
        // once only: the screen stays up a few ticks while the world opens, and each press opened it again, the
        // second taking the first's session lock (the integrated server stopped, "Lock is no longer valid")
        if (confirmed || !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen)) return;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button b
                    && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && "selectWorld.backupJoinSkipButton".equals(t.getKey())) {
                confirmed = true;
                log("the experimental settings warning: answered \"I Know What I'm Doing\"");
                b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0)); // as Enter on it
                return;
            }
        }
    }

    /** Daylight for the screenshots (a singleplayer world only): the clock to noon of the same day; put back at the end. */
    private static void noon(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            var level = server.overworld();
            var clock = level.dimensionType().defaultClock();
            if (clock.isEmpty()) return;
            clockBefore = level.getOverworldClockTime();
            gameTimeBefore = level.getGameTime();
            server.clockManager().setTotalTicks(clock.get(), clockBefore / 24000 * 24000 + 6000);
            log("clock set to noon for the screenshots (was " + clockBefore + ")");
        });
    }

    private static void restoreClock(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || clockBefore < 0) return;
        long before = clockBefore;
        clockBefore = -1;
        server.execute(() -> {
            var level = server.overworld();
            var clock = level.dimensionType().defaultClock();
            if (clock.isEmpty()) return;
            long back = before + (level.getGameTime() - gameTimeBefore); // as if the test had not happened
            server.clockManager().setTotalTicks(clock.get(), back);
            log("clock put back to " + back);
        });
    }

    private static void next(long now, String what) {
        log(what);
        step++;
        stepAt = now;
    }

    private static void report(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        double mpb = OrbisClient.worldInfo == null ? 1 : OrbisClient.worldInfo.metersPerBlock();
        int[][] offsets = {{0, 0}, {(int) (2_000 / mpb), 0}, {0, (int) (20_000 / mpb)}, {(int) (200_000 / mpb), 0}, {0, (int) (-200_000 / mpb)}};
        for (int[] o : offsets) {
            int x = at.getX() + o[0], z = at.getZ() + o[1];
            BlockPos p = new BlockPos(x, at.getY(), z);
            Biome biome = mc.level.getBiome(p).value();
            String id = String.valueOf(mc.level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome));
            int grass = BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, x, z);
            int leaves = BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, x, z);
            boolean was = LocalSeasons.suspended;
            LocalSeasons.suspended = true;
            int packGrass = BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, x, z);
            int packLeaves = BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, x, z);
            LocalSeasons.suspended = was;
            log(String.format(java.util.Locale.ROOT, "%+d,%+d %s | %s | grass #%06x (pack alone #%06x), leaves #%06x (pack alone #%06x)", o[0], o[1], id,
                    LocalSeasons.describe(x, z), grass & 0xFFFFFF, packGrass & 0xFFFFFF, leaves & 0xFFFFFF, packLeaves & 0xFFFFFF));
        }
    }

    private static void log(String s) {
        System.out.println("[orbis] Self test: " + s);
    }
}
