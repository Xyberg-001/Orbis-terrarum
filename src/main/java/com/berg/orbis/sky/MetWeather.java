package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The weather now at a place, from the Norwegian Meteorological Institute's forecast service (api.met.no
 * locationforecast, worldwide, free under CC BY 4.0). Its terms are kept: an identifying User-Agent, coordinates
 * to at most four decimals, and no new request before the answer's Expires time (If-Modified-Since after that).
 */
public final class MetWeather {

    public enum Sky { CLEAR, RAIN, THUNDER }

    /** The weather in the coming hour: what Minecraft can show of it, the forecast's symbol and the temperature. */
    public record Report(Sky sky, String symbol, double temperatureC) {}

    private static final String URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%s&lon=%s";
    private static final String USER_AGENT = "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)";

    private String lastUrl;
    private String lastModified;
    private long expiresMillis;
    private Report last;

    /** The current report for a place, from the cache while the last answer has not expired. Throws on failure. */
    public synchronized Report now(double lat, double lon) throws Exception {
        String url = String.format(Locale.ROOT, URL, round4(lat), round4(lon));
        long nowMs = System.currentTimeMillis();
        if (url.equals(lastUrl) && last != null && nowMs < expiresMillis) return last;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (url.equals(lastUrl) && lastModified != null) headers.put("If-Modified-Since", lastModified);
        OrbisHttp.Response r = OrbisHttp.get(url, headers, 30);
        if (r.status() == 304 && last != null) {
            expiresMillis = expiry(r, nowMs);
            return last;
        }
        if (r.status() != 200) throw new IllegalStateException("HTTP " + r.status());
        last = parse(r.text());
        lastUrl = url;
        lastModified = r.header("Last-Modified");
        expiresMillis = expiry(r, nowMs);
        return last;
    }

    private static long expiry(OrbisHttp.Response r, long nowMs) {
        String e = r.header("Expires");
        long at = nowMs + 10 * 60_000L;
        if (e != null) {
            try {
                at = ZonedDateTime.parse(e, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
            }
        }
        return Math.max(at, nowMs + 5 * 60_000L); // never more often than every 5 minutes
    }

    static Report parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray series = root.getAsJsonObject("properties").getAsJsonArray("timeseries");
        // The first entry is the current hour (the service drops past ones).
        JsonObject data = series.get(0).getAsJsonObject().getAsJsonObject("data");
        double temp = data.getAsJsonObject("instant").getAsJsonObject("details").get("air_temperature").getAsDouble();
        String symbol = "cloudy";
        for (String next : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
            if (data.has(next) && data.getAsJsonObject(next).has("summary")) {
                symbol = data.getAsJsonObject(next).getAsJsonObject("summary").get("symbol_code").getAsString();
                break;
            }
        }
        return new Report(skyOf(symbol), symbol, temp);
    }

    /** Minecraft's three weathers for met.no's symbols (rain, sleet, snow and showers are all precipitation). */
    static Sky skyOf(String symbol) {
        String s = symbol.toLowerCase(Locale.ROOT);
        if (s.contains("thunder")) return Sky.THUNDER;
        if (s.contains("rain") || s.contains("sleet") || s.contains("snow") || s.contains("drizzle")) return Sky.RAIN;
        return Sky.CLEAR;
    }

    private static String round4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
