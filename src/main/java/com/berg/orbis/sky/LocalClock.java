package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.schedule.Activity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * The real local clock at a world's place, for villagers who keep people's hours rather than the sun's: up at
 * 07:00, at work 08:00 to 16:00, out meeting until 18:00, about until 22:00, asleep until 07:00 again, as in the
 * town they live in (in a Bergen winter they go to work in the dark and come home in it).
 *
 * The time zone of the place comes from Open-Meteo (free, no key; one question per world, kept in the world
 * folder), so daylight saving is right; until it answers, or without the internet, the zone is estimated from the
 * longitude.
 */
public final class LocalClock {

    private static volatile ZoneId zone = ZoneOffset.UTC;
    private static volatile String zoneFor = "";

    private LocalClock() {
    }

    /** Looks the place's time zone up (in the background) unless it is known already for this place. */
    public static void prepare(double lat, double lon, Path worldDir) {
        String key = String.format(Locale.ROOT, "%.3f,%.3f", lat, lon);
        if (key.equals(zoneFor)) return;
        zone = estimate(lon);
        zoneFor = key;
        Path file = worldDir == null ? null : worldDir.resolve("orbis-timezone.txt");
        try {
            if (file != null && Files.exists(file)) {
                String[] saved = Files.readString(file, StandardCharsets.UTF_8).trim().split("\\s+");
                if (saved.length == 2 && saved[0].equals(key)) {
                    zone = ZoneId.of(saved[1]);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        Thread t = new Thread(() -> {
            try {
                String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&current=temperature_2m", lat, lon);
                OrbisHttp.Response r = OrbisHttp.get(url, Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)"), 30);
                if (r.status() != 200) return;
                JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
                ZoneId z = ZoneId.of(o.get("timezone").getAsString());
                zone = z;
                if (file != null) Files.writeString(file, key + " " + z.getId(), StandardCharsets.UTF_8);
                System.out.println("[orbis] Local time at the world's place: " + z.getId());
            } catch (Exception e) {
                System.err.println("[orbis] Time zone lookup failed (" + e + "); villagers keep an estimated local time");
            }
        }, "Orbis-timezone");
        t.setDaemon(true);
        t.start();
    }

    /** A zone from the longitude alone: whole hours from Greenwich, no daylight saving. */
    private static ZoneId estimate(double lon) {
        int hours = (int) Math.round(lon / 15.0);
        return ZoneOffset.ofHours(Math.max(-12, Math.min(14, hours)));
    }

    public static LocalTime now() {
        return ZonedDateTime.now(zone).toLocalTime();
    }

    /** What a villager does at this hour: grown-ups keep working hours, children play by day. */
    public static Activity villagerActivity(boolean baby) {
        int m = now().getHour() * 60 + now().getMinute();
        boolean night = m >= 22 * 60 || m < 7 * 60;
        if (baby) return night ? Activity.REST : Activity.PLAY;
        if (night) return Activity.REST;
        if (m < 8 * 60) return Activity.IDLE;
        if (m < 16 * 60) return Activity.WORK;
        if (m < 18 * 60) return Activity.MEET;
        return Activity.IDLE;
    }
}
