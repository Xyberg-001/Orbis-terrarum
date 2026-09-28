package com.berg.orbis.osm;

import java.util.List;
import java.util.Map;

/** A linear OSM feature: road, path, railway, waterway, barrier, coastline, power line, ... */
public record OsmWay(long id, List<LatLon> points, Map<String, String> tags) {
    public boolean isClosed() {
        return points.size() > 2 && points.get(0).equals(points.get(points.size() - 1));
    }
}
