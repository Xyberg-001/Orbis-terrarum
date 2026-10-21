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
 * off; checks the player's own weather (what the server sends, then rain with thunder and clear of its own, with the
 * rain and thunder levels the game reports, screenshots, and rain or snow by the temperature); opens Minecraft's own
 * video settings (the far view options' mixin), and quits. Every line starts "[orbis] Self test". {@code selftest.txt}
 * does the same.
 */
public final class SelfTest {
    private static boolean on;
    /** "explore" in the trigger file: also new land (spectator, teleports) and Orbis' own screens. For a copy of a world only. */
    private static boolean explore;
    private static int exploreLeg;
    private static double[] home;
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
            Path full = configDir.resolve("selftest.txt");
            String words = Files.exists(full) ? Files.readString(full) : "";
            if (Files.deleteIfExists(full) | Files.deleteIfExists(trigger)) {
                on = true;
                explore = words.contains("explore");
                if (explore) log("explore: new land and Orbis' screens too (meant for a copy of a world: it teleports and makes chunks)");
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
                case 5 -> { // the player's own weather, as the server sends it (or a minute gone)
                    if (!LocalWeather.active() && now - stepAt < 60_000) return;
                    BlockPos at = mc.player.blockPosition();
                    next(now, "local weather from the server: " + (LocalWeather.active() ? LocalWeather.why() : "NONE after 60 s") + "; " + weather(mc)
                            + String.format(java.util.Locale.ROOT, "; the server's temperature at the player %.1f C", com.berg.orbis.sky.RealPrecipitation.serverTempAt(at.getX(), at.getY(), at.getZ())));
                }
                case 6 -> {
                    if (now - stepAt < 2_000) return;
                    LocalWeather.debugSet(true, true, 8, mc.player.getY());
                    next(now, "local weather set to rain with thunder at 8 C");
                }
                case 7 -> {
                    if (now - stepAt < 8_000) return;
                    Screenshot.grab(mc, true);
                    next(now, "after 8 s: " + weather(mc) + "; screenshot taken");
                }
                case 8 -> {
                    if (now - stepAt < 2_000) return;
                    LocalWeather.debugSet(true, false, -6, mc.player.getY());
                    next(now, "local weather set to snow weather at -6 C");
                }
                case 9 -> {
                    if (now - stepAt < 8_000) return;
                    Screenshot.grab(mc, true);
                    next(now, "after 8 s: " + weather(mc) + "; screenshot taken");
                }
                case 10 -> {
                    if (now - stepAt < 2_000) return;
                    LocalWeather.debugSet(false, false, 8, mc.player.getY());
                    next(now, "local weather set to clear");
                }
                case 11 -> {
                    if (now - stepAt < 8_000) return;
                    Screenshot.grab(mc, true);
                    LocalWeather.reset(); // the server's next report brings the real one back
                    com.berg.orbis.sky.RealPrecipitation.clearClient();
                    next(now, "after 8 s: " + weather(mc) + "; screenshot taken; local weather handed back");
                    if (explore) step = 20; // new land first, then the video settings (12)
                }
                case 20 -> { // spectator, so teleports into land not made yet are safe
                    if (now - stepAt < 2_000) return;
                    home = new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
                    command(mc, "gamemode spectator @a");
                    next(now, "spectator for the teleports (for land past a world's selection, put off in its orbis-pregen/hard-limit.txt first)");
                }
                case 21 -> { // three trips about 3 km out (at 1:1; scaled by the world), each 90 s for the land to be made
                    if (now - stepAt < (exploreLeg == 0 ? 2_000 : 90_000)) return;
                    if (exploreLeg > 0) {
                        Screenshot.grab(mc, true);
                        log(String.format(java.util.Locale.ROOT, "trip %d: at %s, %d chunks loaded, %s; screenshot taken", exploreLeg, mc.player.blockPosition().toShortString(),
                                mc.level.getChunkSource().getLoadedChunksCount(), ground(mc)));
                    }
                    double mpb = OrbisClient.worldInfo == null ? 1 : OrbisClient.worldInfo.metersPerBlock();
                    double d = 3_000 / mpb;
                    double[][] legs = {{d, 0}, {0, d}, {-d, -d}};
                    if (exploreLeg < legs.length) {
                        double x = home[0] + legs[exploreLeg][0], z = home[2] + legs[exploreLeg][1];
                        command(mc, String.format(java.util.Locale.ROOT, "tp @a %.1f %.1f %.1f 270 40", x, home[1] + 120, z)); // facing west, 40 degrees down
                        log(String.format(java.util.Locale.ROOT, "trip %d: teleported to %.0f, %.0f", exploreLeg + 1, x, z));
                        exploreLeg++;
                        stepAt = now;
                        return;
                    }
                    command(mc, String.format(java.util.Locale.ROOT, "tp @a %.1f %.1f %.1f", home[0], home[1], home[2]));
                    next(now, "back home");
                }
                case 22 -> { // Orbis' world map
                    if (now - stepAt < 5_000) return;
                    mc.setScreenAndShow(new com.berg.orbis.client.map.WorldMapScreen(OrbisClient.worldInfo));
                    next(now, "world map opened");
                }
                case 23 -> {
                    if (now - stepAt < 12_000) return;
                    Screenshot.grab(mc, true);
                    mc.setScreenAndShow(null);
                    next(now, "screenshot of the world map taken");
                }
                case 24 -> { // the area preview of world creation, on the world's place
                    if (now - stepAt < 3_000) return;
                    var w = OrbisClient.worldInfo;
                    mc.setScreenAndShow(new AreaPreviewScreen(null, w.originLat(), w.originLon(), w.metersPerBlock(), w.projection(), s -> {
                    }, c -> {
                    }, () -> true, b -> {
                    }, () -> false, b -> {
                    }));
                    next(now, "the area preview opened");
                }
                case 25 -> {
                    if (now - stepAt < 12_000) return;
                    Screenshot.grab(mc, true);
                    mc.setScreenAndShow(DataSourcesScreen.manage(null, com.berg.orbis.OrbisMod.config(), OrbisClient.worldInfo.originLat(),
                            OrbisClient.worldInfo.originLon()));
                    next(now, "screenshot of the area preview taken; data sources opened");
                }
                case 26 -> {
                    if (now - stepAt < 5_000) return;
                    Screenshot.grab(mc, true);
                    mc.setScreenAndShow(null);
                    command(mc, "gamemode creative @a");
                    step = 12;
                    stepAt = now;
                    log("screenshot of the data sources taken; back to the video settings check");
                }
                case 12 -> {
                    if (now - stepAt < 3_000) return;
                    mc.setScreenAndShow(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
                    next(now, "Minecraft's video settings opened (the far view mixin loaded with it)");
                }
                case 13 -> {
                    if (now - stepAt < 3_000) return;
                    Screenshot.grab(mc, true);
                    mc.setScreenAndShow(null);
                    next(now, "screenshot of the video settings taken");
                }
                case 14 -> {
                    if (now - stepAt < 3_000) return;
                    if (pauseOnLostFocus != null) mc.options.pauseOnLostFocus = pauseOnLostFocus;
                    restoreClock(mc);
                    next(now, "options and clock put back");
                }
                case 15 -> {
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

    /** The land under the player as the client has it, and as the server has it. */
    private static String ground(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        var chunk = mc.level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        int nonEmpty = 0;
        for (var section : chunk.getSections()) if (!section.hasOnlyAir()) nonEmpty++;
        int top = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
        String block = mc.level.getBlockState(new BlockPos(p.getX(), top - 1, p.getZ())).toString();
        String server = "";
        var srv = mc.getSingleplayerServer();
        if (srv != null) {
            var lvl = srv.overworld();
            var sc = lvl.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            server = sc == null ? "; server: chunk not loaded"
                    : "; server: top Y " + sc.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX() & 15, p.getZ() & 15);
        }
        return "client: top Y " + top + " (" + block + "), " + nonEmpty + " non-empty sections in the player's chunk" + server;
    }

    /** A command run by the singleplayer server, as the server. */
    private static void command(Minecraft mc, String cmd) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    /** What the game shows of the weather now, and what would fall at the player. */
    private static String weather(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        Biome biome = mc.level.getBiome(at).value();
        return String.format(java.util.Locale.ROOT, "rain level %.2f, thunder level %.2f, raining %b, falling at the player: %s (world weather raining %b)",
                mc.level.getRainLevel(1f), mc.level.getThunderLevel(1f), mc.level.isRaining(), biome.getPrecipitationAt(at, mc.level.getSeaLevel()),
                mc.getSingleplayerServer() != null && mc.getSingleplayerServer().overworld().isRaining());
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
