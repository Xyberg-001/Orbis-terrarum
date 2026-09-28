package com.berg.orbis.osm.extract;

import com.berg.orbis.osm.LatLon;
import com.berg.orbis.osm.OsmArea;
import com.berg.orbis.osm.OsmData;
import com.berg.orbis.osm.OsmNode;
import com.berg.orbis.osm.OsmTags;
import com.berg.orbis.osm.OsmWay;
import com.berg.orbis.osm.RingAssembler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The imported extracts under {@code config/orbisterrarum/extracts/}. The map
 * data provider asks it first: when an extract covers a region (and its
 * profile suits the world's scale: a "map" import only carries major roads
 * and water, so it serves worlds at 8 m per block or coarser), the region's
 * features come from the local cells instead of Overpass.
 */
public final class LocalExtractStore {

    public record Extract(String name, Path dir, String profile, double south, double west, double north, double east,
                          long ways, long relations) {
        public boolean covers(double s, double w, double n, double e) {
            return s >= south && n <= north && w >= west && e <= east;
        }

        public boolean usableAt(double metersPerBlock) {
            return "full".equals(profile) || metersPerBlock >= 8.0;
        }

        public String describe() {
            return String.format(Locale.ROOT, "%s (%s profile, lat %.1f..%.1f, lon %.1f..%.1f, %,d ways, %,d polygons)",
                    name, profile, south, north, west, east, ways, relations);
        }
    }

    private static final Map<Path, LocalExtractStore> SHARED = new ConcurrentHashMap<>();
    private static final int CELL_CACHE = 48;

