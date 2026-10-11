package com.berg.orbis.config;

import com.berg.orbis.OrbisMod;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every source of downloaded or imported data, by country, with the folders its copies are kept in. A country's
 * own services (its photos, rock map, road widths...) are used by themselves wherever they cover, inside that
 * country's outline; everywhere else the worldwide data stands in. Each source's copies can be measured and
 * deleted (downloaded sources are fetched again when new chunks need them).
 */
public final class DataSources {

    public enum Kind { BUILT_IN, NATIONAL, IMPORTED }

    /**
     * One source. {@code folders} are relative to the data folder; with a {@code prefix} only the files directly in
     * the (single) folder whose names start with it are the source's ("" for every file directly in it, none of its
     * subfolders). {@code area}: the outline of the country or state a national service covers (see {@link Outlines}).
     */
    public record Source(String id, String label, String region, Kind kind, double south, double west, double north, double east,
                         String area, List<String> folders, String prefix) {

        /** Whether the box touches what the source covers: its box and, for a national one, its country's outline. */
        public boolean overlaps(double s, double w, double n, double e) {
            return n >= south && s <= north && e >= west && w <= east && (area == null || Outlines.touches(area, s, w, n, e));
        }

        /** The country (the region without its state or province). */
        public String country() {
            int i = region.indexOf(" (");
            return i < 0 ? region : region.substring(0, i);
        }
    }

    /** Files and bytes a source keeps on disk. */
    public record Usage(long files, long bytes) {
    }

    public static final String EVERYWHERE = "Everywhere", IMPORTED = "Imported by you";

    /** Country, label and outline (see {@link Outlines}) of the services shipped by default (others: their own name, "Other"). */
    private static final Map<String, String[]> PHOTO_NAMES = new HashMap<>();

