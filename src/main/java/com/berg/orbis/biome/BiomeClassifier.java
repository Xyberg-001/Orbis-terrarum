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
 *
 * Worlds with real climate ({@code realClimate}, new worlds from 2.0.0) take it from the climate pack shipped with
 * the mod ({@link ClimateMap}): the place's Köppen-Geiger class picks the zone (Bergen oceanic, the Sahara desert,
 * Manaus rainforest, Yakutsk taiga, whatever their latitude), and the temperature is the measured one at sea level
 * with the block's own height taken off (6.5 °C per km). A block that stands 400 m or more above or below the mean
 * of its climate cell (a mountain, a deep valley) takes the colder or warmer zone its own temperature gives.
 */
public class BiomeClassifier {

    public enum Zone { ICE_CAP, TUNDRA, ALPINE, TAIGA, TEMPERATE, SUBTROPICAL, ARID, SAVANNA, TROPICAL_HUMID }

    /**
     * A column's climate. {@code snowy} is the year-round guess from temperature (it picks the biome);
     * {@code snowDepthM} is today's real snow on the ground (metres, NaN when unknown or real snow is off), which the
     * painter lays when known; {@code iceNow} whether water there is frozen today as seen (1), open (0) or not known (-1:
     * the year-round {@code frozenWater} decides).
     */
    public record Climate(Zone zone, double meanTempC, boolean snowy, boolean frozenWater, double elevation, double snowDepthM, int iceNow) {
        public Climate(Zone zone, double meanTempC, boolean snowy, boolean frozenWater, double elevation) {
            this(zone, meanTempC, snowy, frozenWater, elevation, Double.NaN, -1);
        }

        public Climate(Zone zone, double meanTempC, boolean snowy, boolean frozenWater, double elevation, double snowDepthM) {
            this(zone, meanTempC, snowy, frozenWater, elevation, snowDepthM, -1);
        }

        public Climate withSnowDepth(double depthM) {
            return new Climate(zone, meanTempC, snowy, frozenWater, elevation, depthM, iceNow);
        }

        public Climate withIceNow(int ice) {
            return new Climate(zone, meanTempC, snowy, frozenWater, elevation, snowDepthM, ice);
        }

        /** Whether water here is frozen: today's ice where it is seen, else the climate's. */
        public boolean waterFrozen() {
            return iceNow >= 0 ? iceNow == 1 : frozenWater;
        }

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

    /** The climate of a place, from the climate pack where the world has real climate and the place is known. */
    public Climate classify(double lat, double lon, double elevationMeters) {
        ClimateMap map = cfg.realClimate && override.isEmpty() ? ClimateMap.get() : null;
        if (map == null) return classify(lat, elevationMeters);
        double sea = map.seaLevelMeanC(lat, lon);
        int kg = map.koppen(lat, lon);
        if (Double.isNaN(sea) || kg == 0) return classify(lat, elevationMeters);
        double elev = Double.isNaN(elevationMeters) ? 0 : elevationMeters;
        double t = sea - 0.0065 * Math.max(-500, elev) + cfg.temperatureOffsetC;
        double precip = map.precipitationMm(lat, lon);
        Zone zone = koppenZone(kg, t, elev, Math.abs(lat), precip);
        // A block well above or below its climate cell (whose class speaks for the cell's mean height): its own temperature.
        double cellElev = map.cellElevationM(lat, lon);
        double dz = Double.isNaN(cellElev) ? 0 : elev - cellElev;
        if (dz > 400 || elev > 2600) {
            Zone byTemp = temperatureZone(t, elev);
            if (byTemp != null && rank(byTemp) < rank(zone)) zone = byTemp;
        } else if (dz < -400) {
            Zone byTemp = temperatureZone(t, elev);
            if (byTemp != null && rank(byTemp) > rank(zone) && rank(byTemp) < 4) zone = byTemp;
        }
        boolean snowy = cfg.snowAtHighElevation && t < cfg.snowTemperatureC;
        boolean frozen = t < cfg.snowTemperatureC - 2.5;
        return new Climate(zone, t, snowy, frozen, elev);
    }

    /** The zone of a Köppen-Geiger class, with the block's temperature, height, latitude and the yearly rain where a class spans two. */
    static Zone koppenZone(int kg, double t, double elev, double absLat, double precipMm) {
        return switch (kg) {
            case ClimateMap.AF, ClimateMap.AM -> Zone.TROPICAL_HUMID;
            case ClimateMap.AW, ClimateMap.BSH -> Zone.SAVANNA;
            case ClimateMap.BWH, ClimateMap.BWK -> Zone.ARID;
            // Cold steppe: grassland, desert where it is very dry, savanna-like where it is warm.
            case ClimateMap.BSK -> !Double.isNaN(precipMm) && precipMm < 250 ? Zone.ARID : t >= 15 ? Zone.SAVANNA : Zone.TEMPERATE;
            case ClimateMap.CSA, ClimateMap.CWA, ClimateMap.CFA -> Zone.SUBTROPICAL;
            case ClimateMap.CSB, ClimateMap.CSC, ClimateMap.CWB, ClimateMap.CWC, ClimateMap.CFB -> Zone.TEMPERATE;
            case ClimateMap.CFC -> Zone.TAIGA;
            case ClimateMap.DSA, ClimateMap.DWA, ClimateMap.DFA -> Zone.TEMPERATE;
            // Warm-summer continental: mixed forest, the boreal kind where the year is cold.
            case ClimateMap.DSB, ClimateMap.DWB, ClimateMap.DFB -> t < 4 ? Zone.TAIGA : Zone.TEMPERATE;
            case ClimateMap.DSC, ClimateMap.DSD, ClimateMap.DWC, ClimateMap.DWD, ClimateMap.DFC, ClimateMap.DFD -> Zone.TAIGA;
            case ClimateMap.ET -> elev > 1500 && absLat < 60 ? Zone.ALPINE : Zone.TUNDRA;
            case ClimateMap.EF -> Zone.ICE_CAP;
            default -> Zone.TEMPERATE;
        };
    }

    /** The zone a temperature alone gives (as worlds without real climate have it), null for the warm zones it cannot tell apart. */
    private static Zone temperatureZone(double t, double elev) {
        if (t < -6 || elev > 4800) return Zone.ICE_CAP;
        if (elev > 2600 && t < 8) return Zone.ALPINE;
        if (t < 1.0) return Zone.TUNDRA;
        if (t < 7.5) return Zone.TAIGA;
        if (t < 16) return Zone.TEMPERATE;
        return null;
    }

    /** Cold to warm: ice, tundra and alpine, taiga, temperate, the warm zones. */
    private static int rank(Zone z) {
        return switch (z) {
            case ICE_CAP -> 0;
            case TUNDRA, ALPINE -> 1;
            case TAIGA -> 2;
            case TEMPERATE -> 3;
            default -> 4;
        };
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
