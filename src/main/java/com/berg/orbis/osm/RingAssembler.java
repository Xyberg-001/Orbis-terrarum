package com.berg.orbis.osm;

import java.util.ArrayList;
import java.util.List;

/**
 * Stitches multipolygon member ways into closed rings. Large lakes,
 * coastlines and forests in OSM are frequently split into dozens of ways
 * that only form a closed outline once joined end-to-end -- Overpass returns
 * them as separate member geometries, so we must join them ourselves.
 */
public final class RingAssembler {

    private RingAssembler() {}

    public static List<List<LatLon>> assemble(List<List<LatLon>> fragments) {
        List<List<LatLon>> open = new ArrayList<>();
        List<List<LatLon>> closed = new ArrayList<>();
        for (List<LatLon> f : fragments) {
            if (f.size() < 2) continue;
            if (f.size() > 2 && f.get(0).equals(f.get(f.size() - 1))) closed.add(new ArrayList<>(f));
            else open.add(new ArrayList<>(f));
        }

        while (!open.isEmpty()) {
            List<LatLon> ring = open.remove(open.size() - 1);
            boolean progress = true;
            while (progress && !ring.get(0).equals(ring.get(ring.size() - 1))) {
                progress = false;
                LatLon tail = ring.get(ring.size() - 1);
                LatLon head = ring.get(0);
                for (int i = 0; i < open.size(); i++) {
                    List<LatLon> cand = open.get(i);
                    LatLon cHead = cand.get(0), cTail = cand.get(cand.size() - 1);
                    if (near(tail, cHead)) {
                        ring.addAll(cand.subList(1, cand.size()));
                    } else if (near(tail, cTail)) {
                        List<LatLon> rev = new ArrayList<>(cand);
                        java.util.Collections.reverse(rev);
                        ring.addAll(rev.subList(1, rev.size()));
                    } else if (near(head, cTail)) {
                        List<LatLon> merged = new ArrayList<>(cand);
                        merged.addAll(ring.subList(1, ring.size()));
                        ring = merged;
                    } else if (near(head, cHead)) {
                        List<LatLon> rev = new ArrayList<>(cand);
                        java.util.Collections.reverse(rev);
                        rev.addAll(ring.subList(1, ring.size()));
                        ring = rev;
                    } else {
                        continue;
                    }
                    open.remove(i);
                    progress = true;
                    break;
                }
            }
            if (ring.size() >= 3) {
                if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                    // Unclosed after exhausting fragments (data clipped by the
                    // bbox). Close it explicitly -- a slightly wrong edge along
                    // the region border beats losing the whole lake.
                    ring.add(ring.get(0));
                }
                closed.add(ring);
            }
        }
        return closed;
    }

    private static boolean near(LatLon a, LatLon b) {
        return Math.abs(a.lat() - b.lat()) < 1e-7 && Math.abs(a.lon() - b.lon()) < 1e-7;
    }
}
