package com.berg.orbis.osm.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns a country extract (Geofabrik's {@code <country>-latest.osm.pbf}) into
 * the mod's local tile store, so a whole country can be generated without a
 * single Overpass request.
 *
 * Three passes over the file, none of which needs the whole thing in memory:
 * <ol>
 *   <li>ways and relations: keep the ones the profile wants (for the "map"
 *   profile: coastline, water, glaciers, wetlands, rivers, major roads,
 *   railways, runways) and note which untagged ways the kept multipolygons
 *   are made of;</li>
 *   <li>the ways again, from the first way block on: pick up those members;</li>
 *   <li>the nodes, up to the first way block: coordinates of every node the
 *   kept ways reference.</li>
 * </ol>
 * Then every way and relation is written into each 0.1 degree cell its
 * bounding box touches ({@link ExtractTiles}).
 *
 * <p>A full import limited to an area (a box) reads only what that area needs,
 * so its memory follows the area, not the country ({@link #collectArea}): the
 * points inside the box, the ways and polygons touching them, and the rest of
 * those polygons' outlines. A city out of Germany's 4 GB file then needs a few
 * hundred MB, where the whole-country full import of Norway took 12 GB. A full-country "map" import
 * of Norway needs about 2 GB of heap; run it standalone with
 * {@code java -Xmx4g -cp orbisterrarum-<version>.jar com.berg.orbis.osm.extract.ExtractImporter <file.osm.pbf>}
 * from the Minecraft instance folder, or in game with {@code /orbis import <file>}.
 */
public final class ExtractImporter {

    public enum Profile { MAP, FULL }

    public interface Progress {
        void report(String message);
    }

    public record Summary(String name, Path dir, int ways, int relations, long nodes, int cells, long millis) {
        public String describe() {
            return String.format(Locale.ROOT, "%s: %,d ways, %,d water/land polygons, %,d nodes in %,d cells (%.1f min) -> %s",
                    name, ways, relations, nodes, cells, millis / 60000.0, dir);
        }
    }

    private static final Set<String> MAJOR_HIGHWAYS = Set.of("motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link");
    private static final Set<String> NATURAL_KEPT = Set.of("coastline", "water", "glacier", "wetland");
    private static final Set<String> WATERWAY_KEPT = Set.of("river", "canal", "riverbank", "dam");
    /** Water bodies, plus the built-up areas that coarse worlds fill with vanilla-scale towns. */
    private static final Set<String> LANDUSE_KEPT = Set.of("reservoir", "basin", "residential", "commercial", "retail", "industrial");

    private ExtractImporter() {
    }

    /** "norway-latest.osm.pbf" -> "norway". */
    public static String nameOf(Path pbf) {
        String n = pbf.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".osm.pbf", ".pbf"}) {
            if (n.endsWith(suffix)) n = n.substring(0, n.length() - suffix.length());
        }
        n = n.replaceAll("-latest$", "").replaceAll("-\\d{6}$", "");
        n = n.replaceAll("[^a-z0-9_-]+", "_");
        return n.isEmpty() ? "extract" : n;
    }

    static boolean keepWay(PbfReader.Tags t, Profile profile) {
        if (profile == Profile.FULL) return t.size() > 0;
        String hw = t.get("highway");
        if (hw != null) return MAJOR_HIGHWAYS.contains(hw);
        String rw = t.get("railway");
        if ("rail".equals(rw) || "narrow_gauge".equals(rw)) return true;
        String nat = t.get("natural");
        if (nat != null && NATURAL_KEPT.contains(nat)) return true;
        if (t.has("water")) return true;
        String ww = t.get("waterway");
        if (ww != null && WATERWAY_KEPT.contains(ww)) return true;
        String lu = t.get("landuse");
        if (lu != null && LANDUSE_KEPT.contains(lu)) return true;
        return "runway".equals(t.get("aeroway"));
    }

    static boolean keepRelation(PbfReader.Tags t, Profile profile) {
        if (!"multipolygon".equals(t.get("type"))) return false;
        if (profile == Profile.FULL) return t.size() > 1;
        String nat = t.get("natural");
        if (nat != null && NATURAL_KEPT.contains(nat)) return true;
        if (t.has("water")) return true;
        String ww = t.get("waterway");
        if (ww != null && WATERWAY_KEPT.contains(ww)) return true;
        String lu = t.get("landuse");
        return lu != null && LANDUSE_KEPT.contains(lu);
    }

    /** Keys of tagged nodes worth keeping in a full import (what the Overpass query asks for). */
    private static final Set<String> NODE_KEYS = Set.of("highway", "natural", "amenity", "barrier", "man_made", "power",
            "railway", "aeroway", "leisure", "place", "tourism", "historic", "emergency", "public_transport",
            // businesses are usually a point inside the building, not a tag on the building: furniture and signs need them
            "shop", "office", "craft", "healthcare", "club", "sport");

    static boolean keepNode(PbfReader.Tags t) {
        for (String k : NODE_KEYS) if (t.has(k)) return true;
        return false;
    }

    public static Summary importFile(Path pbf, Path extractsDir, Profile profile, Progress progress) throws IOException {
        return importFile(pbf, extractsDir, profile, null, progress);
    }

    public static Summary importFile(Path pbf, Path extractsDir, Profile profile, String nameOverride, Progress progress) throws IOException {
        return importFile(pbf, extractsDir, profile, nameOverride, null, progress);
    }

    /**
     * @param nameOverride store name (default: derived from the file name, e.g. "norway").
     * @param bbox         {south, west, north, east} in degrees to keep only the cells touching that box
     *                     (a city-sized full-detail store for a server), or null for everything.
     */
    public static Summary importFile(Path pbf, Path extractsDir, Profile profile, String nameOverride, double[] bbox, Progress progress) throws IOException {
        long t0 = System.currentTimeMillis();
        String name = nameOverride != null && !nameOverride.isBlank() ? nameOverride.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_") : nameOf(pbf);
        Path dir = extractsDir.resolve(name);
        Files.createDirectories(dir);

        // ---- pass 1: tagged ways + multipolygon relations ------------------------------
        final Pb.LongList wayIds = new Pb.LongList(1 << 16);
        final List<String[]> wayTags = new ArrayList<>();
        final List<long[]> wayRefs = new ArrayList<>();
        final Pb.LongList relIds = new Pb.LongList(1 << 12);
        final List<String[]> relTags = new ArrayList<>();
        final List<long[]> relMembers = new ArrayList<>();
        final List<boolean[]> relInner = new ArrayList<>();
        final Pb.LongList memberWayIds = new Pb.LongList(1 << 16);
        final long[] firstWay = {-1}, firstRel = {-1};
        final long[] seen = {0, 0};
        final long[] lastReport = {System.currentTimeMillis()};
        // A full import of an area reads only what the area needs; everything else reads the whole file.
        final double[] keepBox = bbox != null && profile == Profile.FULL ? cellAligned(bbox) : null;
        int taggedWays;
        if (keepBox != null) {
            taggedWays = collectArea(pbf, keepBox, wayIds, wayTags, wayRefs, relIds, relTags, relMembers, relInner, firstWay, progress);
            if (wayIds.size() == 0 && relIds.size() == 0) throw new IOException("nothing mapped inside the area in " + pbf);
        } else {

        PbfReader.Visitor pass1 = new PbfReader.Visitor() {
            @Override
            public boolean wantsWays() {
                return true;
            }

            @Override
            public boolean wantsRelations() {
                return true;
            }

            @Override
            public void block(long offset, boolean hasNodes, boolean hasWays, boolean hasRelations) {
                if (hasWays && firstWay[0] < 0) firstWay[0] = offset;
                if (hasRelations && firstRel[0] < 0) firstRel[0] = offset;
            }

            @Override
            public void way(long id, PbfReader.Tags tags, long[] refs) {
                seen[0]++;
                if (refs.length < 2 || !keepWay(tags, profile)) return;
                wayIds.add(id);
                wayTags.add(tagArray(tags));
                wayRefs.add(refs);
            }

            @Override
            public void relation(long id, PbfReader.Tags tags, long[] memberIds, byte[] memberTypes, String[] roles) {
                seen[1]++;
                if (!keepRelation(tags, profile)) return;
                int k = 0;
                for (byte tpe : memberTypes) if (tpe == 1) k++;
                if (k == 0) return;
                long[] mids = new long[k];
                boolean[] inner = new boolean[k];
                k = 0;
                for (int i = 0; i < memberIds.length; i++) {
                    if (memberTypes[i] != 1) continue;
                    mids[k] = memberIds[i];
                    inner[k] = "inner".equals(roles[i]);
                    memberWayIds.add(memberIds[i]);
                    k++;
                }
                relIds.add(id);
                relTags.add(tagArray(tags));
                relMembers.add(mids);
                relInner.add(inner);
            }
        };
        try (PbfReader r = new PbfReader(pbf)) {
            while (r.readBlock(pass1)) {
                if (System.currentTimeMillis() - lastReport[0] > 3000) {
                    lastReport[0] = System.currentTimeMillis();
                    progress.report(String.format(Locale.ROOT, "Pass 1/3: %,d MB read, %,d ways and %,d polygons kept",
                            r.blockOffset() >> 20, wayIds.size(), relIds.size()));
                }
            }
        }
        if (wayIds.size() == 0 && relIds.size() == 0) throw new IOException("nothing kept from " + pbf + " (is it an OSM PBF file?)");

        // ---- pass 2: untagged member ways of the kept multipolygons -----------------
        final int tagged0 = wayIds.size();
        taggedWays = tagged0;
        final long[] memberSorted = memberWayIds.sortedUnique();
        final long[] keptSorted = wayIds.sortedUnique();
        if (memberSorted.length > 0 && firstWay[0] >= 0) {
            PbfReader.Visitor pass2 = new PbfReader.Visitor() {
                @Override
                public boolean wantsWays() {
                    return true;
                }

                @Override
                public void way(long id, PbfReader.Tags tags, long[] refs) {
                    if (refs.length < 2) return;
                    if (Arrays.binarySearch(memberSorted, id) < 0 || Arrays.binarySearch(keptSorted, id) >= 0) return;
                    wayIds.add(id);
                    wayTags.add(new String[0]);
                    wayRefs.add(refs);
                }
            };
            try (PbfReader r = new PbfReader(pbf, firstWay[0], 0)) {
                while (r.readBlock(pass2)) {
                    if (System.currentTimeMillis() - lastReport[0] > 3000) {
                        lastReport[0] = System.currentTimeMillis();
                        progress.report(String.format(Locale.ROOT, "Pass 2/3: %,d MB read, %,d polygon outlines collected",
                                r.blockOffset() >> 20, wayIds.size() - tagged0));
                    }
                }
            }
        }
        }

        // ---- node index: every referenced node id, sorted, in one array -------------------
        // (Norway's lakes and coast reference ~60 million nodes; everything below is sized for that.)
        long totalRefs = 0;
        for (long[] refs : wayRefs) totalRefs += refs.length;
        if (totalRefs > Integer.MAX_VALUE - 16) throw new IOException("extract too large (" + totalRefs + " node references)");
        final long[] nodeIds = new long[(int) totalRefs];
        int fill = 0;
        for (long[] refs : wayRefs) {
            System.arraycopy(refs, 0, nodeIds, fill, refs.length);
            fill += refs.length;
        }
        progress.report(String.format(Locale.ROOT, "Indexing %,d node references...", totalRefs));
        Arrays.sort(nodeIds);
        int unique = 0;
        for (int i = 0; i < nodeIds.length; i++) {
            if (unique == 0 || nodeIds[i] != nodeIds[unique - 1]) nodeIds[unique++] = nodeIds[i];
        }
        final int nodeCount = unique;
        // Way references become indices into the node index (int instead of long; the long arrays are dropped).
        final int wayCount = wayIds.size();
        final int[][] wayIdx = new int[wayCount][];
        for (int w = 0; w < wayCount; w++) {
            long[] refs = wayRefs.get(w);
            int[] idx = new int[refs.length];
            for (int i = 0; i < refs.length; i++) idx[i] = Arrays.binarySearch(nodeIds, 0, nodeCount, refs[i]);
            wayIdx[w] = idx;
            wayRefs.set(w, null);
        }
        wayRefs.clear();

        // ---- pass 3: node coordinates ----------------------------------------------
        final int[] lat = new int[nodeCount], lon = new int[nodeCount];
        final BitSet found = new BitSet(nodeCount);
        final int[] cursor = {0};
        final long[] lastId = {Long.MIN_VALUE};
        // Tagged nodes (full profile): individually mapped trees, lamp posts, benches, stops, ...
        final Pb.LongList nodeIdsTagged = new Pb.LongList(1 << 12);
        final Pb.IntList nodeLatTagged = new Pb.IntList(), nodeLonTagged = new Pb.IntList();
        final List<String[]> nodeTagsTagged = new ArrayList<>();
        final boolean keepNodes = profile == Profile.FULL;
        PbfReader.Visitor pass3 = new PbfReader.Visitor() {
            @Override
            public boolean wantsNodes() {
                return true;
            }

            @Override
            public boolean wantsNodeTags() {
                return keepNodes;
            }

            @Override
            public void taggedNode(long id, int la, int lo, PbfReader.Tags tags) {
                if (!keepNode(tags) || keepBox != null && !inBox(keepBox, la, lo)) return;
                nodeIdsTagged.add(id);
                nodeLatTagged.add(la);
                nodeLonTagged.add(lo);
                nodeTagsTagged.add(tagArray(tags));
            }

            @Override
            public void node(long id, int la, int lo) {
                int i;
                if (id >= lastId[0]) {
                    int c = cursor[0];
                    while (c < nodeCount && nodeIds[c] < id) c++;
                    cursor[0] = c;
                    lastId[0] = id;
                    i = c < nodeCount && nodeIds[c] == id ? c : -1;
                } else {
                    i = Arrays.binarySearch(nodeIds, 0, nodeCount, id);
                }
                if (i >= 0) {
                    lat[i] = la;
                    lon[i] = lo;
                    found.set(i);
                }
            }
        };
        try (PbfReader r = new PbfReader(pbf, 0, firstWay[0] >= 0 ? firstWay[0] + 1 : 0)) {
            while (r.readBlock(pass3)) {
                if (System.currentTimeMillis() - lastReport[0] > 3000) {
                    lastReport[0] = System.currentTimeMillis();
                    progress.report(String.format(Locale.ROOT, "Node positions: %,d MB read, %,d of %,d found",
                            r.blockOffset() >> 20, found.cardinality(), nodeCount));
                }
            }
        }
        long nodesFound = found.cardinality();

        // ---- id -> way index, for relation members -------------------------------------------
        long[] idsSorted = wayIds.toArray();
        Integer[] order = new Integer[wayCount];
        for (int i = 0; i < wayCount; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(idsSorted[a], idsSorted[b]));
        long[] sortedIds = new long[wayCount];
        int[] sortedIdx = new int[wayCount];
        for (int i = 0; i < wayCount; i++) {
            sortedIds[i] = idsSorted[order[i]];
            sortedIdx[i] = order[i];
        }
        order = null;

        // ---- assign to cells --------------------------------------------------------------
        Geometry geom = new Geometry(wayIdx, lat, lon, found);
        Map<Long, Pb.IntList> cellEntries = new HashMap<>();
        for (int w = 0; w < taggedWays; w++) {
            int[] box = geom.bbox(w, null);
            if (box != null) addToCells(cellEntries, box, w);
        }
        for (int r = 0; r < relIds.size(); r++) {
            long[] mids = relMembers.get(r);
            int[] box = null;
            for (long mid : mids) {
                int s = Arrays.binarySearch(sortedIds, mid);
                if (s >= 0) box = geom.bbox(sortedIdx[s], box);
            }
            if (box != null) addToCells(cellEntries, box, -1 - r);
        }
        // Tagged nodes are encoded as entries below -1 - relation count.
        final int nodeBase = -1 - relIds.size();
        for (int i = 0; i < nodeIdsTagged.size(); i++) {
            int[] b = {nodeLatTagged.get(i), nodeLonTagged.get(i), nodeLatTagged.get(i), nodeLonTagged.get(i)};
            addToCells(cellEntries, b, nodeBase - i);
        }

        // ---- write ------------------------------------------------------------------------
        try (Stream<Path> old = Files.list(dir)) {
            for (Path p : old.filter(p -> p.getFileName().toString().startsWith("c_")).toList()) Files.deleteIfExists(p);
        }
        int cellsWritten = 0;
        int[] box = new int[4];
        boolean any = false;
        for (Map.Entry<Long, Pb.IntList> e : cellEntries.entrySet()) {
            int cellLat = (int) (e.getKey() >> 32), cellLon = (int) (long) e.getKey();
            if (bbox != null) {
                double cs = cellLat * ExtractTiles.CELL_DEG, cw = cellLon * ExtractTiles.CELL_DEG;
                if (cs + ExtractTiles.CELL_DEG < bbox[0] || cs > bbox[2] || cw + ExtractTiles.CELL_DEG < bbox[1] || cw > bbox[3]) continue;
            }
            try (ExtractTiles.Writer writer = new ExtractTiles.Writer(ExtractTiles.cellFile(dir, cellLat, cellLon))) {
                Pb.IntList list = e.getValue();
                for (int i = 0; i < list.size(); i++) {
                    int idx = list.get(i);
                    if (idx >= 0) {
                        int[][] pts = geom.points(idx);
                        if (pts != null) writer.way(wayIds.get(idx), toMap(wayTags.get(idx)), pts[0], pts[1]);
                    } else if (idx <= nodeBase) {
                        int n = nodeBase - idx;
                        writer.node(nodeIdsTagged.get(n), toMap(nodeTagsTagged.get(n)), nodeLatTagged.get(n), nodeLonTagged.get(n));
                    } else {
                        int r = -1 - idx;
                        long[] mids = relMembers.get(r);
                        boolean[] inner = relInner.get(r);
                        List<ExtractTiles.Member> members = new ArrayList<>(mids.length);
                        for (int m = 0; m < mids.length; m++) {
                            int s = Arrays.binarySearch(sortedIds, mids[m]);
                            if (s < 0) continue;
                            int[][] pts = geom.points(sortedIdx[s]);
                            if (pts != null) members.add(new ExtractTiles.Member(inner[m], pts[0], pts[1]));
                        }
                        if (!members.isEmpty()) writer.relation(relIds.get(r), toMap(relTags.get(r)), members);
                    }
                }
            }
            cellsWritten++;
            if (!any) {
                box[0] = cellLat;
                box[1] = cellLon;
                box[2] = cellLat;
                box[3] = cellLon;
                any = true;
            } else {
                box[0] = Math.min(box[0], cellLat);
                box[1] = Math.min(box[1], cellLon);
                box[2] = Math.max(box[2], cellLat);
                box[3] = Math.max(box[3], cellLon);
            }
            if (System.currentTimeMillis() - lastReport[0] > 3000) {
                lastReport[0] = System.currentTimeMillis();
                progress.report(String.format(Locale.ROOT, "Writing cells: %,d of %,d", cellsWritten, cellEntries.size()));
            }
        }
        long millis = System.currentTimeMillis() - t0;
        String index = "{\n"
                + "  \"name\": \"" + name + "\",\n"
                + "  \"profile\": \"" + profile.name().toLowerCase(Locale.ROOT) + "\",\n"
                + "  \"source\": \"" + pbf.getFileName().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\n"
                + "  \"cellDegrees\": " + ExtractTiles.CELL_DEG + ",\n"
                + String.format(Locale.ROOT, "  \"south\": %.4f,\n  \"west\": %.4f,\n  \"north\": %.4f,\n  \"east\": %.4f,\n",
                box[0] * ExtractTiles.CELL_DEG, box[1] * ExtractTiles.CELL_DEG, (box[2] + 1) * ExtractTiles.CELL_DEG, (box[3] + 1) * ExtractTiles.CELL_DEG)
                + "  \"ways\": " + taggedWays + ",\n"
                + "  \"relations\": " + relIds.size() + ",\n"
                + "  \"taggedNodes\": " + nodeIdsTagged.size() + ",\n"
                + "  \"nodes\": " + nodesFound + ",\n"
                + "  \"cells\": " + cellsWritten + ",\n"
                + "  \"created\": \"" + java.time.Instant.now() + "\"\n"
                + "}\n";
        Files.writeString(dir.resolve("index.json"), index, StandardCharsets.UTF_8);
        return new Summary(name, dir, taggedWays, relIds.size(), nodesFound, cellsWritten, millis);
    }

    /**
     * The box around an area, out to whole 0.1 degree cells (and a little more): exactly the cells the writer keeps
     * (every cell touching the area, also one whose edge is the area's edge, by the same test), so none of them is
     * written half filled. Starting at the area's own edge left the outer cells of Bergen without 28,550 of 369,471
     * ways.
     */
    private static double[] cellAligned(double[] b) {
        double c = ExtractTiles.CELL_DEG, m = 0.005;
        int la0 = Integer.MAX_VALUE, la1 = Integer.MIN_VALUE, lo0 = Integer.MAX_VALUE, lo1 = Integer.MIN_VALUE;
        for (int i = (int) Math.floor(b[0] / c) - 1; i <= (int) Math.floor(b[2] / c) + 1; i++) {
            double cs = i * c;
            if (cs + c < b[0] || cs > b[2]) continue;
            la0 = Math.min(la0, i);
            la1 = Math.max(la1, i);
        }
        for (int i = (int) Math.floor(b[1] / c) - 1; i <= (int) Math.floor(b[3] / c) + 1; i++) {
            double cw = i * c;
            if (cw + c < b[1] || cw > b[3]) continue;
            lo0 = Math.min(lo0, i);
            lo1 = Math.max(lo1, i);
        }
        return new double[]{la0 * c - m, lo0 * c - m, (la1 + 1) * c + m, (lo1 + 1) * c + m};
    }

    private static boolean inBox(double[] b, int latE7, int lonE7) {
        double la = latE7 / 1e7, lo = lonE7 / 1e7;
        return la >= b[0] && la <= b[2] && lo >= b[1] && lo <= b[3];
    }

    /**
     * What a full-detail store of one area needs, in three reads that keep only that much in memory:
     * <ol>
     *   <li>the nodes up to the first way block: the ids of those inside the box;</li>
     *   <li>the ways and relations: every way with a node inside (tagged ones are kept, untagged ones may be polygon
     *   pieces), and every multipolygon with a piece among them;</li>
     *   <li>the ways again: the pieces of those polygons that lie wholly outside the box (a lake or coast crossing
     *   the edge stays whole).</li>
     * </ol>
     * Fills the lists as the whole-file passes do (tagged ways first, then untagged polygon pieces); the positions of
     * all their nodes come with the importer's last pass. Returns the number of tagged ways.
     */
    private static int collectArea(Path pbf, double[] box, Pb.LongList wayIds, List<String[]> wayTags, List<long[]> wayRefs,
                                   Pb.LongList relIds, List<String[]> relTags, List<long[]> relMembers, List<boolean[]> relInner,
                                   long[] firstWay, Progress progress) throws IOException {
        long[] lastReport = {System.currentTimeMillis()};

        // 1: node ids inside the box
        Pb.LongList inside = new Pb.LongList(1 << 16);
        boolean[] waysReached = {false};
        PbfReader.Visitor nodes = new PbfReader.Visitor() {
            @Override
            public boolean wantsNodes() {
                return true;
            }

            @Override
            public void block(long offset, boolean hasNodes, boolean hasWays, boolean hasRelations) {
                if (hasWays && firstWay[0] < 0) {
                    firstWay[0] = offset;
                    waysReached[0] = true;
                }
            }

            @Override
            public void node(long id, int la, int lo) {
                if (inBox(box, la, lo)) inside.add(id);
            }
        };
        try (PbfReader r = new PbfReader(pbf)) {
            while (!waysReached[0] && r.readBlock(nodes)) {
                if (System.currentTimeMillis() - lastReport[0] > 3000) {
                    lastReport[0] = System.currentTimeMillis();
                    progress.report(String.format(Locale.ROOT, "Area pass 1/3: %,d MB read, %,d points inside the area",
                            r.blockOffset() >> 20, inside.size()));
                }
            }
        }
        if (firstWay[0] < 0) throw new IOException("no ways in " + pbf + " (is it an OSM PBF file?)");
        final long[] in = inside.sortedUnique();
        inside.clear();

        // 2: ways touching the box, and the multipolygons with a piece among them
        Pb.LongList touchIds = new Pb.LongList(1 << 14);
        List<long[]> touchRefs = new ArrayList<>();
        long[][] touchSorted = {null};
        PbfReader.Visitor ways = new PbfReader.Visitor() {
            @Override
            public boolean wantsWays() {
                return true;
            }

            @Override
            public boolean wantsRelations() {
                return true;
            }

            @Override
            public void way(long id, PbfReader.Tags tags, long[] refs) {
                if (refs.length < 2) return;
                boolean touches = false;
                for (long ref : refs) {
                    if (Arrays.binarySearch(in, ref) >= 0) {
                        touches = true;
                        break;
                    }
                }
                if (!touches) return;
                touchIds.add(id);
                touchRefs.add(refs);
                if (keepWay(tags, Profile.FULL)) {
                    wayIds.add(id);
                    wayTags.add(tagArray(tags));
                    wayRefs.add(refs);
                }
            }

            @Override
            public void relation(long id, PbfReader.Tags tags, long[] memberIds, byte[] memberTypes, String[] roles) {
                if (!keepRelation(tags, Profile.FULL)) return;
                if (touchSorted[0] == null) touchSorted[0] = touchIds.sortedUnique(); // relations come after every way
                int k = 0;
                boolean touches = false;
                for (int i = 0; i < memberIds.length; i++) {
                    if (memberTypes[i] != 1) continue;
                    k++;
                    if (!touches && Arrays.binarySearch(touchSorted[0], memberIds[i]) >= 0) touches = true;
                }
                if (!touches) return;
                long[] mids = new long[k];
                boolean[] inner = new boolean[k];
                k = 0;
                for (int i = 0; i < memberIds.length; i++) {
                    if (memberTypes[i] != 1) continue;
                    mids[k] = memberIds[i];
                    inner[k] = "inner".equals(roles[i]);
                    k++;
                }
                relIds.add(id);
                relTags.add(tagArray(tags));
                relMembers.add(mids);
                relInner.add(inner);
            }
        };
        try (PbfReader r = new PbfReader(pbf, firstWay[0], 0)) {
            while (r.readBlock(ways)) {
                if (System.currentTimeMillis() - lastReport[0] > 3000) {
                    lastReport[0] = System.currentTimeMillis();
                    progress.report(String.format(Locale.ROOT, "Area pass 2/3: %,d MB read, %,d ways and %,d polygons in the area",
                            r.blockOffset() >> 20, wayIds.size(), relIds.size()));
                }
            }
        }
        final int tagged = wayIds.size();

        // The polygons' pieces: from the touching ways where they are, else read once more.
        Pb.LongList memberList = new Pb.LongList(1 << 12);
        for (long[] mids : relMembers) for (long m : mids) memberList.add(m);
        long[] members = memberList.sortedUnique();
        long[] keptSorted = wayIds.sortedUnique();
        long[] touchAll = touchIds.toArray();
        Integer[] order = new Integer[touchAll.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Long.compare(touchAll[x], touchAll[y]));
        long[] touchKeys = new long[order.length];
        for (int i = 0; i < order.length; i++) touchKeys[i] = touchAll[order[i]];
        Pb.LongList outside = new Pb.LongList(1 << 10);
        for (long m : members) {
            if (Arrays.binarySearch(keptSorted, m) >= 0) continue;
            int t = Arrays.binarySearch(touchKeys, m);
            if (t >= 0) {
                wayIds.add(m);
                wayTags.add(new String[0]);
                wayRefs.add(touchRefs.get(order[t]));
            } else {
                outside.add(m);
            }
        }
        touchRefs.clear();

        // 3: the pieces wholly outside the box
        if (outside.size() > 0) {
            final long[] out = outside.sortedUnique();
            final int[] got = {0};
            PbfReader.Visitor pieces = new PbfReader.Visitor() {
                @Override
                public boolean wantsWays() {
                    return true;
                }

                @Override
                public void way(long id, PbfReader.Tags tags, long[] refs) {
                    if (refs.length < 2 || Arrays.binarySearch(out, id) < 0) return;
                    wayIds.add(id);
                    wayTags.add(new String[0]);
                    wayRefs.add(refs);
                    got[0]++;
                }
            };
            try (PbfReader r = new PbfReader(pbf, firstWay[0], 0)) {
                while (r.readBlock(pieces)) {
                    if (System.currentTimeMillis() - lastReport[0] > 3000) {
                        lastReport[0] = System.currentTimeMillis();
                        progress.report(String.format(Locale.ROOT, "Area pass 3/3: %,d MB read, %,d of %,d outline pieces beyond the edge",
                                r.blockOffset() >> 20, got[0], out.length));
                    }
                }
            }
        }
        return tagged;
    }

    /** Way geometry resolved on demand from the node index (nothing per way is kept beyond its node indices). */
    private static final class Geometry {
        final int[][] wayIdx;
        final int[] lat, lon;
        final BitSet found;

        Geometry(int[][] wayIdx, int[] lat, int[] lon, BitSet found) {
            this.wayIdx = wayIdx;
            this.lat = lat;
            this.lon = lon;
            this.found = found;
        }

        /** {lat[], lon[]} of the way's located nodes, or null when fewer than two are known. */
        int[][] points(int w) {
            int[] idx = wayIdx[w];
            int n = 0;
            for (int i : idx) if (i >= 0 && found.get(i)) n++;
            if (n < 2) return null;
            int[] la = new int[n], lo = new int[n];
            int k = 0;
            for (int i : idx) {
                if (i < 0 || !found.get(i)) continue;
                la[k] = lat[i];
                lo[k] = lon[i];
                k++;
            }
            return new int[][]{la, lo};
        }

        /** Bounding box [minLat, minLon, maxLat, maxLon] extended by this way, or null if it has no usable geometry. */
        int[] bbox(int w, int[] into) {
            int[] b = into;
            int n = 0;
            for (int i : wayIdx[w]) {
                if (i < 0 || !found.get(i)) continue;
                if (b == null) b = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                if (lat[i] < b[0]) b[0] = lat[i];
                if (lon[i] < b[1]) b[1] = lon[i];
                if (lat[i] > b[2]) b[2] = lat[i];
                if (lon[i] > b[3]) b[3] = lon[i];
                n++;
            }
            return n < 2 && into == null ? null : b;
        }
    }

    private static String[] tagArray(PbfReader.Tags t) {
        Map<String, String> m = t.toMap();
        String[] a = new String[m.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            a[i++] = e.getKey();
            a[i++] = e.getValue();
        }
        return a;
    }

    private static Map<String, String> toMap(String[] a) {
        Map<String, String> m = new HashMap<>(a.length + 1);
        for (int i = 0; i + 1 < a.length; i += 2) m.put(a[i], a[i + 1]);
        return m;
    }

    /** [minLat, minLon, maxLat, maxLon] in E7, extending {@code into} when given. */
    private static int[] bbox(int[] lat, int[] lon, int[] into) {
        int[] b = into != null ? into : new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < lat.length; i++) {
            if (lat[i] < b[0]) b[0] = lat[i];
            if (lon[i] < b[1]) b[1] = lon[i];
            if (lat[i] > b[2]) b[2] = lat[i];
            if (lon[i] > b[3]) b[3] = lon[i];
        }
        return b;
    }

    private static void addToCells(Map<Long, Pb.IntList> cells, int[] box, int entry) {
        int c0 = ExtractTiles.cellIndex(box[0]), c1 = ExtractTiles.cellIndex(box[2]);
        int d0 = ExtractTiles.cellIndex(box[1]), d1 = ExtractTiles.cellIndex(box[3]);
        // A single broken way spanning the planet must not be copied into thousands of cells.
        if ((long) (c1 - c0 + 1) * (d1 - d0 + 1) > 400) return;
        for (int c = c0; c <= c1; c++) {
            for (int d = d0; d <= d1; d++) {
                cells.computeIfAbsent(((long) c << 32) | (d & 0xffffffffL), k -> new Pb.IntList()).add(entry);
            }
        }
    }

    // ---- standalone ---------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        Path pbf = null, out = null;
        String name = null;
        double[] bbox = null;
        Profile profile = Profile.MAP;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--full" -> profile = Profile.FULL;
                case "--out" -> out = Path.of(args[++i]);
                case "--name" -> name = args[++i];
                case "--bbox" -> bbox = new double[]{Double.parseDouble(args[++i]), Double.parseDouble(args[++i]),
                        Double.parseDouble(args[++i]), Double.parseDouble(args[++i])};
                default -> pbf = Path.of(args[i]);
            }
        }
        if (pbf == null || !Files.exists(pbf)) {
            System.err.println("usage: java -Xmx4g -cp orbisterrarum-<version>.jar com.berg.orbis.osm.extract.ExtractImporter <country-latest.osm.pbf> [--full] [--name <store name>] [--bbox <south> <west> <north> <east>] [--out <extracts dir>]");
            System.err.println("Run it from the Minecraft instance folder (the one with mods/ and config/): the extract lands in config/orbisterrarum/extracts/<name>/.");
            System.exit(2);
        }
        if (out == null) {
            out = Files.isDirectory(Path.of("config", "orbisterrarum")) ? Path.of("config", "orbisterrarum", "extracts") : Path.of("extracts");
        }
        System.out.println("Importing " + pbf + " (" + (Files.size(pbf) >> 20) + " MB, profile " + profile + ") into " + out.toAbsolutePath());
        Summary s = importFile(pbf, out, profile, name, bbox, System.out::println);
        System.out.println("Done. " + s.describe());
    }
}
