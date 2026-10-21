package com.berg.orbis.client;

import com.berg.orbis.net.LocalWeatherPayload;
import com.berg.orbis.sky.RealPrecipitation;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Each player's own weather on their screen (players with Orbis, real weather on). Minecraft has one weather for a
 * whole world, which the server sets from the players' mean place; players far apart shared it. The server sends each
 * player the weather where they are ({@link LocalWeatherPayload}, every two minutes); here it eases in at vanilla's pace
 * (a hundredth a tick: five seconds from clear to full rain) and is what the client level reports as its rain and
 * thunder levels (LevelLocalWeatherMixin), so rain or snow falls, the sky darkens and the rain sounds where the player
 * really is. Thunder that the world's weather does not have (its lightning is the server's) rolls in the distance now
 * and then. Rain or snow follows the temperature at the player's place. Gameplay (crops, fire, the server's lightning)
 * keeps the world's one weather.
 *
 * Without a report for ten minutes (the server stopped sending, real weather switched off) the world's weather shows
 * again.
 */
public final class LocalWeather {
    private static final long STALE_MS = 10 * 60_000L;
    private static final float STEP = 0.01f;

    private static volatile boolean on, rain, thunder;
    private static volatile long receivedAt;
    private static volatile String why = "";
    private static float rainLevel, oRainLevel, thunderLevel, oThunderLevel;
    private static boolean levelsStarted;
    private static int nextThunder = 200;

    private LocalWeather() {
    }

    public static void receive(LocalWeatherPayload p) {
        if (!p.on()) {
            reset();
            return;
        }
        boolean changed = !on || p.rain() != rain || p.thunder() != thunder;
        rain = p.rain();
        thunder = p.thunder();
        why = p.why();
        receivedAt = System.currentTimeMillis();
        on = true;
        RealPrecipitation.setClient(p.tempC(), p.refY(), p.metresPerBlock());
        if (changed) System.out.println("[orbis] Local weather here: " + (thunder ? "thunder" : rain ? "rain" : "clear") + " (" + why + ")");
    }

    public static void reset() {
        on = false;
        levelsStarted = false;
    }

    /** Whether this player's own weather is shown. */
    public static boolean active() {
        return on && System.currentTimeMillis() - receivedAt < STALE_MS;
    }

    /** For the self test: a weather of its own choosing (as if the server had sent it). */
    static void debugSet(boolean rainNow, boolean thunderNow, double tempC, double atY) {
        receive(new LocalWeatherPayload(true, rainNow, thunderNow, tempC, atY, 1, "self test"));
    }

    public static float rainLevel(float partialTick) {
        return oRainLevel + (rainLevel - oRainLevel) * partialTick;
    }

    public static float thunderLevel(float partialTick) {
        return oThunderLevel + (thunderLevel - oThunderLevel) * partialTick;
    }

    /** Each client tick: the levels a step towards the weather, and the odd distant thunder. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || !active()) {
            levelsStarted = false;
            return;
        }
        if (!levelsStarted) {
            // from what the world showed, so nothing jumps when local weather takes over
            levelsStarted = true;
            boolean was = on;
            on = false;
            rainLevel = oRainLevel = mc.level.getRainLevel(1f);
            thunderLevel = oThunderLevel = mc.level.getThunderLevel(1f) / Math.max(0.01f, rainLevel);
            thunderLevel = oThunderLevel = Math.min(1f, thunderLevel);
            on = was;
        }
        oRainLevel = rainLevel;
        oThunderLevel = thunderLevel;
        rainLevel = approach(rainLevel, rain ? 1f : 0f);
        thunderLevel = approach(thunderLevel, thunder ? 1f : 0f);
        if (thunder && rainLevel > 0.5f && mc.player != null && --nextThunder <= 0) {
            nextThunder = 300 + mc.level.getRandom().nextInt(900); // every 15 to 60 seconds
            float volume = 4f + mc.level.getRandom().nextFloat() * 6f;
            mc.level.playLocalSound(mc.player.getX() + (mc.level.getRandom().nextFloat() - 0.5f) * 200, mc.player.getY() + 40,
                    mc.player.getZ() + (mc.level.getRandom().nextFloat() - 0.5f) * 200, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                    volume, 0.8f + mc.level.getRandom().nextFloat() * 0.2f, false);
        }
    }

    private static float approach(float v, float target) {
        if (v < target) return Math.min(target, v + STEP);
        if (v > target) return Math.max(target, v - STEP);
        return v;
    }

    static String why() {
        return why;
    }
}
