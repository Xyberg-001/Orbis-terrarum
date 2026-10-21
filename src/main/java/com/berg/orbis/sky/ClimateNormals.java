package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A place's climate month by month: the mean temperature and the precipitation of each month, from TerraClimate's
 * 1991-2020 normals (University of Idaho, 4 km, CC0: three OPeNDAP point reads of tmax, tmin and precipitation, some
 * ten seconds each; brought from its cell's mean height to the place's own at 6.5 C a kilometre, as a 4 km cell in
 * the Alps holds peaks and valleys alike: Zermatt's cell is at 2,054 m, the village at 1,608) or, where TerraClimate has no cell (at sea, or its server down), over the five years 2021-2025
 * from Open-Meteo's weather archive (ERA5 reanalysis brought down to the place's height, CC BY 4.0; about 70 kB,
 * counted as some 130 of the 10,000 calls a day Open-Meteo allows). One question per place, asked in the background and kept in {@code climate-normals/} of the data folder
 * by tenths of a degree, so the seasons can be worked out before a world opens without waiting on the network.
 */
public final class ClimateNormals {
    private static final String URL = "https://archive-api.open-meteo.com/v1/archive?latitude=%.4f&longitude=%.4f"
            + "&start_date=2021-01-01&end_date=2025-12-31&daily=temperature_2m_mean,precipitation_sum&timezone=GMT";
    private static final String TERRA = "http://thredds.northwestknowledge.net:8080/thredds/dodsC/TERRACLIMATE_ALL/climatology/"
            + "TerraClimate_19912020_%s.nc.ascii?%s%%5B0:1:11%%5D%%5B%d%%5D%%5B%d%%5D";
    private static final String TERRA_HEIGHT = "http://thredds.northwestknowledge.net:8080/thredds/dodsC/TERRACLIMATE_ALL/layers/terraclim_dem.nc.ascii?elevation%%5B%d%%5D%%5B%d%%5D";
    private static final String PLACE_HEIGHT = "https://api.open-meteo.com/v1/elevation?latitude=%.4f&longitude=%.4f";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    /** Places being asked for now, so one is asked once at a time. */
    private static final Set<String> ASKING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 30 * 60_000L;

    /** Mean temperature (C) and precipitation (mm) of each month, January first. */
    public record Months(double[] temperatureC, double[] precipitationMm) {
    }

    private ClimateNormals() {
    }

