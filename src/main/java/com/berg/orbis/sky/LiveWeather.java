package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The weather now, as measured where it can be: rain radar (MET Norway's nowcast in Norway, Sweden, Finland and Denmark;
 * DWD in Germany; Environment Canada in Canada: the precipitation rate of the last few minutes, and DWD's lightning),
 * the nearest airport's weather report (METAR from NOAA's Aviation Weather Center, worldwide: rain, snow, drizzle,
 * thunder), and MET Norway's forecast for the coming hour where neither answers. Radar decides whether it rains, the
 * airport whether it thunders; the forecast fills what they leave open.
 */
public final class LiveWeather {
    private static final String NOWCAST = "https://api.met.no/weatherapi/nowcast/2.0/complete?lat=%.4f&lon=%.4f";
    private static final String DWD = "https://maps.dwd.de/geoserver/dwd/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetFeatureInfo&LAYERS=%s&QUERY_LAYERS=%s"
            + "&SRS=EPSG:4326&BBOX=%.4f,%.4f,%.4f,%.4f&WIDTH=3&HEIGHT=3&X=1&Y=1&INFO_FORMAT=application/json";
    private static final String ECCC = "https://geo.weather.gc.ca/geomet?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS=%s&QUERY_LAYERS=%s"
            + "&CRS=EPSG:4326&BBOX=%.4f,%.4f,%.4f,%.4f&WIDTH=3&HEIGHT=3&I=1&J=1&INFO_FORMAT=application/json";
    private static final String METAR = "https://aviationweather.gov/api/data/metar?bbox=%.3f,%.3f,%.3f,%.3f&format=json";
    private static final String USER_AGENT = "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)";
    /** Precipitation below this rate (mm/h) is dry: radar's faint echoes. */
    private static final double WET_MM_H = 0.1;
    /** The nearest airport counts within this distance and age. */
    private static final double STATION_KM = 30;
    private static final long STATION_AGE_MS = 75 * 60_000L;
    /** How often each source is asked at most. */
    private static final long RADAR_EVERY_MS = 5 * 60_000L, METAR_EVERY_MS = 10 * 60_000L;

    private final MetWeather forecast = new MetWeather();
    private final Map<String, Object[]> cache = new HashMap<>();
    /** Nowcast cells it does not cover (it answers 422 outside the Nordic countries), not asked again. */
    private final java.util.Set<String> noNowcast = new java.util.HashSet<>();

    /** What radar says about a place now: the rate in mm/h and lightning, either NaN / false when it does not know. */
    private record Radar(double rateMmH, boolean lightning, String source) {
        static final Radar NONE = new Radar(Double.NaN, false, null);
    }

    /** The nearest airport's report: precipitation, thunder (at or near it), temperature and the report itself. */
    private record Station(String id, boolean precipitation, boolean thunder, double temperatureC, String weather) {
    }

    /** The weather now at a place (MetWeather's report, its symbol naming the sources). Throws when no source answers. */
    public synchronized MetWeather.Report now(double lat, double lon) throws Exception {
        MetWeather.Report fc = null;
        Exception fcError = null;
        try {
            fc = forecast.now(lat, lon);
        } catch (Exception e) {
            fcError = e;
        }
        Radar radar = radar(lat, lon);
        Station station = station(lat, lon);
        if (fc == null && station == null && Double.isNaN(radar.rateMmH)) throw fcError != null ? fcError : new IllegalStateException("no weather source answered");

        boolean wet;
        StringBuilder why = new StringBuilder();
        if (!Double.isNaN(radar.rateMmH)) {
            wet = radar.rateMmH >= WET_MM_H;
            why.append(String.format(Locale.ROOT, "%s %.1f mm/h", radar.source, radar.rateMmH));
        } else if (station != null) {
            wet = station.precipitation;
            why.append(station.id).append(' ').append(station.weather.isEmpty() ? "dry" : station.weather);
        } else {
            wet = fc.sky() != MetWeather.Sky.CLEAR;
            why.append("forecast ").append(fc.symbol());
        }
        boolean thunder;
        if (radar.lightning) {
            thunder = true;
            why.append(", lightning");
        } else if (station != null) {
            thunder = station.thunder;
            if (thunder) why.append(", thunder at ").append(station.id);
        } else {
            thunder = fc != null && fc.sky() == MetWeather.Sky.THUNDER;
        }
        if (station != null && !Double.isNaN(radar.rateMmH) && station.precipitation != wet) why.append(" (").append(station.id).append(' ').append(station.weather).append(')');
        MetWeather.Sky sky = thunder ? MetWeather.Sky.THUNDER : wet ? MetWeather.Sky.RAIN : MetWeather.Sky.CLEAR;
        double temp = station != null && !Double.isNaN(station.temperatureC) ? station.temperatureC : fc != null ? fc.temperatureC() : Double.NaN;
        return new MetWeather.Report(sky, why.toString(), temp);
    }

