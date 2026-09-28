package com.berg.orbis.osm;

import java.util.List;
import java.util.Map;

/**
 * A polygonal OSM feature (building, lake, forest, park, ...). Simple closed
 * ways have one outer ring and no inner rings; multipolygon relations can
 * have several of each. Inner rings are holes (islands in a lake, courtyards
 * in a building).
 */
public record OsmArea(long id, List<List<LatLon>> outers, List<List<LatLon>> inners, Map<String, String> tags) {

    /** Approximate area in square metres (sum of outer rings, minus inner rings), for draw ordering. */
    public double approxAreaM2() {
        double a = 0;
        for (List<LatLon> r : outers) a += Math.abs(ringArea(r));
        for (List<LatLon> r : inners) a -= Math.abs(ringArea(r));
        return a;
    }

    /** Signed area of a ring in m^2 using an equirectangular approximation. */
    public static double ringArea(List<LatLon> ring) {
        if (ring.size() < 3) return 0;
        double lat0 = Math.toRadians(ring.get(0).lat());
        double kx = 6_371_000.0 * Math.cos(lat0) * Math.PI / 180.0;
        double kz = 6_371_000.0 * Math.PI / 180.0;
        double sum = 0;
        for (int i = 0; i < ring.size(); i++) {
            LatLon p = ring.get(i), q = ring.get((i + 1) % ring.size());
            double x1 = p.lon() * kx, y1 = p.lat() * kz;
            double x2 = q.lon() * kx, y2 = q.lat() * kz;
            sum += x1 * y2 - x2 * y1;
        }
        return sum / 2.0;
    }

    public LatLon centroid() {
        double lat = 0, lon = 0;
        int n = 0;
        for (List<LatLon> r : outers) {
            for (LatLon p : r) {
                lat += p.lat();
                lon += p.lon();
                n++;
            }
        }
        return n == 0 ? new LatLon(0, 0) : new LatLon(lat / n, lon / n);
    }
}