    static {
        String[][] n = {
                {"ch-swissimage", "Switzerland", "Aerial photos (swisstopo)", "CH"}, {"nl-pdok", "Netherlands", "Aerial photos (PDOK)", "NL"},
                {"be-vl-ortho", "Belgium (Flanders)", "Aerial photos (Flanders)", "BE-VLG"}, {"be-wal-ortho", "Belgium (Wallonia)", "Aerial photos (Wallonia)", "BE-WAL"},
                {"lu-ortho", "Luxembourg", "Aerial photos (geoportail.lu)", "LU"}, {"fr-ign", "France", "Aerial photos (IGN)", "FR"},
                {"es-pnoa", "Spain", "Aerial photos (PNOA)", "ES"}, {"es-pnoa-canarias", "Spain (Canary Islands)", "Aerial photos (PNOA)", "ES"},
                {"de-by-dop", "Germany (Bavaria)", "Aerial photos (Bavaria)", "DE-BY"},
                {"de-be-truedop", "Germany (Berlin)", "Aerial photos (Berlin)", "DE-BE"}, {"de-nw-dop", "Germany (North Rhine-Westphalia)", "Aerial photos (NRW)", "DE-NW"},
                {"at-basemap", "Austria", "Aerial photos (basemap.at)", "AT"}, {"cz-cuzk", "Czechia", "Aerial photos (ČÚZK)", "CZ"},
                {"si-gurs", "Slovenia", "Aerial photos (GURS)", "SI"}, {"ee-maaamet", "Estonia", "Aerial photos (Maa- ja Ruumiamet)", "EE"},
                {"jp-gsi", "Japan", "Aerial photos (GSI)", "JP"}, {"tw-nlsc", "Taiwan", "Aerial photos (NLSC)", "TW"},
                {"hk-landsd", "Hong Kong", "Aerial photos (Lands Department)", "HK"}, {"au-nsw", "Australia (New South Wales)", "Aerial photos (NSW)", "AU-NSW"},
                {"au-vic", "Australia (Victoria)", "Aerial photos (Vicmap)", "AU-VIC"}, {"ca-on-geo", "Canada (Ontario)", "Aerial photos (Ontario GEO)", "CA-ON"},
                {"us-ma-massgis", "United States (Massachusetts)", "Aerial photos (MassGIS)", "US-MA"}, {"usgs-naip", "United States", "Aerial photos (NAIP)", "US"},
                {"esri-world-imagery", EVERYWHERE, "Satellite photos (Esri)", null},
                {"kartverket-dom-1m", "Norway", "Lidar building heights and roofs (Kartverket)", "NO"},
                {"nl-ahn-dsm", "Netherlands", "Lidar building heights and roofs (AHN)", "NL"},
                {"fr-ign-mns", "France", "Lidar building heights and roofs (IGN)", "FR"},
                {"de-nw-ndom", "Germany (North Rhine-Westphalia)", "Lidar building heights and roofs (NRW)", "DE-NW"},
                {"es-ign-mdsn", "Spain", "Building heights (IGN, lidar)", "ES"},
                {"ee-maaamet-ndsm", "Estonia", "Building heights (Maa- ja Ruumiamet, lidar)", "EE"},
                {"cz-cuzk-dmp1g", "Czechia", "Building heights (ČÚZK, lidar)", "CZ"},
                {"us-or-dogami-dsm", "United States (Oregon)", "Lidar building heights and roofs (DOGAMI)", "US-OR"},
                {"us-ky-dsm", "United States (Kentucky)", "Lidar building heights and roofs (KyFromAbove)", "US-KY"},
                {"us-dc-ndsm", "United States (Washington DC)", "Lidar building heights and roofs (DC)", "US-DC"},
                {"ca-nb-dsm", "Canada (New Brunswick)", "Lidar building heights and roofs (GNB)", "CA-NB"},
                {"ch-swisssurface3d", "Switzerland", "Lidar building heights and roofs (swisstopo)", "CH"},
                {"fr-bdtopo", "France", "Building heights and floors (BD TOPO)", "FR"},
                {"de-lod2", "Germany", "German building heights (LoD2)", null},
                {"fr-bdtopo-roads", "France", "Road widths and lanes (BD TOPO)", "FR"},
                {"fi-syke-contours", "Finland", "Lake depth contours (SYKE)", "FI"},
                {"fi-syke-deepest", "Finland", "Lake greatest depths (SYKE)", "FI"},
                {"us-mn-dnr", "United States (Minnesota)", "Lake depth contours (DNR)", "US-MN"},
                {"ca-on-mnrf", "Canada (Ontario)", "Lake depth contours (MNRF)", "CA-ON"},
                {"fi-digiroad-width", "Finland", "Road widths (Digiroad)", "FI"},
                {"fi-digiroad-lanes", "Finland", "Road lanes (Digiroad)", "FI"},
                {"us-hpms", "United States", "Road lanes and lane widths (FHWA HPMS)", "US"},
                {"ca-bc-dra", "Canada (British Columbia)", "Road lanes (Digital Road Atlas)", "CA-BC"},
                {"nl-3dbag", "Netherlands", "Building heights and roofs (3DBAG)", "NL"},
                {"si-gurs-stavbe", "Slovenia", "Building heights and floors (GURS)", "SI"},
                {"at-wien-bkm", "Austria (Vienna)", "Building heights (Vienna building model)", null},
                {"us-nyc-footprints", "United States (New York City)", "Building heights (NYC footprints)", null},
                {"ca-hrdem-dsm", "Canada", "Lidar building heights and roofs (NRCan HRDEM)", "CA"},
        };
        for (String[] e : n) PHOTO_NAMES.put(e[0], new String[]{e[1], e[2], e[3]});
    }

    private DataSources() {}