    // ------------------------------------------------------------------ radar

    private Radar radar(double lat, double lon) {
        String cell = String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
        Object[] hit = cache.get("radar " + cell);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < RADAR_EVERY_MS) return (Radar) hit[1];
        Radar r = Radar.NONE;
        try {
            if (lat > 54 && lat < 72 && lon > 4 && lon < 32 && !noNowcast.contains(cell)) r = nowcast(lat, lon, cell);
            if (Double.isNaN(r.rateMmH) && lat > 47 && lat < 55.1 && lon > 5.8 && lon < 15.1) r = dwd(lat, lon);
            if (Double.isNaN(r.rateMmH) && lat > 41.5 && lat < 70 && lon > -141 && lon < -52) r = eccc(lat, lon);
        } catch (Exception e) {
            r = Radar.NONE;
        }
        if (cache.size() > 400) cache.clear(); // players moving about: old places are not asked again
        cache.put("radar " + cell, new Object[]{System.currentTimeMillis(), r});
        return r;
    }

    private Radar nowcast(double lat, double lon, String cell) throws Exception {
        OrbisHttp.Response resp = OrbisHttp.get(String.format(Locale.ROOT, NOWCAST, lat, lon), Map.of("User-Agent", USER_AGENT), 30);
        if (resp.status() == 422) {
            noNowcast.add(cell);
            return Radar.NONE;
        }
        if (resp.status() != 200) return Radar.NONE;
        JsonObject props = JsonParser.parseString(resp.text()).getAsJsonObject().getAsJsonObject("properties");
        JsonObject meta = props.getAsJsonObject("meta");
        if (meta == null || !meta.has("radar_coverage") || !"ok".equals(meta.get("radar_coverage").getAsString())) return Radar.NONE;
        JsonObject data = props.getAsJsonArray("timeseries").get(0).getAsJsonObject().getAsJsonObject("data");
        double rate = data.getAsJsonObject("instant").getAsJsonObject("details").get("precipitation_rate").getAsDouble();
        boolean thunder = false;
        if (data.has("next_1_hours")) {
            String symbol = data.getAsJsonObject("next_1_hours").getAsJsonObject("summary").get("symbol_code").getAsString();
            thunder = symbol.contains("thunder");
        }
        return new Radar(rate, thunder, "MET radar");
    }

    private Radar dwd(double lat, double lon) throws Exception {
        double rate = featureValue(String.format(Locale.ROOT, DWD, "dwd:Niederschlagsradar", "dwd:Niederschlagsradar", lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01), "RV_ANALYSIS");
        if (Double.isNaN(rate)) return Radar.NONE;
        double flashes = featureValue(String.format(Locale.ROOT, DWD, "dwd:Blitzdichte", "dwd:Blitzdichte", lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01), "GRAY_INDEX");
        return new Radar(Math.max(0, rate), !Double.isNaN(flashes) && flashes > 0, "DWD radar");
    }

    private Radar eccc(double lat, double lon) throws Exception {
        double rain = featureValue(String.format(Locale.ROOT, ECCC, "RADAR_1KM_RRAI", "RADAR_1KM_RRAI", lat - 0.01, lon - 0.01, lat + 0.01, lon + 0.01), "value");
        if (Double.isNaN(rain)) return Radar.NONE;
        double snow = featureValue(String.format(Locale.ROOT, ECCC, "RADAR_1KM_RSNO", "RADAR_1KM_RSNO", lat - 0.01, lon - 0.01, lat + 0.01, lon + 0.01), "value");
        return new Radar(Math.max(rain, Double.isNaN(snow) ? 0 : snow), false, "Canadian radar");
    }

    /** A numeric property of the first feature of a GetFeatureInfo answer, NaN when there is none (off the radar, no data). */
    private static double featureValue(String url, String property) throws Exception {
        OrbisHttp.Response resp = OrbisHttp.get(url, Map.of("User-Agent", USER_AGENT), 30);
        if (resp.status() != 200) return Double.NaN;
        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        JsonArray features = root.getAsJsonArray("features");
        if (features == null || features.isEmpty()) return Double.NaN;
        JsonObject props = features.get(0).getAsJsonObject().getAsJsonObject("properties");
        if (props == null || !props.has(property) || props.get(property).isJsonNull()) return Double.NaN;
        try {
            return props.get(property).getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN; // "Undetected" and the like
        }
    }

    // ------------------------------------------------------------------ airports

    private Station station(double lat, double lon) {
        String cell = String.format(Locale.ROOT, "%.1f,%.1f", lat, lon);
        Object[] hit = cache.get("metar " + cell);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < METAR_EVERY_MS) return (Station) hit[1];
        Station best = null;
        try {
            double dLat = STATION_KM / 111.0, dLon = dLat / Math.max(0.1, Math.cos(Math.toRadians(lat)));
            OrbisHttp.Response resp = OrbisHttp.get(String.format(Locale.ROOT, METAR, lat - dLat, lon - dLon, lat + dLat, lon + dLon),
                    Map.of("User-Agent", USER_AGENT), 30);
            if (resp.status() == 200 && !resp.text().isBlank()) {
                double bestKm = STATION_KM;
                long now = System.currentTimeMillis();
                for (JsonElement e : JsonParser.parseString(resp.text()).getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    if (!o.has("lat") || !o.has("lon") || !o.has("obsTime")) continue;
                    if (now - o.get("obsTime").getAsLong() * 1000 > STATION_AGE_MS) continue;
                    double km = distanceKm(lat, lon, o.get("lat").getAsDouble(), o.get("lon").getAsDouble());
                    if (km > bestKm) continue;
                    String wx = o.has("wxString") && !o.get("wxString").isJsonNull() ? o.get("wxString").getAsString() : "";
                    double t = o.has("temp") && !o.get("temp").isJsonNull() ? o.get("temp").getAsDouble() : Double.NaN;
                    bestKm = km;
                    best = new Station(o.get("icaoId").getAsString(), precipitation(wx), thunder(wx), t, wx);
                }
            }
        } catch (Exception e) {
            best = null;
        }
        cache.put("metar " + cell, new Object[]{System.currentTimeMillis(), best});
        return best;
    }

    /**
     * Whether a METAR's present weather has precipitation falling at the station: drizzle, rain, snow, snow grains, ice
     * pellets, hail, small hail or unknown precipitation (with any intensity or descriptor), not "VC" (in the vicinity).
     */
    static boolean precipitation(String wx) {
        for (String group : wx.trim().split("\\s+")) {
            if (group.startsWith("VC") || group.isEmpty()) continue;
            String g = group.replaceAll("^[+-]", "");
            for (String p : new String[]{"DZ", "RA", "SN", "SG", "PL", "GR", "GS", "UP", "IC"}) if (g.contains(p)) return true;
        }
        return false;
    }

    /** Whether a METAR's present weather has a thunderstorm at or near the station (TS, VCTS). */
    static boolean thunder(String wx) {
        for (String group : wx.trim().split("\\s+")) if (group.contains("TS")) return true;
        return false;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
