package com.berg.orbis.worldgen;

import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.extract.ExtractTiles;
import com.berg.orbis.osm.extract.LocalExtractStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named places (city, suburbs, neighbourhoods, villages, islands) for the labels of the pregen map, read from
 * the local map extract: OSM's {@code place=*} points, plus areas tagged the same way at the middle of their
 * outline. Only the place records are kept per extract cell (cached by file time), so reading a whole
 * municipality costs a few seconds once.
 */
public final class MapPlaces {

    /** A named place in chunk coordinates; lower rank = more important (drawn first, larger). */
    public record Place(String name, String kind, int rank, double cx, double cz) {}

    private record CellPlaces(FileTime mtime, List<Raw> places) {}

    private record Raw(String name, String kind, double lat, double lon) {}

    private static final Map<Path, CellPlaces> CACHE = new HashMap<>();

    private MapPlaces() {}

    /** Importance of a place kind, or -1 for kinds that are not labelled. */
    static int rank(String kind) {
        return switch (kind) {
            case "city" -> 0;
            case "town" -> 1;
            case "borough" -> 2;
            case "suburb", "village" -> 3;
            case "quarter" -> 4;
            case "neighbourhood", "island" -> 5;
            case "hamlet" -> 6;
            case "locality" -> 7;
            default -> -1;
        };
    }

    /**
     * The places inside the chunk rectangle, from every extract that overlaps it (the full-detail one preferred).
     * Returns an empty list when there is no extract.
     */
    public static List<Place> load(Path extractsRoot, CoordinateMapper mapper, double metersPerBlock,
                                   int minCx, int minCz, int maxCx, int maxCz) {
        double s = Double.MAX_VALUE, n = -Double.MAX_VALUE, w = Double.MAX_VALUE, e = -Double.MAX_VALUE;
        for (double[] corner : new double[][]{{minCx * 16.0, minCz * 16.0}, {(maxCx + 1) * 16.0, minCz * 16.0},
                {minCx * 16.0, (maxCz + 1) * 16.0}, {(maxCx + 1) * 16.0, (maxCz + 1) * 16.0}}) {
            double[] ll = mapper.toLatLonExact(corner[0], corner[1]);
            s = Math.min(s, ll[0]);
            n = Math.max(n, ll[0]);
            w = Math.min(w, ll[1]);
            e = Math.max(e, ll[1]);
        }
        LocalExtractStore store = LocalExtractStore.get(extractsRoot);
        List<LocalExtractStore.Extract> extracts = new ArrayList<>(store.extracts());
        // Full-detail extracts first: they have every neighbourhood; a country "map" extract may only keep towns.
        extracts.sort(Comparator.comparing((LocalExtractStore.Extract x) -> "full".equals(x.profile()) ? 0 : 1));
        Map<String, Place> byKey = new HashMap<>();
        for (LocalExtractStore.Extract ex : extracts) {
            double es = Math.max(s, ex.south()), en = Math.min(n, ex.north()), ew = Math.max(w, ex.west()), ee = Math.min(e, ex.east());
            if (es > en || ew > ee) continue;
            int c0 = ExtractTiles.cellIndex((int) Math.round(es * 1e7)), c1 = ExtractTiles.cellIndex((int) Math.round(en * 1e7));
            int d0 = ExtractTiles.cellIndex((int) Math.round(ew * 1e7)), d1 = ExtractTiles.cellIndex((int) Math.round(ee * 1e7));
            for (int cl = c0; cl <= c1; cl++) {
                for (int cn = d0; cn <= d1; cn++) {
                    for (Raw r : cellPlaces(ExtractTiles.cellFile(ex.dir(), cl, cn))) {
                        if (r.lat() < s || r.lat() > n || r.lon() < w || r.lon() > e) continue;
                        double[] b = mapper.toBlockExact(r.lat(), r.lon());
                        Place p = new Place(r.name(), r.kind(), rank(r.kind()), b[0] / 16.0, b[1] / 16.0);
                        // The same name mapped twice (a point and an area, or two extracts): keep the more important.
                        String key = r.name() + "@" + Math.round(p.cx() / 125) + "," + Math.round(p.cz() / 125);
                        Place old = byKey.get(key);
                        if (old == null || p.rank() < old.rank()) byKey.put(key, p);
                    }
                }
            }
            if (!byKey.isEmpty() && "full".equals(ex.profile())) break; // the full extract has them all
        }
        List<Place> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparingInt(Place::rank).thenComparing(Place::name));
        return out;
    }

    private static List<Raw> cellPlaces(Path file) {
        try {
            if (!Files.exists(file)) return List.of();
            FileTime mtime = Files.getLastModifiedTime(file);
            synchronized (CACHE) {
                CellPlaces c = CACHE.get(file);
                if (c != null && c.mtime().equals(mtime)) return c.places();
            }
            ExtractTiles.Cell cell = ExtractTiles.read(file);
            List<Raw> places = new ArrayList<>();
            for (ExtractTiles.Node node : cell.nodes()) add(places, node.tags(), node.lat() / 1e7, node.lon() / 1e7);
            for (ExtractTiles.Way way : cell.ways()) {
                if (!way.tags().containsKey("place") || way.lat().length == 0) continue;
                double[] c = centroid(way.lat(), way.lon());
                add(places, way.tags(), c[0], c[1]);
            }
            for (ExtractTiles.Relation rel : cell.relations()) {
                if (!rel.tags().containsKey("place")) continue;
                long sumLat = 0, sumLon = 0, count = 0;
                for (ExtractTiles.Member m : rel.members()) {
                    if (m.inner()) continue;
                    for (int i = 0; i < m.lat().length; i++) {
                        sumLat += m.lat()[i];
                        sumLon += m.lon()[i];
                        count++;
                    }
                }
                if (count > 0) add(places, rel.tags(), sumLat / (double) count / 1e7, sumLon / (double) count / 1e7);
            }
            synchronized (CACHE) {
                CACHE.put(file, new CellPlaces(mtime, places));
            }
            return places;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[orbis] Map places: could not read " + file.getFileName() + ": " + ex);
            return List.of();
        }
    }

    private static void add(List<Raw> out, Map<String, String> tags, double lat, double lon) {
        String kind = tags.get("place");
        String name = tags.get("name");
        if (kind == null || name == null || name.isBlank()) return;
        kind = kind.toLowerCase(Locale.ROOT);
        if (rank(kind) < 0) return;
        out.add(new Raw(name.strip(), kind, lat, lon));
    }

    private static double[] centroid(int[] lat, int[] lon) {
        double a = 0, b = 0;
        for (int i = 0; i < lat.length; i++) {
            a += lat[i];
            b += lon[i];
        }
        return new double[]{a / lat.length / 1e7, b / lon.length / 1e7};
    }
}
