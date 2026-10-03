package com.berg.orbis.net;

import com.berg.orbis.OrbisMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "Eiffel Tower", "Bergen, Norway" or "48.8584, 2.2945" into
 * coordinates. Coordinates are parsed locally; names are tried against, in
 * order, OpenStreetMap's Nominatim, komoot's Photon, and finally the
 * Overpass mirrors the mod already uses for map data (an exact-name place
 * lookup), so a resolver that cannot reach one host does not kill the
 * feature. Each service gets one request per click, with the User-Agent
 * their usage policies ask for.
 */
public final class Geocoder {

    public record Result(double lat, double lon, String name) {}

    private static final Pattern COORDS = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    /** "lat, lon" typed as numbers ([lat, lon]), or null when the text is not coordinates. */
    public static double[] parseCoordinates(String text) {
        Matcher m = COORDS.matcher(text == null ? "" : text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
        return Math.abs(lat) <= 85 && Math.abs(lon) <= 180 ? new double[]{lat, lon} : null;
    }
    private static final String USER_AGENT = "OrbisTerrarum-Minecraft-Mod/1.0 (world location picker)";

    private Geocoder() {}

    public static CompletableFuture<Result> lookup(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("empty"));
        Matcher m = COORDS.matcher(q);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                return CompletableFuture.completedFuture(new Result(lat, lon, String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)));
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            List<String> problems = new ArrayList<>();
            for (Service s : new Service[]{Geocoder::nominatim, Geocoder::photon, Geocoder::overpass}) {
                try {
                    Result r = s.find(q);
                    if (r != null) return r;
                    problems.add("no match");
                } catch (UnknownHostException e) {
                    problems.add("DNS cannot resolve " + e.getMessage());
                } catch (Exception e) {
                    problems.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
            throw new RuntimeException(String.join("; ", problems));
        });
    }

    /**
     * The outline polygon(s) of a named area (country, region, city) from
     * Nominatim, simplified to about 200 m so a country is a few thousand
     * vertices. Fails when the best match is a point rather than an area.
     */
    public static CompletableFuture<AreaOutline> lookupArea(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("empty"));
        String key = OutlineCache.searchKey(q);
        return CompletableFuture.supplyAsync(() -> {
            AreaOutline cached = OutlineCache.get(key);
            if (cached != null) return cached;
            String body;
            try {
                body = get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&polygon_geojson=1&polygon_threshold=0.002&q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8), 60);
            } catch (Exception e) {
                throw new RuntimeException("Nominatim: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            }
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            if (arr.isEmpty()) throw new RuntimeException("no place called '" + q + "'");
            AreaOutline o = outlineOf(arr.get(0).getAsJsonObject(), q);
            OutlineCache.put(key, o);
            return o;
        });
    }

    /**
     * The outline of the area a coordinate lies in, from Nominatim's reverse lookup: at {@code zoom} 10 the
     * municipality or city ("Bergen, Vestland, Norway"), at 8 the county, at 12 the district. Fails at sea or
     * where the match has no outline.
     */
    public static CompletableFuture<AreaOutline> areaAt(double lat, double lon, int zoom) {
        String key = OutlineCache.reverseKey(lat, lon, zoom);
        return CompletableFuture.supplyAsync(() -> {
            AreaOutline cached = OutlineCache.get(key);
            if (cached != null) return cached;
            String body;
            try {
                body = get(String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=%d&polygon_geojson=1&polygon_threshold=0.002&lat=%.6f&lon=%.6f",
                        zoom, lat, lon), 60);
            } catch (Exception e) {
                throw new RuntimeException("Nominatim: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            }
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) throw new RuntimeException(o.get("error").getAsString());
            AreaOutline outline = outlineOf(o, String.format(Locale.ROOT, "%.5f, %.5f", lat, lon));
            OutlineCache.put(key, outline);
            return outline;
        });
    }

    /** A Nominatim result with polygon_geojson as an AreaOutline. */
    private static AreaOutline outlineOf(JsonObject first, String q) {
        String name = first.has("display_name") ? first.get("display_name").getAsString() : q;
        JsonObject geo = first.has("geojson") && first.get("geojson").isJsonObject() ? first.getAsJsonObject("geojson") : null;
        if (geo == null) throw new RuntimeException(name + " has no outline");
        String type = geo.has("type") ? geo.get("type").getAsString() : "";
        JsonArray coords = geo.getAsJsonArray("coordinates");
        List<double[][]> polys = new ArrayList<>();
        switch (type) {
            case "Polygon" -> polys.add(ring(coords.get(0).getAsJsonArray()));
            case "MultiPolygon" -> {
                for (JsonElement poly : coords) polys.add(ring(poly.getAsJsonArray().get(0).getAsJsonArray()));
            }
            default -> throw new RuntimeException(name + " is a " + type.toLowerCase(Locale.ROOT) + ", not an area -- name a country, region, county or city");
        }
        polys.removeIf(p -> p.length < 3);
        if (polys.isEmpty()) throw new RuntimeException(name + " has an empty outline");
        return AreaOutline.of(name, polys);
    }

    /** GeoJSON ring ([lon, lat] pairs) to [lat, lon] vertices without the closing duplicate. */
    private static double[][] ring(JsonArray ring) {
        int n = ring.size();
        if (n > 1) {
            JsonArray a = ring.get(0).getAsJsonArray(), b = ring.get(n - 1).getAsJsonArray();
            if (a.get(0).getAsDouble() == b.get(0).getAsDouble() && a.get(1).getAsDouble() == b.get(1).getAsDouble()) n--;
        }
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            JsonArray p = ring.get(i).getAsJsonArray();
            out[i] = new double[]{p.get(1).getAsDouble(), p.get(0).getAsDouble()};
        }
        return out;
    }

    /** Human-readable place description for a coordinate ("Thamel, Kathmandu, Nepal"); fails when no service answers. */
    public static CompletableFuture<String> reverse(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> problems = new ArrayList<>();
            try {
                String body = get(String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&lat=%.6f&lon=%.6f", lat, lon), 12);
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                if (o.has("display_name")) return o.get("display_name").getAsString();
                problems.add("nominatim: no name");
            } catch (Exception e) {
                problems.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            try {
                String body = get(String.format(Locale.ROOT, "https://photon.komoot.io/reverse?lang=en&lat=%.6f&lon=%.6f", lat, lon), 12);
                JsonArray features = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("features");
                if (features != null && !features.isEmpty()) {
                    JsonObject props = features.get(0).getAsJsonObject().getAsJsonObject("properties");
                    StringBuilder name = new StringBuilder();
                    for (String k : new String[]{"name", "street", "district", "city", "state", "country"}) {
                        if (props.has(k)) {
                            String v = props.get(k).getAsString();
                            if (name.indexOf(v) < 0) {
                                if (name.length() > 0) name.append(", ");
                                name.append(v);
                            }
                        }
                    }
                    if (name.length() > 0) return name.toString();
                }
                problems.add("photon: no name");
            } catch (Exception e) {
                problems.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            throw new RuntimeException(String.join("; ", problems));
        });
    }

    @FunctionalInterface
    private interface Service {
        Result find(String query) throws Exception;
    }

    private static String get(String url, int timeoutSeconds) throws Exception {
        OrbisHttp.Response resp = OrbisHttp.get(url, java.util.Map.of("User-Agent", USER_AGENT, "Accept-Language", "en"), timeoutSeconds);
        if (resp.status() != 200) throw new IllegalStateException("HTTP " + resp.status());
        return resp.text();
    }

    // ---- 1. Nominatim -------------------------------------------------------

    private static Result nominatim(String q) throws Exception {
        String body = get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), 15);
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        if (arr.isEmpty()) return null;
        JsonObject first = arr.get(0).getAsJsonObject();
        return new Result(first.get("lat").getAsDouble(), first.get("lon").getAsDouble(),
                first.has("display_name") ? first.get("display_name").getAsString() : q);
    }

    // ---- 2. Photon (komoot) ---------------------------------------------------

    private static Result photon(String q) throws Exception {
        String body = get("https://photon.komoot.io/api/?limit=1&lang=en&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), 15);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray features = root.getAsJsonArray("features");
        if (features == null || features.isEmpty()) return null;
        JsonObject f = features.get(0).getAsJsonObject();
        JsonArray coords = f.getAsJsonObject("geometry").getAsJsonArray("coordinates");
        JsonObject props = f.getAsJsonObject("properties");
        StringBuilder name = new StringBuilder();
        for (String k : new String[]{"name", "city", "state", "country"}) {
            if (props.has(k)) {
                if (name.length() > 0) name.append(", ");
                name.append(props.get(k).getAsString());
            }
        }
        return new Result(coords.get(1).getAsDouble(), coords.get(0).getAsDouble(), name.length() == 0 ? q : name.toString());
    }

    // ---- 3. Overpass exact place-name lookup ----------------------------------

    private static final List<String> PLACE_RANK = List.of("city", "town", "village", "hamlet", "suburb", "island", "state", "country", "locality");

    private static Result overpass(String q) throws Exception {
        // Exact tag values are indexed on Overpass servers; regexes over the whole planet are not.
        String name = q.split(",")[0].trim();
        String title = titleCase(name);
        String query = "[out:json][timeout:25];("
                + "nwr[\"place\"][\"name\"=\"" + esc(name) + "\"];"
                + (title.equals(name) ? "" : "nwr[\"place\"][\"name\"=\"" + esc(title) + "\"];")
                + "nwr[\"place\"][\"name:en\"=\"" + esc(title) + "\"];"
                + ");out center 30;";
        List<String> endpoints = OrbisMod.config() != null ? OrbisMod.config().overpassUrls : List.of("https://overpass.kumi.systems/api/interpreter");
        Exception last = null;
        for (String endpoint : endpoints) {
            try {
                OrbisHttp.Response resp = OrbisHttp.post(endpoint, java.util.Map.of("User-Agent", USER_AGENT),
                        "application/x-www-form-urlencoded",
                        ("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), 40);
                if (resp.status() != 200) throw new IllegalStateException("Overpass HTTP " + resp.status());
                JsonArray elements = JsonParser.parseString(resp.text()).getAsJsonObject().getAsJsonArray("elements");
                Result best = null;
                int bestRank = Integer.MAX_VALUE;
                for (JsonElement el : elements) {
                    JsonObject o = el.getAsJsonObject();
                    JsonObject tags = o.has("tags") ? o.getAsJsonObject("tags") : null;
                    if (tags == null) continue;
                    String place = tags.has("place") ? tags.get("place").getAsString() : "";
                    int rank = PLACE_RANK.indexOf(place);
                    if (rank < 0) rank = PLACE_RANK.size();
                    double lat, lon;
                    if (o.has("lat")) {
                        lat = o.get("lat").getAsDouble();
                        lon = o.get("lon").getAsDouble();
                    } else if (o.has("center")) {
                        lat = o.getAsJsonObject("center").get("lat").getAsDouble();
                        lon = o.getAsJsonObject("center").get("lon").getAsDouble();
                    } else {
                        continue;
                    }
                    if (rank < bestRank) {
                        bestRank = rank;
                        String label = (tags.has("name:en") ? tags.get("name:en").getAsString() : tags.get("name").getAsString())
                                + " (" + place + (tags.has("is_in") ? ", " + tags.get("is_in").getAsString() : "") + ")";
                        best = new Result(lat, lon, label);
                    }
                }
                return best;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("no Overpass endpoint");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-';
        }
        return sb.toString();
    }
}
