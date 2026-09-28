package com.berg.orbis.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Fetches every OSM feature the generator cares about for a bounding box from
 * the Overpass API, and caches the raw JSON (gzipped) on disk so a region is
 * downloaded at most once per server.
 *
 * Public Overpass instances are shared and touchy: overpass-api.de answers
 * HTTP 429 as soon as a second query from the same IP is queued and blocks
 * the IP for minutes after repeated 429s. So: one request in flight at a
 * time by default, a global cooldown honoured by every caller after a 429,
 * and automatic failover across several endpoints.
 *
 * Output is a geometry-typed {@link OsmData}: nodes, lines and areas.
 * Multipolygon relations are stitched into proper outer/inner rings.
 */
public class OsmDataProvider {

    private final Path cacheDir;
    private final List<String> endpoints;
    private final int timeoutSeconds;
    private final int retries;
    /** Global cap on in-flight requests. */
    private final Semaphore slots;
    /** Per-endpoint caps: the mirrors tolerate a few parallel queries, overpass-api.de tolerates one. */
    private final Map<String, Semaphore> endpointSlots = new HashMap<>();
    private final HttpClient client;
    private final AtomicInteger endpointIndex = new AtomicInteger();
    private volatile long cooldownUntil;
    private volatile boolean closed;
    /** Imported country extracts, consulted before any network request. */
    private volatile com.berg.orbis.osm.extract.LocalExtractStore extracts;
    private volatile double metersPerBlock = 1.0;
    private volatile boolean extractAnnounced;

    /** Serve regions from the local extracts that cover them (given this world's scale) instead of Overpass. */
    public void setLocalExtracts(com.berg.orbis.osm.extract.LocalExtractStore store, double metersPerBlock) {
        this.extracts = store;
        this.metersPerBlock = metersPerBlock;
    }

    /** Stops every retry loop at its next step (the model that owned this provider has been replaced). */
    public void close() {
        closed = true;
    }

    public OsmDataProvider(Path cacheDir, List<String> endpoints, int timeoutSeconds, int concurrentRequests, int retries) {
        this.cacheDir = cacheDir;
        this.endpoints = new ArrayList<>(endpoints);
        this.timeoutSeconds = Math.max(20, timeoutSeconds);
        this.retries = Math.max(0, retries);
        this.slots = new Semaphore(Math.max(1, concurrentRequests));
        for (String ep : this.endpoints) {
            int limit = ep.contains("overpass-api.de") ? 1 : ep.contains("kumi") ? 4 : 2;
            endpointSlots.put(ep, new Semaphore(Math.min(limit, Math.max(1, concurrentRequests))));
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create OSM cache dir: " + cacheDir, e);
        }
    }

    private volatile PlacesStore places;

    /** Extra points of interest (Overture Places import) merged into every region's data. */
    public void setPlaces(PlacesStore store) {
        this.places = store;
    }

    /** Returns cached data if present, otherwise downloads it. Never returns null; throws on network failure. */
    public OsmData getData(double south, double west, double north, double east) throws IOException, InterruptedException {
        OsmData data = baseData(south, west, north, east);
        PlacesStore p = places;
        if (p != null && !p.isEmpty()) {
            try {
                data = p.merge(data, south, west, north, east);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Places merge failed: " + e);
            }
        }
        return data;
    }

    private OsmData baseData(double south, double west, double north, double east) throws IOException, InterruptedException {
        com.berg.orbis.osm.extract.LocalExtractStore store = extracts;
        if (store != null) {
            com.berg.orbis.osm.extract.LocalExtractStore.Extract ex = store.covering(south, west, north, east, metersPerBlock);
            if (ex != null) {
                if (!extractAnnounced) {
                    extractAnnounced = true;
                    System.out.println("[orbis] Map data comes from the local extract " + ex.describe());
                }
                return store.load(ex, south, west, north, east);
            }
        }
        Path cached = cacheFile(south, west, north, east);
        String json;
        if (Files.exists(cached)) {
            json = readGzip(cached);
        } else {
            json = fetchWithRetries(south, west, north, east);
            writeGzip(cached, json);
        }
        return parse(json);
    }

    public boolean isCached(double south, double west, double north, double east) {
        com.berg.orbis.osm.extract.LocalExtractStore store = extracts;
        if (store != null && store.covering(south, west, north, east, metersPerBlock) != null) return true;
        return Files.exists(cacheFile(south, west, north, east));
    }

