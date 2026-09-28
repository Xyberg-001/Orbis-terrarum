package com.berg.orbis.biome;

import com.berg.orbis.config.OrbisConfig;

import java.util.Locale;

/**
 * Approximates the real climate of a coordinate from latitude and elevation
 * (a simplified Köppen-style model): mean annual temperature from a
 * latitude curve plus the standard 6.5 C/km lapse rate, then a climate zone
 * from that temperature. OSM land cover is applied on top by the biome
 * source and the painter, so this only has to answer "how cold / how dry".
 *
 * Precipitation is the documented gap: without a rainfall dataset the warm
 * zones are ambiguous (deserts and rainforests share latitudes), so
 * {@code climateOverride} in the config lets a player force "arid" or
 * "humid" for the area they are generating.
 */
public class BiomeClassifier {

    public enum Zone { ICE_CAP, TUNDRA, ALPINE, TAIGA, TEMPERATE, SUBTROPICAL, ARID, SAVANNA, TROPICAL_HUMID }

    public record Climate(Zone zone, double meanTempC, boolean snowy, boolean frozenWater, double elevation) {
        public boolean isArid() {
            return zone == Zone.ARID;
        }

        public boolean isTropical() {
            return zone == Zone.TROPICAL_HUMID || zone == Zone.SAVANNA;
        }

        public boolean isCold() {
            return zone == Zone.ICE_CAP || zone == Zone.TUNDRA || zone == Zone.ALPINE || zone == Zone.TAIGA;
        }
    }

    private final OrbisConfig cfg;
    private final String override;

    public BiomeClassifier(OrbisConfig cfg) {
        this.cfg = cfg;
        this.override = cfg.climateOverride == null ? "" : cfg.climateOverride.trim().toLowerCase(Locale.ROOT);
    }

    /** Mean annual temperature estimate in Celsius. */
    public double meanTemperature(double lat, double elevationMeters) {
        double absLat = Math.abs(lat);
        double elev = Double.isNaN(elevationMeters) ? 0 : Math.max(0, elevationMeters);
        // 28.5 C at the equator falling ~0.35 C per degree; matches coastal
        // stations reasonably (Bergen 60N ~7.5, London 51N ~11, Madrid 40N ~15).
        return 28.5 - 0.35 * absLat - 0.0065 * elev + cfg.temperatureOffsetC;
    }

    public Climate classify(double lat, double elevationMeters) {
        double t = meanTemperature(lat, elevationMeters);
        double elev = Double.isNaN(elevationMeters) ? 0 : elevationMeters;
        double absLat = Math.abs(lat);
        boolean snowy = cfg.snowAtHighElevation && t < cfg.snowTemperatureC;
        boolean frozen = t < cfg.snowTemperatureC - 2.5;

        Zone zone;
        if (t < -6 || elev > 4800) zone = Zone.ICE_CAP;
        else if (elev > 2600 && t < 8) zone = Zone.ALPINE;
        else if (t < 1.0) zone = Zone.TUNDRA;
        else if (t < 7.5) zone = Zone.TAIGA;
        else if (t < 16) zone = Zone.TEMPERATE;
        else if (t < 21) zone = "arid".equals(override) ? Zone.ARID : Zone.SUBTROPICAL;
        else {
            if ("arid".equals(override) || "desert".equals(override)) zone = Zone.ARID;
            else if ("humid".equals(override) || "tropical".equals(override) || "rainforest".equals(override)) zone = Zone.TROPICAL_HUMID;
            else if (absLat < 10) zone = Zone.TROPICAL_HUMID;
            else zone = Zone.SAVANNA;
        }
        if ("arid".equals(override) && (zone == Zone.TEMPERATE || zone == Zone.SUBTROPICAL) && t > 12) zone = Zone.ARID;
        return new Climate(zone, t, snowy, frozen, elev);
    }
}