    /** Every source the config knows, the worldwide ones first, then by country, then the imported data. */
    public static List<Source> list(OrbisConfig c) {
        List<Source> l = new ArrayList<>();
        // ---- worldwide
        l.add(world("mapterhorn", "Terrain heights (Mapterhorn)", List.of("dem-cache/tiles.mapterhorn.com"), null));
        l.add(world("gedtm30", "Bare-earth terrain outside surveys (GEDTM30)", List.of("dem-cache/gedtm30"), null));
        l.add(world("seascape", "Sea floor (Open Waters)", List.of("dem-cache/tiles.openwaters.io"), null));
        l.add(world("overpass", "OpenStreetMap downloads (Overpass)", List.of("osm-cache"), null));
        l.add(world("worldcover", "Land cover (ESA WorldCover)", List.of("worldcover-cache"), null));
        l.add(world("macrostrat", "Rock types (Macrostrat)", List.of("geology-macrostrat-cache"), null));
        l.add(world("map-backgrounds", "Map and preview backgrounds", List.of("map-tile-cache", "preview", "outline-cache"), null));
        // ---- photos (worldwide or national, as configured)
        for (OrbisConfig.ImagerySource s : c.imagerySources) {
            if (s == null || s.name == null || s.name.isBlank()) continue;
            String[] names = PHOTO_NAMES.getOrDefault(s.name, new String[]{"Other", "Aerial photos (" + s.name + ")", null});
            boolean global = (s.north - s.south) * (s.east - s.west) > 300 * 160;
            l.add(new Source(s.name, names[1], global ? EVERYWHERE : names[0], global ? Kind.BUILT_IN : Kind.NATIONAL,
                    s.south, s.west, s.north, s.east, names[2], List.of("imagery-cache"), s.name.replaceAll("[^A-Za-z0-9_-]", "_") + "_"));
        }
        // ---- Norway's own
        double[] no = {57.8, 4.0, 71.4, 31.5};
        l.add(national("ngu-bedrock", "Rock types (NGU)", "Norway", no, "NO", List.of("geology-cache")));
        l.add(national("nve-lakes", "Lake depths (NVE)", "Norway", no, "NO", List.of("lake-survey-cache")));
        l.add(national("nvdb-roads", "Road widths and lanes (NVDB)", "Norway", no, "NO", List.of("nvdb-cache")));
        // ---- lidar building heights (each where it covers; Kartverket's with its switch in the Advanced tab)
        for (OrbisConfig.ElevationSource es : c.surfaceModelSources) addLidar(l, es, "dsm-cache");
        // ---- building databases (heights and floors)
        for (OrbisConfig.BuildingSource b : c.buildingSources) {
            if (b == null || b.name == null || b.name.isBlank()) continue;
            String[] names = PHOTO_NAMES.getOrDefault(b.name, new String[]{"Other", "Building heights (" + b.name + ")", null});
            boolean imported = b.urlTemplate == null || b.urlTemplate.isBlank(); // filled by a tool, not downloaded
            l.add(new Source(b.name, names[1], imported ? IMPORTED : names[0], imported ? Kind.IMPORTED : Kind.NATIONAL,
                    b.south, b.west, b.north, b.east, imported ? null : names[2],
                    List.of("building-db-cache/" + b.name.replaceAll("[^A-Za-z0-9_-]", "_")), null));
        }
        // ---- road databases (widths and lanes)
        for (OrbisConfig.RoadSource rs : c.roadSources) {
            if (rs == null || rs.name == null || rs.name.isBlank()) continue;
            String[] names = PHOTO_NAMES.getOrDefault(rs.name, new String[]{"Other", "Road widths (" + rs.name + ")", null});
            l.add(new Source(rs.name, names[1], names[0], Kind.NATIONAL, rs.south, rs.west, rs.north, rs.east, names[2],
                    List.of("road-db-cache/" + rs.name.replaceAll("[^A-Za-z0-9_-]", "_")), null));
        }
        // ---- lake depth surveys outside Norway
        for (OrbisConfig.LakeSurveySource ls : c.lakeSurveySources) {
            if (ls == null || ls.name == null || ls.name.isBlank()) continue;
            String[] names = PHOTO_NAMES.getOrDefault(ls.name, new String[]{"Other", "Lake depths (" + ls.name + ")", null});
            l.add(new Source(ls.name, names[1], names[0], Kind.NATIONAL, ls.south, ls.west, ls.north, ls.east, names[2],
                    List.of("lake-depth-cache/" + ls.name.replaceAll("[^A-Za-z0-9_-]", "_")), null));
        }
        // ---- imported with the tools
        l.add(imported("import-extracts", "OpenStreetMap extracts", List.of("extracts")));
        l.add(imported("import-canopy", "Tree positions and heights", List.of("canopy-cache")));
        l.add(imported("import-canopy-src", "Canopy source tiles (only for cutting new areas)", List.of("canopy-src")));
        l.add(imported("import-heights", "Building heights", List.of("heights")));
        l.add(imported("import-lake-depths", "Lake depths (GLOBathy)", List.of("lake-depths", "lake-depths-src")));
        l.add(imported("import-places", "Shops and places (Overture)", List.of("places")));
        return l;
    }