    private Path cacheFile(double south, double west, double north, double east) {
        return cacheDir.resolve(String.format(Locale.ROOT, "%.5f_%.5f_%.5f_%.5f.json.gz", south, west, north, east));
    }

    /** Thrown for HTTP 429 so the retry loop can back off longer and switch endpoint. */
    private static final class RateLimited extends IOException {
        final long retryAfterMs;

        RateLimited(String endpoint, long retryAfterMs) {
            super("rate limited (HTTP 429) by " + endpoint);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private String fetchWithRetries(double south, double west, double north, double east) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (closed) throw new IOException("provider closed (world model replaced)");
            String endpoint = endpoints.get(Math.floorMod(endpointIndex.get(), endpoints.size()));
            try {
                return fetchOverpass(endpoint, south, west, north, east);
            } catch (RateLimited e) {
                last = e;
                long wait = Math.max(20_000L, e.retryAfterMs);
                cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + wait);
                endpointIndex.incrementAndGet();
                System.err.println("[orbis] " + e.getMessage() + " -- switching endpoint, cooling down " + (wait / 1000) + " s");
            } catch (IOException e) {
                last = e;
                endpointIndex.incrementAndGet();
                long backoffMs = 3000L * (attempt + 1);
                System.err.println("[orbis] Overpass fetch failed (attempt " + (attempt + 1) + "/" + (retries + 1) + ") on " + endpoint
                        + ": " + e.getMessage() + " -- trying the next endpoint in " + backoffMs + " ms");
                Thread.sleep(backoffMs);
            }
        }
        throw last;
    }

    private String fetchOverpass(String endpoint, double south, double west, double north, double east) throws IOException, InterruptedException {
        String bbox = String.format(Locale.ROOT, "(%.6f,%.6f,%.6f,%.6f)", south, west, north, east);
        // nwr = node + way + relation. "out geom" inlines way geometry and
        // relation member geometry so no second lookup is needed.
        String query = "[out:json][timeout:" + timeoutSeconds + "];"
                + "("
                + "nwr[\"highway\"]" + bbox + ";"
                + "nwr[\"building\"]" + bbox + ";"
                + "nwr[\"building:part\"]" + bbox + ";"
                + "nwr[\"natural\"]" + bbox + ";"
                + "nwr[\"landuse\"]" + bbox + ";"
                + "nwr[\"leisure\"]" + bbox + ";"
                + "nwr[\"waterway\"]" + bbox + ";"
                + "nwr[\"water\"]" + bbox + ";"
                + "nwr[\"railway\"]" + bbox + ";"
                + "nwr[\"barrier\"]" + bbox + ";"
                + "nwr[\"man_made\"]" + bbox + ";"
                + "nwr[\"power\"]" + bbox + ";"
                + "nwr[\"aeroway\"]" + bbox + ";"
                + "nwr[\"amenity\"]" + bbox + ";"
                + "nwr[\"shop\"]" + bbox + ";"
                + "nwr[\"tourism\"]" + bbox + ";"
                + "nwr[\"office\"]" + bbox + ";"
                + "nwr[\"craft\"]" + bbox + ";"
                + "nwr[\"healthcare\"]" + bbox + ";"
                + "nwr[\"historic\"]" + bbox + ";"
                + "nwr[\"public_transport\"]" + bbox + ";"
                + "nwr[\"place\"=\"square\"]" + bbox + ";"
                + ");"
                + "out geom;";

        Semaphore perEndpoint = endpointSlots.get(endpoint);
        slots.acquire();
        if (perEndpoint != null) perEndpoint.acquire();
        try {
            long wait = cooldownUntil - System.currentTimeMillis();
            if (wait > 0) {
                System.out.println("[orbis] Waiting " + (wait / 1000) + " s for the Overpass rate-limit cooldown...");
                Thread.sleep(wait);
            }
            if (closed) throw new IOException("provider closed (world model replaced)");
            com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.post(endpoint,
                    Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0 (real-world terrain generation)"),
                    "application/x-www-form-urlencoded",
                    ("data=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                    timeoutSeconds + 30);
            if (resp.status() == 429) {
                long retryAfterMs = 0L;
                String retryAfter = resp.header("Retry-After");
                if (retryAfter != null) {
                    try {
                        retryAfterMs = Long.parseLong(retryAfter.trim()) * 1000L;
                    } catch (NumberFormatException ignored) {
                    }
                }
                throw new RateLimited(endpoint, retryAfterMs);
            }
            if (resp.status() != 200) {
                String body = new String(resp.body(), StandardCharsets.UTF_8).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                throw new IOException("HTTP " + resp.status() + ": " + body.substring(0, Math.min(160, body.length())));
            }
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            // Overpass returns HTTP 200 with a "remark" when it ran out of time/memory.
            if (body.length() < 4000 && body.contains("\"remark\"") && body.contains("runtime error")) {
                throw new IOException("Overpass runtime error: " + body.replace('\n', ' '));
            }
            if (!body.contains("\"elements\"")) {
                throw new IOException("Unexpected Overpass response (" + body.length() + " bytes)");
            }
            return body;
        } finally {
            if (perEndpoint != null) perEndpoint.release();
            slots.release();
        }
    }

    // ---- parsing ------------------------------------------------------------

    /** Parses an Overpass JSON response. Public so offline tools can feed saved responses through it. */
    public OsmData parse(String json) {
        List<OsmNode> nodes = new ArrayList<>();
        List<OsmWay> ways = new ArrayList<>();
        List<OsmArea> areas = new ArrayList<>();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray elements = root.getAsJsonArray("elements");
        if (elements == null) return new OsmData(nodes, ways, areas);

        for (JsonElement el : elements) {
            JsonObject obj = el.getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            long id = obj.has("id") ? obj.get("id").getAsLong() : 0L;
            Map<String, String> tags = readTags(obj);

            switch (type) {
                case "node" -> {
                    if (tags.isEmpty() || !obj.has("lat")) continue;
                    nodes.add(new OsmNode(id, new LatLon(obj.get("lat").getAsDouble(), obj.get("lon").getAsDouble()), tags));
                }
                case "way" -> {
                    List<LatLon> pts = readGeometry(obj.getAsJsonArray("geometry"));
                    if (pts.size() < 2) continue;
                    OsmWay way = new OsmWay(id, pts, tags);
                    if (way.isClosed() && OsmTags.isAreaTagged(tags)) {
                        areas.add(new OsmArea(id, List.of(pts), List.of(), tags));
                    } else {
                        ways.add(way);
                    }
                }
                case "relation" -> {
                    JsonArray members = obj.getAsJsonArray("members");
                    if (members == null) continue;
                    String relType = tags.get("type");
                    boolean multipolygon = "multipolygon".equals(relType) || "boundary".equals(relType)
                            || tags.containsKey("building") || tags.containsKey("natural") || tags.containsKey("landuse")
                            || tags.containsKey("water") || tags.containsKey("leisure");
                    if (!multipolygon) continue;
                    List<List<LatLon>> outerFrags = new ArrayList<>();
                    List<List<LatLon>> innerFrags = new ArrayList<>();
                    for (JsonElement mEl : members) {
                        JsonObject m = mEl.getAsJsonObject();
                        if (!"way".equals(m.has("type") ? m.get("type").getAsString() : "")) continue;
                        String role = m.has("role") ? m.get("role").getAsString() : "";
                        List<LatLon> pts = readGeometry(m.getAsJsonArray("geometry"));
                        if (pts.size() < 2) continue;
                        if ("inner".equals(role)) innerFrags.add(pts);
                        else outerFrags.add(pts); // "outer" or unset
                    }
                    List<List<LatLon>> outers = RingAssembler.assemble(outerFrags);
                    List<List<LatLon>> inners = RingAssembler.assemble(innerFrags);
                    if (outers.isEmpty()) continue;
                    areas.add(new OsmArea(id, outers, inners, tags));
                }
                default -> { }
            }
        }
        return new OsmData(nodes, ways, areas);
    }

    private static Map<String, String> readTags(JsonObject obj) {
        if (!obj.has("tags")) return new HashMap<>();
        JsonObject t = obj.getAsJsonObject("tags");
        Map<String, String> tags = new HashMap<>(t.size() * 2);
        for (String k : t.keySet()) {
            JsonElement v = t.get(k);
            if (v != null && v.isJsonPrimitive()) tags.put(k, v.getAsString());
        }
        return tags;
    }

    private static List<LatLon> readGeometry(JsonArray geometry) {
        if (geometry == null) return List.of();
        List<LatLon> pts = new ArrayList<>(geometry.size());
        for (JsonElement pt : geometry) {
            if (!pt.isJsonObject()) continue; // null entries for missing nodes
            JsonObject p = pt.getAsJsonObject();
            if (!p.has("lat") || !p.has("lon")) continue;
            pts.add(new LatLon(p.get("lat").getAsDouble(), p.get("lon").getAsDouble()));
        }
        return pts;
    }

    // ---- gzip cache helpers --------------------------------------------------

    private static String readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void writeGzip(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
