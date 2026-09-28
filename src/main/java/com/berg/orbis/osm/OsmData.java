package com.berg.orbis.osm;

import java.util.List;

/** Everything Overpass returned for one bounding box, split by geometry type. */
public record OsmData(List<OsmNode> nodes, List<OsmWay> ways, List<OsmArea> areas) {
    public static OsmData empty() {
        return new OsmData(List.of(), List.of(), List.of());
    }
}