    private static Source world(String id, String label, List<String> folders, String prefix) {
        return new Source(id, label, EVERYWHERE, Kind.BUILT_IN, -90, -180, 90, 180, null, folders, prefix);
    }

    private static Source national(String id, String label, String region, double[] box, String area, List<String> folders) {
        return new Source(id, label, region, Kind.NATIONAL, box[0], box[1], box[2], box[3], area, folders, null);
    }

    private static Source imported(String id, String label, List<String> folders) {
        return new Source(id, label, IMPORTED, Kind.IMPORTED, -90, -180, 90, 180, null, folders, null);
    }

    private static void addLidar(List<Source> l, OrbisConfig.ElevationSource es, String folder) {
        if (es == null || es.name == null || es.name.isBlank()) return;
        String[] names = PHOTO_NAMES.getOrDefault(es.name, new String[]{"Other", "Lidar (" + es.name + ")", null});
        l.add(new Source(es.name, names[1], names[0], Kind.NATIONAL, es.south, es.west, es.north, es.east, names[2],
                List.of(folder + "/" + es.name.replaceAll("[^A-Za-z0-9_-]", "_")), null));
    }

    // ------------------------------------------------------------------ coverage

    private static volatile OrbisConfig sourcesFor;
    private static volatile Map<String, Source> byId = Map.of();

    /**
     * Whether a source is to be asked for this box: it covers it (a national service: its country's outline, with
     * 2 km to spare; a box alone is too wide: France's holds Bern and Geneva). Sources it does not know: yes.
     */
    public static boolean covers(String id, double south, double west, double north, double east) {
        OrbisConfig c = OrbisMod.config();
        if (c == null) return true;
        Source s = sources(c).get(id);
        double m = 0.02;
        return s == null || s.overlaps(south - m, west - m, north + m, east + m);
    }

    /** The outline keys starting with a prefix ("US-": the states) that touch the box. */
    public static List<String> areasTouching(String prefix, double s, double w, double n, double e) {
        List<String> out = new ArrayList<>();
        for (String key : Outlines.keys()) if (key.startsWith(prefix) && Outlines.touches(key, s, w, n, e)) out.add(key);
        return out;
    }

    private static Map<String, Source> sources(OrbisConfig c) {
        if (sourcesFor != c) {
            Map<String, Source> m = new HashMap<>();
            for (Source s : list(c)) m.put(s.id, s);
            byId = m;
            sourcesFor = c;
        }
        return byId;
    }

    // ------------------------------------------------------------------ what a world needs

    /**
     * A service the world will use that needs something of the player (a VPN, patience): what to tell them, and a
     * light request ({@code probe}) to find out whether it answers from this network.
     */
    public record Requirement(String name, String text, String probe) {
    }

    /** The services with special needs that these settings would use around the world's location (and selection). */
    public static List<Requirement> requirements(OrbisConfig c) {
        List<Requirement> out = new ArrayList<>();
        if (!OrbisConfig.lidarUseful(c.metersPerBlock)) return out; // coarser scales skip the lidar services
        List<double[]> boxes = worldBoxes(c);
        boolean kartverket = false;
        for (Source s : list(c)) {
            if (s.id.equals("kartverket-dom-1m") && (c.lidarSurfaceModel || c.useHighResElevation) && touches(s, boxes)) kartverket = true;
        }
        if (kartverket) {
            out.add(new Requirement("Kartverket lidar (Norway)",
                    "Kartverket's servers only answer from some networks: if this one says they don't, a VPN to Norway can help."
                            + " They are slow, so generation is slower while lidar is on. Switch it off in the Advanced tab if you"
                            + " don't need measured building heights and roofs.",
                    KARTVERKET_PROBE));
        }
        return out;
    }

    private static boolean touches(Source s, List<double[]> boxes) {
        for (double[] b : boxes) if (s.overlaps(b[0], b[1], b[2], b[3])) return true;
        return false;
    }

