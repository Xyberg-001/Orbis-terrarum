package com.berg.orbis.osm;

import com.berg.orbis.net.OrbisHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tunnels a national road database surveyed in 3D, outside Norway (whose NVDB tunnel bores {@link NvdbRoads.Store#tunnels} has):
 * France's BD TOPO road sections under ground (position_par_rapport_au_sol -1; those whose heights IGN does not know, -1000, are
 * left out: the Mont Blanc tunnel has none) and Finland's Digiroad links marked as tunnels (silta_alik -11). Each road line with a
 * height at every point, as lat, lon, metres. Both services answer with whole features, so every region a tunnel crosses gets the
 * same lines. Kept per region box; a failure is not kept (asked again after 10 minutes).
 */
public final class TunnelSurveys {
    private static final String BDTOPO = "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
            + "&TYPENAMES=BDTOPO_V3:troncon_de_route&OUTPUTFORMAT=application/json&SRSNAME=EPSG:4326&PROPERTYNAME=geometrie";
    private static final String DIGIROAD = "https://avoinapi.vaylapilvi.fi/vaylatiedot/ogc/features/v1/collections/"
            + "digiroad:dr_tielinkki_silta_alikulku_tunneli/items?f=json";
    private static final int PAGE = 1000;
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum (Minecraft mod; tunnel heights)");

    private final Path cacheDir;
    private volatile long failedUntil;

    public TunnelSurveys(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** The surveyed tunnel lines touching the box (empty where no source covers it), or null when a source failed. */
    public List<NvdbRoads.Bore> get(double south, double west, double north, double east) {
        boolean fr = com.berg.orbis.config.DataSources.covers("fr-bdtopo-roads", south, west, north, east);
        boolean fi = com.berg.orbis.config.DataSources.covers("fi-digiroad-width", south, west, north, east);
        if (!fr && !fi) return List.of();
        List<NvdbRoads.Bore> out = new ArrayList<>();
        if (fr) {
            List<NvdbRoads.Bore> got = cached("fr", south, west, north, east);
            if (got == null) return null;
            out.addAll(got);
        }
        if (fi) {
            List<NvdbRoads.Bore> got = cached("fi", south, west, north, east);
            if (got == null) return null;
            out.addAll(got);
        }
        return out;
    }

    private List<NvdbRoads.Bore> cached(String source, double south, double west, double north, double east) {
        Path file = cacheDir.resolve(String.format(Locale.ROOT, "%s_%.5f_%.5f_%.5f_%.5f.json.gz", source, south, west, north, east));
        try {
            if (Files.exists(file)) return parse(readGzip(file));
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Tunnel survey cache " + file.getFileName() + " unreadable, fetching again: " + e);
        }
        if (System.currentTimeMillis() < failedUntil) return null;
        try {
            List<NvdbRoads.Bore> got = source.equals("fr") ? fetchBdTopo(south, west, north, east) : fetchDigiroad(south, west, north, east);
            Files.createDirectories(cacheDir);
            writeGzip(file, serialize(got));
            return got;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failedUntil = System.currentTimeMillis() + 10 * 60_000L;
            System.err.println("[orbis] Tunnel heights from " + (source.equals("fr") ? "BD TOPO" : "Digiroad") + " unavailable (" + e.getMessage()
                    + "); tunnels run straight between their portals for 10 minutes");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<NvdbRoads.Bore> fetchBdTopo(double s, double w, double n, double e) throws IOException, InterruptedException {
        String filter = String.format(Locale.ROOT, "position_par_rapport_au_sol='-1' AND BBOX(geometrie,%.6f,%.6f,%.6f,%.6f,'urn:ogc:def:crs:EPSG::4326')", s, w, n, e);
        String base = BDTOPO + "&CQL_FILTER=" + java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8) + "&COUNT=" + PAGE;
        List<NvdbRoads.Bore> out = new ArrayList<>();
        for (int page = 0; page < 20; page++) {
            JsonArray features = features(base + "&STARTINDEX=" + page * PAGE);
            for (JsonElement f : features) {
                for (List<double[]> line : lines(f.getAsJsonObject())) {
                    // IGN's -1000: the height is not known (whole tunnels, the Mont Blanc's among them)
                    if (line.stream().allMatch(p -> p[2] > -500)) out.add(new NvdbRoads.Bore(line));
                }
            }
            if (features.size() < PAGE) break;
        }
        return out;
    }

    private static List<NvdbRoads.Bore> fetchDigiroad(double s, double w, double n, double e) throws IOException, InterruptedException {
        String base = DIGIROAD + String.format(Locale.ROOT, "&bbox=%.6f,%.6f,%.6f,%.6f&limit=%d", w, s, e, n, PAGE);
        List<NvdbRoads.Bore> out = new ArrayList<>();
        for (int page = 0; page < 20; page++) {
            JsonArray features = features(base + "&startIndex=" + page * PAGE);
            for (JsonElement f : features) {
                JsonObject o = f.getAsJsonObject();
                JsonObject props = o.has("properties") && o.get("properties").isJsonObject() ? o.getAsJsonObject("properties") : null;
                if (props == null || !props.has("silta_alik") || props.get("silta_alik").isJsonNull()) continue;
                if (props.get("silta_alik").getAsInt() != -11) continue; // -11 a tunnel (1 a bridge, -1 an underpass)
                for (List<double[]> line : lines(o)) out.add(new NvdbRoads.Bore(line));
            }
            if (features.size() < PAGE) break;
        }
        return out;
    }

    private static JsonArray features(String url) throws IOException, InterruptedException {
        OrbisHttp.Response r = OrbisHttp.get(url, HEADERS, 60);
        if (r.status() != 200) throw new IOException("HTTP " + r.status());
        JsonObject root = JsonParser.parseString(r.text()).getAsJsonObject();
        if (!root.has("features") || !root.get("features").isJsonArray()) throw new IOException("no features in the answer");
        return root.getAsJsonArray("features");
    }

    /** A GeoJSON feature's lines with a height at every point, as lat, lon, metres (GeoJSON is lon, lat, z). */
    private static List<List<double[]>> lines(JsonObject feature) {
        List<List<double[]>> out = new ArrayList<>();
        if (!feature.has("geometry") || !feature.get("geometry").isJsonObject()) return out;
        JsonObject g = feature.getAsJsonObject("geometry");
        String type = g.has("type") ? g.get("type").getAsString() : "";
        JsonArray c = g.getAsJsonArray("coordinates");
        if (c == null) return out;
        if (type.equals("LineString")) {
            List<double[]> line = line(c);
            if (line != null) out.add(line);
        } else if (type.equals("MultiLineString")) {
            for (JsonElement part : c) {
                List<double[]> line = line(part.getAsJsonArray());
                if (line != null) out.add(line);
            }
        }
        return out;
    }

    private static List<double[]> line(JsonArray pts) {
        List<double[]> line = new ArrayList<>();
        for (JsonElement pe : pts) {
            JsonArray p = pe.getAsJsonArray();
            if (p.size() < 3) return null; // no height: of no use here
            line.add(new double[]{p.get(1).getAsDouble(), p.get(0).getAsDouble(), p.get(2).getAsDouble()});
        }
        return line.size() >= 2 ? line : null;
    }

    private static String serialize(List<NvdbRoads.Bore> bores) {
        JsonArray arr = new JsonArray();
        for (NvdbRoads.Bore b : bores) {
            JsonArray pts = new JsonArray();
            for (double[] p : b.latLonZ()) {
                JsonArray q = new JsonArray();
                q.add(Math.round(p[0] * 1e7) / 1e7);
                q.add(Math.round(p[1] * 1e7) / 1e7);
                q.add(Math.round(p[2] * 100) / 100.0);
                pts.add(q);
            }
            arr.add(pts);
        }
        return arr.toString();
    }

    private static List<NvdbRoads.Bore> parse(String json) {
        List<NvdbRoads.Bore> out = new ArrayList<>();
        for (JsonElement b : JsonParser.parseString(json).getAsJsonArray()) {
            List<double[]> pts = new ArrayList<>();
            for (JsonElement p : b.getAsJsonArray()) {
                JsonArray q = p.getAsJsonArray();
                pts.add(new double[]{q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble()});
            }
            if (pts.size() >= 2) out.add(new NvdbRoads.Bore(pts));
        }
        return out;
    }

    private static String readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeGzip(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