    private final Path root;
    private volatile List<Extract> extracts = List.of();
    private volatile long lastScan;
    private final LinkedHashMap<String, ExtractTiles.Cell> cells = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ExtractTiles.Cell> eldest) {
            return size() > CELL_CACHE;
        }
    };

    private LocalExtractStore(Path root) {
        this.root = root;
    }

    /** One store per directory, shared by the provider and the commands. */
    public static LocalExtractStore get(Path root) {
        return SHARED.computeIfAbsent(root.toAbsolutePath().normalize(), LocalExtractStore::new);
    }

    public Path root() {
        return root;
    }

    public List<Extract> extracts() {
        if (System.currentTimeMillis() - lastScan > 30_000) rescan();
        return extracts;
    }

    public synchronized void rescan() {
        List<Extract> found = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    Path index = dir.resolve("index.json");
                    if (!Files.exists(index)) continue;
                    try {
                        JsonObject o = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8)).getAsJsonObject();
                        found.add(new Extract(
                                o.has("name") ? o.get("name").getAsString() : dir.getFileName().toString(), dir,
                                o.has("profile") ? o.get("profile").getAsString() : "map",
                                o.get("south").getAsDouble(), o.get("west").getAsDouble(),
                                o.get("north").getAsDouble(), o.get("east").getAsDouble(),
                                o.has("ways") ? o.get("ways").getAsLong() : 0, o.has("relations") ? o.get("relations").getAsLong() : 0));
                    } catch (RuntimeException | IOException e) {
                        System.err.println("[orbis] Ignoring extract " + dir + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("[orbis] Cannot list extracts in " + root + ": " + e.getMessage());
            }
        }
        extracts = found;
        lastScan = System.currentTimeMillis();
        synchronized (cells) {
            cells.clear();
        }
    }

    /**
     * The extract that fully covers this box and suits this scale, or null. A coarse world prefers a "map"
     * import (small cells: coast, water, major roads), a fine one needs a "full" import.
     */
    public Extract covering(double south, double west, double north, double east, double metersPerBlock) {
        Extract fallback = null;
        for (Extract e : extracts()) {
            if (!e.usableAt(metersPerBlock) || !e.covers(south, west, north, east)) continue;
            boolean preferred = metersPerBlock >= 8.0 ? !"full".equals(e.profile()) : "full".equals(e.profile());
            if (preferred) return e;
            if (fallback == null) fallback = e;
        }
        return fallback;
    }

    /** Every feature of the extract whose bounding box touches the box, as the Overpass parser would deliver it. */
    public OsmData load(Extract ex, double south, double west, double north, double east) throws IOException {
        int s7 = (int) Math.floor(south * 1e7), w7 = (int) Math.floor(west * 1e7);
        int n7 = (int) Math.ceil(north * 1e7), e7 = (int) Math.ceil(east * 1e7);
        List<OsmWay> ways = new ArrayList<>();
        List<OsmArea> areas = new ArrayList<>();
        List<OsmNode> nodes = new ArrayList<>();
        Set<Long> seenWays = new HashSet<>(), seenRelations = new HashSet<>(), seenNodes = new HashSet<>();
        for (int cl = ExtractTiles.cellIndex(s7); cl <= ExtractTiles.cellIndex(n7); cl++) {
            for (int co = ExtractTiles.cellIndex(w7); co <= ExtractTiles.cellIndex(e7); co++) {
                ExtractTiles.Cell cell = cell(ex, cl, co);
                for (ExtractTiles.Way w : cell.ways()) {
                    if (!seenWays.add(w.id()) || !touches(w.lat(), w.lon(), s7, w7, n7, e7)) continue;
                    List<LatLon> pts = points(w.lat(), w.lon());
                    if (pts.size() < 2) continue;
                    Map<String, String> tags = new HashMap<>(w.tags());
                    OsmWay way = new OsmWay(w.id(), pts, tags);
                    if (way.isClosed() && OsmTags.isAreaTagged(tags)) {
                        areas.add(new OsmArea(w.id(), List.of(pts), List.of(), tags));
                    } else {
                        ways.add(way);
                    }
                }
                for (ExtractTiles.Relation r : cell.relations()) {
                    if (!seenRelations.add(r.id())) continue;
                    boolean touch = false;
                    for (ExtractTiles.Member m : r.members()) {
                        if (touches(m.lat(), m.lon(), s7, w7, n7, e7)) {
                            touch = true;
                            break;
                        }
                    }
                    if (!touch) continue;
                    List<List<LatLon>> outerFrags = new ArrayList<>(), innerFrags = new ArrayList<>();
                    for (ExtractTiles.Member m : r.members()) {
                        List<LatLon> pts = points(m.lat(), m.lon());
                        if (pts.size() < 2) continue;
                        (m.inner() ? innerFrags : outerFrags).add(pts);
                    }
                    List<List<LatLon>> outers = RingAssembler.assemble(outerFrags);
                    if (outers.isEmpty()) continue;
                    areas.add(new OsmArea(r.id(), outers, RingAssembler.assemble(innerFrags), new HashMap<>(r.tags())));
                }
                for (ExtractTiles.Node n : cell.nodes()) {
                    if (n.lat() < s7 || n.lat() > n7 || n.lon() < w7 || n.lon() > e7 || !seenNodes.add(n.id())) continue;
                    nodes.add(new OsmNode(n.id(), new LatLon(n.lat() / 1e7, n.lon() / 1e7), new HashMap<>(n.tags())));
                }
            }
        }
        return new OsmData(nodes, ways, areas);
    }

    private ExtractTiles.Cell cell(Extract ex, int cellLat, int cellLon) throws IOException {
        String key = ex.name() + "/" + cellLat + "/" + cellLon;
        synchronized (cells) {
            ExtractTiles.Cell c = cells.get(key);
            if (c != null) return c;
        }
        ExtractTiles.Cell c = ExtractTiles.read(ExtractTiles.cellFile(ex.dir(), cellLat, cellLon));
        synchronized (cells) {
            cells.put(key, c);
        }
        return c;
    }

    private static boolean touches(int[] lat, int[] lon, int s7, int w7, int n7, int e7) {
        int minLat = Integer.MAX_VALUE, maxLat = Integer.MIN_VALUE, minLon = Integer.MAX_VALUE, maxLon = Integer.MIN_VALUE;
        for (int i = 0; i < lat.length; i++) {
            if (lat[i] < minLat) minLat = lat[i];
            if (lat[i] > maxLat) maxLat = lat[i];
            if (lon[i] < minLon) minLon = lon[i];
            if (lon[i] > maxLon) maxLon = lon[i];
        }
        return maxLat >= s7 && minLat <= n7 && maxLon >= w7 && minLon <= e7;
    }

    private static List<LatLon> points(int[] lat, int[] lon) {
        List<LatLon> pts = new ArrayList<>(lat.length);
        for (int i = 0; i < lat.length; i++) pts.add(new LatLon(lat[i] / 1e7, lon[i] / 1e7));
        return pts;
    }
}