    /**
     * A light request that answers with a small GeoTIFF when Kartverket's surface model can be reached from this
     * network: an 8 x 8 pixel piece of central Bergen. Its GetCapabilities was the probe until it answered 502 Bad
     * Gateway on 4 Oct 2026 while every tile request worked, and a world was created without the surface model.
     */
    public static final String KARTVERKET_PROBE = "https://wcs.geonorge.no/skwms1/wcs.hoyde-dom-nhm-25833?SERVICE=WCS&VERSION=1.0.0&REQUEST=GetCoverage"
            + "&COVERAGE=nhm_dom_topo_25833&CRS=EPSG:4326&RESPONSE_CRS=EPSG:4326&BBOX=5.3240,60.3920,5.3250,60.3925&WIDTH=8&HEIGHT=8&FORMAT=GeoTIFF";

    /** Whether a requirement's service answers from this network now (a few seconds at most). */
    public static boolean reachable(Requirement r) {
        try {
            com.berg.orbis.net.OrbisHttp.Response resp = com.berg.orbis.net.OrbisHttp.get(r.probe(), Map.of("User-Agent", "Orbis-Minecraft-Mod/1.0"), 15);
            return resp.status() == 200 && (!r.probe().contains("FORMAT=GeoTIFF") || isTiff(resp.body()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the bytes are a TIFF ("II*" or "MM*"): a service under strain can answer 200 with an error page. */
    public static boolean isTiff(byte[] b) {
        return b != null && b.length > 3 && ((b[0] == 'I' && b[1] == 'I') || (b[0] == 'M' && b[1] == 'M'));
    }

    /** The world's area as boxes (south, west, north, east): 30 km around the origin, 10 km around a custom spawn, the selection. */
    public static List<double[]> worldBoxes(OrbisConfig c) {
        List<double[]> boxes = new ArrayList<>();
        boxes.add(box(c.originLat, c.originLon, 30_000));
        if (c.customSpawn) boxes.add(box(c.spawnLat, c.spawnLon, 10_000));
        if (c.pregenShapes != null) {
            for (OrbisConfig.PregenShape p : c.pregenShapes) {
                if (p == null || p.latLon == null || p.latLon.length == 0) continue;
                double s = 90, w = 180, n = -90, e = -180;
                for (double[] ll : p.latLon) {
                    s = Math.min(s, ll[0]);
                    n = Math.max(n, ll[0]);
                    w = Math.min(w, ll[1]);
                    e = Math.max(e, ll[1]);
                }
                boxes.add(new double[]{s, w, n, e});
            }
        }
        return boxes;
    }

    private static double[] box(double lat, double lon, double metres) {
        double dLat = metres / 111_320.0, dLon = metres / (111_320.0 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lon - dLon, lat + dLat, lon + dLon};
    }

    // ------------------------------------------------------------------ copies on disk

    /** What each source keeps on disk (walks the folders: can take a while for big terrain caches). */
    public static Map<String, Usage> scan(Path root, List<Source> sources) {
        Map<String, Usage> out = new LinkedHashMap<>();
        Map<String, List<Source>> byFolder = new HashMap<>();
        for (Source s : sources) {
            if (s.prefix != null) {
                byFolder.computeIfAbsent(s.folders.get(0), k -> new ArrayList<>()).add(s);
                continue;
            }
            long[] t = new long[2];
            for (String f : s.folders) walk(root.resolve(f), p -> {
                t[0]++;
                t[1] += size(p);
            });
            out.put(s.id, new Usage(t[0], t[1]));
        }
        // Sources sharing a folder by file name (the photo services): one listing, each file to the longest prefix.
        for (var e : byFolder.entrySet()) {
            Map<String, long[]> t = new HashMap<>();
            for (Source s : e.getValue()) t.put(s.id, new long[2]);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root.resolve(e.getKey()))) {
                for (Path p : files) {
                    Source owner = owner(e.getValue(), p);
                    if (owner == null) continue;
                    long[] u = t.get(owner.id);
                    u[0]++;
                    u[1] += size(p);
                }
            } catch (IOException ignored) {
            }
            for (Source s : e.getValue()) out.put(s.id, new Usage(t.get(s.id)[0], t.get(s.id)[1]));
        }
        return out;
    }

    /** Deletes a source's copies (its folders stay, empty); returns what was freed. Files in use are skipped. */
    public static Usage clear(Path root, Source s) {
        long[] t = new long[2];
        Consumer<Path> delete = p -> {
            long size = size(p);
            try {
                if (Files.deleteIfExists(p)) {
                    t[0]++;
                    t[1] += size;
                }
            } catch (IOException ignored) {
            }
        };
        if (s.prefix != null) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root.resolve(s.folders.get(0)))) {
                List<Source> one = List.of(s);
                for (Path p : files) if (owner(one, p) != null) delete.accept(p);
            } catch (IOException ignored) {
            }
        } else {
            for (String f : s.folders) {
                walk(root.resolve(f), delete);
                removeEmptySubfolders(root.resolve(f));
            }
        }
        return new Usage(t[0], t[1]);
    }

    private static Source owner(List<Source> sources, Path p) {
        String name = p.getFileName().toString();
        Source best = null;
        for (Source s : sources) {
            if (name.startsWith(s.prefix) && (best == null || s.prefix.length() > best.prefix.length())) best = s;
        }
        return best == null || !Files.isRegularFile(p) ? null : best;
    }

    private static void walk(Path dir, Consumer<Path> file) {
        if (!Files.isDirectory(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                    if (a.isRegularFile()) file.accept(p);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path p, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void removeEmptySubfolders(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    if (!d.equals(dir)) {
                        try {
                            Files.delete(d);
                        } catch (IOException ignored) {
                            // not empty (a file in use): keep it
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** "1.3 GB", "640 MB", "12 kB". */
    public static String bytes(long b) {
        if (b >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", b / (double) (1L << 20));
        if (b > 0) return String.format(java.util.Locale.ROOT, "%.0f kB", Math.max(1, b / 1024.0));
        return "nothing";
    }

    /**
     * The outlines of the countries and states the national services cover (Natural Earth, public domain, simplified
     * to about 400 m; orbis-coverage.json), so a service is only asked about inside its country: France's box holds
     * Bern and Geneva, Norway's half of Sweden.
     */
    static final class Outlines {
        /** Per area: rings of lat, lon pairs, each with its box (south, west, north, east) first. */
        private static volatile Map<String, List<double[]>> areas;

        static java.util.Set<String> keys() {
            return load().keySet();
        }

        static boolean touches(String area, double s, double w, double n, double e) {
            List<double[]> rings = load().get(area);
            if (rings == null) return true; // no outline: the box decides
            for (double[] r : rings) {
                if (r[2] < s || r[0] > n || r[3] < w || r[1] > e) continue;
                for (int i = 4; i < r.length; i += 2) {
                    if (r[i] >= s && r[i] <= n && r[i + 1] >= w && r[i + 1] <= e) return true;
                }
                if (inside(r, (s + n) / 2, (w + e) / 2) || inside(r, s, w) || inside(r, n, e)) return true;
            }
            return false;
        }

        private static boolean inside(double[] r, double lat, double lon) {
            boolean in = false;
            int pts = (r.length - 4) / 2;
            for (int i = 0, j = pts - 1; i < pts; j = i++) {
                double yi = r[4 + i * 2], xi = r[5 + i * 2], yj = r[4 + j * 2], xj = r[5 + j * 2];
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }

        private static Map<String, List<double[]>> load() {
            Map<String, List<double[]>> a = areas;
            if (a != null) return a;
            a = new HashMap<>();
            try (var in = DataSources.class.getResourceAsStream("/orbis-coverage.json")) {
                if (in != null) {
                    var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonObject("areas");
                    for (var e : root.entrySet()) {
                        List<double[]> rings = new ArrayList<>();
                        for (var ring : e.getValue().getAsJsonArray()) {
                            var arr = ring.getAsJsonArray();
                            double[] r = new double[4 + arr.size()];
                            r[0] = 90;
                            r[1] = 180;
                            r[2] = -90;
                            r[3] = -180;
                            for (int i = 0; i < arr.size(); i++) {
                                double v = arr.get(i).getAsDouble();
                                r[4 + i] = v;
                                if (i % 2 == 0) {
                                    r[0] = Math.min(r[0], v);
                                    r[2] = Math.max(r[2], v);
                                } else {
                                    r[1] = Math.min(r[1], v);
                                    r[3] = Math.max(r[3], v);
                                }
                            }
                            rings.add(r);
                        }
                        a.put(e.getKey(), rings);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("[orbis] Could not read the coverage outlines: " + ex);
            }
            areas = a;
            return a;
        }
    }
}
