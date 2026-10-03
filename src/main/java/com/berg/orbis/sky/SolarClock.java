package com.berg.orbis.sky;

import java.time.Instant;

/**
 * Where the real sun and moon are, as Minecraft time: sunrise at tick 0, solar noon 6000, sunset 12000 and solar
 * midnight 18000, linear in between, so a Minecraft day at the world's place has the real day's length and the
 * sun is up exactly when it is up there. Sunrise and sunset follow the NOAA solar calculation (accurate to about a
 * minute away from the poles). Under the midnight sun or the polar night the day is clamped to at least one hour
 * of light or of dark: Minecraft has no sun that circles the horizon.
 */
public final class SolarClock {

    public static final int DAY = 24_000;
    /** Shortest day or night (hours) the clock shows, for the polar night and the midnight sun. */
    private static final double MIN_HOURS = 1.0;
    /** A new moon (2000-01-06 18:14 UTC) and the synodic month, for the moon's phase. */
    private static final double NEW_MOON_JD = 2451550.26, SYNODIC_DAYS = 29.530588853;

    private SolarClock() {
    }

    /**
     * The Minecraft time for a moment at a place.
     *
     * @param tickOfDay 0 (sunrise) until 24000
     * @param rate      ticks of time per game tick at this moment, so the clock keeps up with the real sun
     * @param day       the Minecraft day it is (days start at sunrise), chosen so that {@code day % 8} is the real
     *                  moon's phase that day; it only ever grows
     */
    public record Time(double tickOfDay, double rate, long day) {
        public long totalTicks() {
            return day * DAY + (long) Math.floor(tickOfDay);
        }
    }

    public static Time timeOfDay(Instant now, double lat, double lon) {
        double jd = julianDay(now);
        // 0h (UTC Julian day) of the place's local solar date
        double day0 = Math.floor(jd + lon / 360.0 + 0.5) - 0.5;
        double noon = solarNoonJd(day0, lat, lon);
        if (jd < noon - 0.5) noon = solarNoonJd(day0 - 1, lat, lon);
        if (jd > noon + 0.5) noon = solarNoonJd(day0 + 1, lat, lon);
        double half = halfDayDays(noon, lat);
        double rise = noon - half, set = noon + half;
        // Points of the day in real time (Julian days) and in ticks; the night either side runs midnight to midnight.
        double[] at = {noon - 0.5, rise, noon, set, noon + 0.5};
        double[] tick = {-6000, 0, 6000, 12000, 18000};
        for (int i = 0; i < 4; i++) {
            if (jd >= at[i] && jd <= at[i + 1]) {
                double f = (jd - at[i]) / (at[i + 1] - at[i]);
                double t = tick[i] + f * (tick[i + 1] - tick[i]);
                double rate = (tick[i + 1] - tick[i]) / ((at[i + 1] - at[i]) * 86400.0 * 20.0);
                // Before sunrise it is still the previous Minecraft day (its night).
                double dayRise = i == 0 ? rise - 1 : rise;
                long date = (long) Math.floor(i == 0 ? noon - 1 : noon);
                long day = 8 * date + moonPhase(dayRise);
                return new Time(t < 0 ? t + DAY : t, rate, day);
            }
        }
        return new Time(18000, 1.0 / 72.0, 8 * (long) Math.floor(noon));
    }

    /** The moon's phase as Minecraft counts it (0 full, 1 waning gibbous ... 4 new ... 7 waxing gibbous). */
    public static int moonPhase(Instant now) {
        return moonPhase(julianDay(now));
    }

    private static int moonPhase(double jd) {
        double age = ((jd - NEW_MOON_JD) / SYNODIC_DAYS) % 1.0;
        if (age < 0) age += 1.0;
        // Minecraft's phase 0 is the full moon (age 0.5); each phase is an eighth of the month.
        return (int) Math.floorMod(Math.round((age + 0.5) * 8), 8L);
    }

    static double julianDay(Instant t) {
        return t.getEpochSecond() / 86400.0 + t.getNano() / 86_400e9 + 2440587.5;
    }

    /** Solar noon (Julian day, UTC) of the local date starting at Julian day {@code day} (0h). */
    private static double solarNoonJd(double day, double lat, double lon) {
        double jd = day + 0.5 - lon / 360.0; // first guess: noon at the place's longitude
        for (int i = 0; i < 2; i++) {
            double eot = equationOfTimeMinutes(jd);
            jd = day + 0.5 - lon / 360.0 - eot / 1440.0;
        }
        return jd;
    }

    /** Half the time the sun is above the horizon (days), clamped for the polar day and night. */
    private static double halfDayDays(double jd, double lat) {
        double decl = Math.toRadians(declinationDeg(jd));
        double phi = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) - Math.sin(phi) * Math.sin(decl)) / (Math.cos(phi) * Math.cos(decl));
        double hours;
        if (cosH >= 1) hours = MIN_HOURS / 2;            // polar night
        else if (cosH <= -1) hours = 12 - MIN_HOURS / 2; // midnight sun
        else hours = Math.toDegrees(Math.acos(cosH)) / 15.0;
        hours = Math.max(MIN_HOURS / 2, Math.min(12 - MIN_HOURS / 2, hours));
        return hours / 24.0;
    }

    private static double declinationDeg(double jd) {
        double[] s = sun(jd);
        return Math.toDegrees(Math.asin(Math.sin(Math.toRadians(s[1])) * Math.sin(Math.toRadians(s[0]))));
    }

    private static double equationOfTimeMinutes(double jd) {
        double t = (jd - 2451545.0) / 36525.0;
        double l0 = Math.toRadians(norm(280.46646 + t * (36000.76983 + t * 0.0003032)));
        double m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double eps = Math.toRadians(sun(jd)[1]);
        double y = Math.tan(eps / 2) * Math.tan(eps / 2);
        double eq = y * Math.sin(2 * l0) - 2 * e * Math.sin(m) + 4 * e * y * Math.sin(m) * Math.cos(2 * l0)
                - 0.5 * y * y * Math.sin(4 * l0) - 1.25 * e * e * Math.sin(2 * m);
        return 4 * Math.toDegrees(eq);
    }

    /** {apparent longitude, corrected obliquity} of the sun in degrees (NOAA). */
    private static double[] sun(double jd) {
        double t = (jd - 2451545.0) / 36525.0;
        double l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032));
        double m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double c = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double lambda = l0 + c - 0.00569 - 0.00478 * Math.sin(omega);
        double eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double eps = eps0 + 0.00256 * Math.cos(omega);
        return new double[]{lambda, eps};
    }

    private static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }
}
