package com.berg.orbis.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Extra points of interest from outside OpenStreetMap (Overture Places, imported with /orbis import-places or
 * PlacesImporter), stored as OSM-style tagged nodes in 0.1-degree cell files under
 * config/orbisterrarum/places/. They are merged into the map data of every region so that shops, bars,
 * museums and the rest that OSM does not know get their furniture and door signs too. A place whose name an
 * OSM point already carries nearby is dropped, so nothing is doubled.
 */
public final class PlacesStore {

    static final double CELL_DEG = 0.1;
    private static final int CACHE_CELLS = 96;
    private static final double DEDUPE_METERS = 60;

    private static final Map<Path, PlacesStore> STORES = new HashMap<>();

    private final Path root;
    private final Map<String, List<OsmNode>> cells = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<OsmNode>> eldest) {
            return size() > CACHE_CELLS;
        }
    });
    private volatile int fileCount = -1;

    private PlacesStore(Path root) {
        this.root = root;
    }

    public static synchronized PlacesStore get(Path root) {
        return STORES.computeIfAbsent(root, PlacesStore::new);
    }

    public Path root() {
        return root;
    }

    /** Forgets cached cells (after an import). */
    public void rescan() {
        cells.clear();
        fileCount = -1;
    }

    public boolean isEmpty() {
        int n = fileCount;
        if (n < 0) {
            n = 0;
            if (Files.isDirectory(root)) {
                try (var s = Files.list(root)) {
                    n = (int) s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz")).count();
                } catch (IOException e) {
                    n = 0;
                }
            }
            fileCount = n;
        }
        return n == 0;
    }

    static String cellName(int latIdx, int lonIdx) {
        return "p_" + latIdx + "_" + lonIdx + ".jsonl.gz";
    }

    /** Places inside the box. */
    public List<OsmNode> load(double south, double west, double north, double east) {
        if (isEmpty()) return List.of();
        List<OsmNode> out = new ArrayList<>();
        int la0 = (int) Math.floor(south / CELL_DEG), la1 = (int) Math.floor(north / CELL_DEG);
        int lo0 = (int) Math.floor(west / CELL_DEG), lo1 = (int) Math.floor(east / CELL_DEG);
        for (int la = la0; la <= la1; la++) {
            for (int lo = lo0; lo <= lo1; lo++) {
                for (OsmNode n : cell(la, lo)) {
                    double lat = n.pos().lat(), lon = n.pos().lon();
                    if (lat >= south && lat <= north && lon >= west && lon <= east) out.add(n);
                }
            }
        }
        return out;
    }

    private List<OsmNode> cell(int la, int lo) {
        String name = cellName(la, lo);
        List<OsmNode> cached = cells.get(name);
        if (cached != null) return cached;
        Path file = root.resolve(name);
        List<OsmNode> nodes = new ArrayList<>();
        if (Files.exists(file)) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    double lat = o.get("lat").getAsDouble(), lon = o.get("lon").getAsDouble();
                    Map<String, String> tags = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tags").entrySet()) tags.put(e.getKey(), e.getValue().getAsString());
                    long id = -(Math.abs((tags.getOrDefault("name", "") + "|" + lat + "|" + lon).hashCode()) + 1_000_000_000L);
                    nodes.add(new OsmNode(id, new LatLon(lat, lon), tags));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Places cell " + name + " unreadable: " + e);
            }
        }
        List<OsmNode> ro = Collections.unmodifiableList(nodes);
        cells.put(name, ro);
        return ro;
    }

    /** The map data plus the places of the box that OSM does not already have. */
    public OsmData merge(OsmData data, double south, double west, double north, double east) {
        List<OsmNode> places = load(south, west, north, east);
        if (places.isEmpty()) return data;
        Map<String, List<LatLon>> osmNames = new HashMap<>();
        for (OsmNode n : data.nodes()) {
            String key = nameKey(n.tags().get("name"));
            if (key != null) osmNames.computeIfAbsent(key, k -> new ArrayList<>()).add(n.pos());
        }
        for (OsmArea a : data.areas()) {
            String key = nameKey(a.tags().get("name"));
            if (key != null) osmNames.computeIfAbsent(key, k -> new ArrayList<>()).add(a.centroid());
        }
        List<OsmNode> merged = new ArrayList<>(data.nodes());
        int added = 0;
        for (OsmNode p : places) {
            String key = nameKey(p.tags().get("name"));
            boolean dup = false;
            if (key != null) {
                List<LatLon> near = osmNames.get(key);
                if (near != null) {
                    for (LatLon q : near) {
                        if (distanceMeters(p.pos(), q) <= DEDUPE_METERS) {
                            dup = true;
                            break;
                        }
                    }
                }
            }
            if (!dup) {
                merged.add(p);
                added++;
            }
        }
        if (added == 0) return data;
        return new OsmData(merged, data.ways(), data.areas());
    }

    static String nameKey(String name) {
        if (name == null) return null;
        String k = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return k.isEmpty() ? null : k;
    }

    static double distanceMeters(LatLon a, LatLon b) {
        double kx = 111_320.0 * Math.cos(Math.toRadians(a.lat()));
        double dx = (a.lon() - b.lon()) * kx, dz = (a.lat() - b.lat()) * 111_320.0;
        return Math.hypot(dx, dz);
    }
}