    /** The place's months as kept on disk, or null when they have not been asked for yet (this never waits on the network). */
    public static Months cached(Path dataDir, double lat, double lon) {
        if (dataDir == null || Double.isNaN(lat) || Double.isNaN(lon)) return null;
        try {
            Path f = file(dataDir, lat, lon);
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            double[] t = numbers(o.getAsJsonArray("temperatureC")), p = numbers(o.getAsJsonArray("precipitationMm"));
            return t.length == 12 && p.length == 12 ? new Months(t, p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Asks for the place's months in the background unless they are kept already (or the last try failed lately). */
    public static void prepare(Path dataDir, double lat, double lon) {
        if (dataDir == null || Double.isNaN(lat) || Double.isNaN(lon)) return;
        Path f = file(dataDir, lat, lon);
        String key = f.getFileName().toString();
        Long failed = FAILED.get(key);
        if (Files.exists(f) || (failed != null && System.currentTimeMillis() - failed < RETRY_MS) || !ASKING.add(key)) return;
        Thread t = new Thread(() -> {
            try {
                double la = Math.round(lat * 10) / 10.0, lo = Math.round(lon * 10) / 10.0;
                Months m = null;
                String source = "TerraClimate 1991-2020 normals";
                try {
                    m = terraClimate(la, lo);
                } catch (Exception e) {
                    System.err.println("[orbis] TerraClimate normals not had (" + e.getMessage() + "): Open-Meteo's archive instead");
                }
                if (m == null) {
                    m = fetch(la, lo);
                    source = "Open-Meteo archive (ERA5), 2021-2025";
                }
                JsonObject o = new JsonObject();
                o.add("temperatureC", array(m.temperatureC()));
                o.add("precipitationMm", array(m.precipitationMm()));
                o.addProperty("source", source);
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(key + ".part");
                Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                System.out.println("[orbis] Climate by month for " + key.replace(".json", "") + " kept for the seasons");
            } catch (Exception e) {
                FAILED.put(key, System.currentTimeMillis());
                System.err.println("[orbis] Climate by month not had (" + e + "); the seasons follow the latitude");
            } finally {
                ASKING.remove(key);
            }
        }, "Orbis climate normals");
        t.setDaemon(true);
        t.start();
    }

    /** TerraClimate's 1991-2020 months at the place's 4 km cell (the mean of the mean highs and lows); null at sea. */
    static Months terraClimate(double lat, double lon) throws Exception {
        int row = (int) Math.round((89.97916666666667 - lat) * 24), col = (int) Math.round((lon + 179.97916666666666) * 24);
        row = Math.max(0, Math.min(4319, row));
        col = Math.floorMod(col, 8640);
        double[] hi = terraVariable("tmax", row, col), lo = terraVariable("tmin", row, col), p = terraVariable("ppt", row, col);
        if (hi == null || lo == null || p == null) return null;
        double shift = heightShiftC(lat, lon, row, col);
        double[] t = new double[12], pr = new double[12];
        for (int m = 0; m < 12; m++) {
            t[m] = Math.round(((hi[m] + lo[m]) / 2 + shift) * 10) / 10.0;
            pr[m] = Math.round(p[m]);
        }
        return new Months(t, pr);
    }

    /**
     * How much warmer the place is than its TerraClimate cell's mean height (6.5 C a kilometre lower); 0 when either
     * height cannot be had (the place's from Open-Meteo's 90 m terrain, one light question).
     */
    private static double heightShiftC(double lat, double lon, int row, int col) {
        try {
            OrbisHttp.Response cell = OrbisHttp.get(String.format(Locale.ROOT, TERRA_HEIGHT, row, col), HEADERS, 90);
            OrbisHttp.Response place = OrbisHttp.get(String.format(Locale.ROOT, PLACE_HEIGHT, lat, lon), HEADERS, 30);
            if (cell.status() != 200 || place.status() != 200) return 0;
            double cellM = Double.NaN;
            for (String line : cell.text().split("\n")) {
                line = line.trim();
                if (line.startsWith("[0], ")) cellM = Double.parseDouble(line.substring(5).trim()) * 0.1;
            }
            double placeM = JsonParser.parseString(place.text()).getAsJsonObject().getAsJsonArray("elevation").get(0).getAsDouble();
            if (Double.isNaN(cellM) || cellM < -500 || Double.isNaN(placeM)) return 0;
            return 0.0065 * (cellM - placeM);
        } catch (Exception e) {
            return 0;
        }
    }

    /** One variable's twelve months at a cell, scaled (value x 0.1, temperatures less 73); null where it has none. */
    private static double[] terraVariable(String name, int row, int col) throws Exception {
        OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, TERRA, name, name, row, col), HEADERS, 90);
        if (r.status() != 200) throw new java.io.IOException("TerraClimate answered " + r.status());
        double[] v = new double[12];
        int n = 0;
        // the data lines read "[month][0], value"
        for (String line : r.text().split("\n")) {
            line = line.trim();
            if (!line.startsWith("[") || !line.contains("], ") || n >= 12) continue;
            long raw = Long.parseLong(line.substring(line.lastIndexOf(", ") + 2).trim());
            if (raw == -32768 || raw == Integer.MIN_VALUE) return null;
            v[n++] = raw * 0.1 + (name.equals("ppt") ? 0 : -73.0);
        }
        if (n != 12) throw new java.io.IOException("TerraClimate gave " + n + " months of " + name);
        return v;
    }

    static Months fetch(double lat, double lon) throws Exception {
        OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, URL, lat, lon), HEADERS, 90);
        if (r.status() != 200) throw new java.io.IOException("archive answered " + r.status());
        JsonObject daily = JsonParser.parseString(r.text()).getAsJsonObject().getAsJsonObject("daily");
        JsonArray days = daily.getAsJsonArray("time"), temp = daily.getAsJsonArray("temperature_2m_mean"), rain = daily.getAsJsonArray("precipitation_sum");
        double[] tSum = new double[12], pSum = new double[12];
        int[] tN = new int[12], pN = new int[12];
        for (int i = 0; i < days.size(); i++) {
            int month = Integer.parseInt(days.get(i).getAsString().substring(5, 7)) - 1;
            if (!temp.get(i).isJsonNull()) {
                tSum[month] += temp.get(i).getAsDouble();
                tN[month]++;
            }
            if (!rain.get(i).isJsonNull()) {
                pSum[month] += rain.get(i).getAsDouble();
                pN[month]++;
            }
        }
        double[] t = new double[12], p = new double[12];
        for (int m = 0; m < 12; m++) {
            if (tN[m] == 0 || pN[m] == 0) throw new java.io.IOException("no data for month " + (m + 1));
            t[m] = Math.round(tSum[m] / tN[m] * 10) / 10.0;
            // the month's mean daily precipitation times its length: the mean month's total
            p[m] = Math.round(pSum[m] / pN[m] * java.time.Month.of(m + 1).length(false));
        }
        return new Months(t, p);
    }

    private static Path file(Path dataDir, double lat, double lon) {
        return dataDir.resolve("climate-normals").resolve(String.format(Locale.ROOT, "%.1f_%.1f.json", lat, lon));
    }

    private static double[] numbers(JsonArray a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    private static JsonArray array(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }
}
