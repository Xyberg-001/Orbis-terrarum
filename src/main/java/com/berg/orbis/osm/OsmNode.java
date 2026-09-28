package com.berg.orbis.osm;

import java.util.Map;

/** A tagged OSM point feature (tree, street lamp, power tower, ...). */
public record OsmNode(long id, LatLon pos, Map<String, String> tags) {}
