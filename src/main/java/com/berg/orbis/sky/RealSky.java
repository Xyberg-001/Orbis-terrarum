package com.berg.orbis.sky;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.saveddata.WeatherData;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The real sky over an Orbis world, run by the server (players need nothing installed):
 * <ul>
 * <li><b>Real daylight</b>: the overworld clock follows the sun at the world's origin, so the sun rises and sets
 * when it really does there (long summer days and short winter ones in the north) and the moon shows its real
 * phase. The clock is set every 10 seconds and runs at the real sun's pace in between, so the sky moves smoothly.
 * Sleeping does not skip a real night: the next update puts the clock back.</li>
 * <li><b>Real weather</b>: clear, rain or thunder where the players are (the average of their positions; the origin
 * when nobody is online), as measured where it can be ({@link LiveWeather}: rain radar in the Nordic countries,
 * Germany and Canada, the nearest airport's report anywhere) and from the Norwegian Meteorological Institute's
 * forecast for the coming hour where nothing is measured.
 * Whether the rain falls as snow is the biome's business, as always in Minecraft.</li>
 * </ul>
 * Both are installation settings that apply at once. Turning daylight off hands the clock back to Minecraft at its
 * normal pace; turning weather off lets Minecraft's own weather cycle take over again.
 */
public final class RealSky {

    private static final int CLOCK_EVERY = 200;      // ticks (10 s)
    private static final int WEATHER_EVERY = 2400;   // ticks (2 min); the forecast's own expiry decides real requests
    private static final int WEATHER_HOLD = 30 * 60 * 20; // how long Minecraft keeps a weather we set (30 min)

    private static final LiveWeather LIVE = new LiveWeather();
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-weather");
        t.setDaemon(true);
        return t;
    });

    private static long ticks;
    private static boolean clockTaken;
    private static volatile boolean fetching;
    private static volatile MetWeather.Report pending;
    /** With the pending report: the block height its temperature stands for and the metres one block of height is. */
    private static volatile double[] pendingRef;
    /** Which world's run asked for the weather: an answer for an earlier one (in flight as it closed) is dropped. */
    private static volatile int run;
    private static MetWeather.Report applied;
    private static String lastError;

    private RealSky() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> RealPrecipitation.clear());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RealPrecipitation.serverThread(Thread.currentThread()); // this event runs on the server's thread
            RealPrecipitation.clear();
            ticks = 0;
            clockTaken = false;
            applied = null;
            pending = null;
            run++;
            fetching = false; // a request of the last world's may still be on its way: its answer is dropped
            // The season pack for the next time this world is loaded (a month may have turned while it ran, and a
            // world made before this existed gets its first one).
            WorldModel m = OrbisMod.model();
            if (m != null && server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator) {
                // The town's time zone, for villagers who keep clock hours.
                LocalClock.prepare(m.cfg().originLat, m.cfg().originLon, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
                java.nio.file.Path packs = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR);
                boolean seasons = m.cfg().realSeasons; // the world's own setting decides
                Seasons.rememberWorldChoice(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), m.cfg().realSeasons);
                java.nio.file.Path data = com.berg.orbis.OrbisMod.dataDir();
                if (Seasons.update(packs, data, m.cfg().originLat, m.cfg().originLon, seasons)) {
                    System.out.println("[orbis] Season now " + Seasons.seasonName(data, m.cfg().originLat, m.cfg().originLon)
                            + ": the world shows it from the next time it is opened");
                }
                // A season pack the world has not switched on (new, or once left out of a reload and so disabled):
                // switch it on with a reload once the server is idle, so the world shows it from the next opening.
                if (java.nio.file.Files.isDirectory(packs.resolve(Seasons.PACK_NAME))
                        && !server.getPackRepository().getSelectedIds().contains("file/" + Seasons.PACK_NAME)) {
                    System.out.println("[orbis] The season pack is not switched on in this world: switching it on");
                    com.berg.orbis.worldgen.Landmarks.requestPackReload(server);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(RealSky::tick);
    }

    private static void tick(MinecraftServer server) {
        WorldModel m = OrbisMod.model();
        ServerLevel overworld = server.overworld();
        if (m == null || overworld == null || !(overworld.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
        // Each is on when the world has it (its settings) and this game or server has not switched it off.
        OrbisConfig cfg = OrbisMod.config();
        boolean daylight = cfg.realDaylight && m.cfg().realDaylight, weather = cfg.realWeather && m.cfg().realWeather;
        long t = ticks++;
        if (t % CLOCK_EVERY == 0) daylight(server, overworld, m, daylight);
        if (weather) {
            if (t % WEATHER_EVERY == 0 && !fetching) requestWeather(server, m);
            MetWeather.Report r = pending;
            if (r != null) {
                pending = null;
                applyWeather(overworld, r, m);
            }
        } else {
            if (applied != null) {
                RealPrecipitation.clear(); // the biomes decide rain or snow again
                sendTemperature(server, null);
            }
            applied = null;
        }
    }

    /** The real temperature for rain or snow to every player with Orbis (to one when {@code only} is given); none clears it. */
    public static void sendTemperature(MinecraftServer server, ServerPlayer only) {
        double[] t = RealPrecipitation.current();
        var payload = t == null ? new com.berg.orbis.net.RealTemperaturePayload(Double.NaN, 0, 1) : new com.berg.orbis.net.RealTemperaturePayload(t[0], t[1], t[2]);
        for (ServerPlayer p : only != null ? java.util.List.of(only) : server.getPlayerList().getPlayers()) {
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, com.berg.orbis.net.RealTemperaturePayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
            }
        }
    }

    // ------------------------------------------------------------------ daylight

    private static void daylight(MinecraftServer server, ServerLevel overworld, WorldModel m, boolean on) {
        Optional<Holder<WorldClock>> clock = overworld.dimensionType().defaultClock();
        if (clock.isEmpty()) return;
        if (!on) {
            if (clockTaken) {
                server.clockManager().setRate(clock.get(), 1.0f);
                clockTaken = false;
                System.out.println("[orbis] Real daylight off: Minecraft's clock runs at its own pace again");
            }
            return;
        }
        SolarClock.Time time = SolarClock.timeOfDay(Instant.now(), m.cfg().originLat, m.cfg().originLon);
        server.clockManager().setTotalTicks(clock.get(), time.totalTicks());
        server.clockManager().setRate(clock.get(), (float) time.rate());
        if (!clockTaken) {
            clockTaken = true;
            System.out.println(String.format(Locale.ROOT, "[orbis] Real daylight: the sun follows %.4f, %.4f (Minecraft time %d, moon phase %d)",
                    m.cfg().originLat, m.cfg().originLon, (long) time.tickOfDay(), time.day() % 8));
        }
    }

    // ------------------------------------------------------------------ weather

    private static void requestWeather(MinecraftServer server, WorldModel m) {
        double x = 0, z = 0;
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != server.overworld()) continue;
            x += p.getX();
            z += p.getZ();
            n++;
        }
        double[] ll = n == 0 ? new double[]{m.cfg().originLat, m.cfg().originLon} : m.mapper().toLatLonExact(x / n, z / n);
        int bx = (int) Math.floor(n == 0 ? 0 : x / n), bz = (int) Math.floor(n == 0 ? 0 : z / n);
        fetching = true;
        int askedIn = run;
        NET.execute(() -> {
            try {
                MetWeather.Report report = LIVE.now(ll[0], ll[1]);
                // where the temperature stands: the ground at the players' place, and how many real metres a block of height is there
                double[] ref = null;
                try {
                    double e = m.elevation(bx, bz);
                    double y0 = m.vertical().toY(e, bx, bz), y1 = m.vertical().toY(e + 100, bx, bz);
                    if (!Double.isNaN(y0) && y1 - y0 > 1e-6) ref = new double[]{y0, 100 / (y1 - y0)};
                } catch (RuntimeException ignored) {
                    // no height there yet: rain or snow stays the biomes' this time
                }
                // the last world's weather was applied to the next one when it came after that one opened
                if (askedIn == run) {
                    pendingRef = ref;
                    pending = report;
                }
                lastError = null;
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                if (!msg.equals(lastError)) System.err.println("[orbis] Real weather unavailable (" + msg + "); trying again in 2 minutes");
                lastError = msg;
            } finally {
                fetching = false;
            }
        });
    }

    private static void applyWeather(ServerLevel overworld, MetWeather.Report r, WorldModel m) {
        double[] ref = pendingRef;
        if (ref != null) RealPrecipitation.set(r.temperatureC(), ref[0], ref[1]);
        else RealPrecipitation.clear();
        sendTemperature(overworld.getServer(), null);
        WeatherData w = overworld.getWeatherData();
        boolean rain = r.sky() != MetWeather.Sky.CLEAR, thunder = r.sky() == MetWeather.Sky.THUNDER;
        // As /weather sets it, with a long hold so Minecraft's own cycle does not change it before the next report.
        w.setClearWeatherTime(rain ? 0 : WEATHER_HOLD);
        w.setRainTime(rain ? WEATHER_HOLD : 0);
        w.setThunderTime(thunder ? WEATHER_HOLD : 0);
        w.setRaining(rain);
        w.setThundering(thunder);
        w.setDirty();
        if (applied == null || applied.sky() != r.sky() || !applied.symbol().equals(r.symbol())) {
            System.out.println(String.format(Locale.ROOT, "[orbis] Real weather: %s (%s, %.1f C)", r.sky().name().toLowerCase(Locale.ROOT), r.symbol(), r.temperatureC()));
        }
        applied = r;
    }
}
