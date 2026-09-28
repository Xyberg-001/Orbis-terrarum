package com.berg.orbis.osm;

import java.util.Map;
import java.util.Set;

/** Small helpers for interpreting OSM tag values. */
public final class OsmTags {

    private OsmTags() {}

    private static final Set<String> AREA_KEYS = Set.of(
            "building", "building:part", "landuse", "leisure", "amenity", "natural", "water",
            "place", "aeroway", "military", "tourism", "shop", "office", "historic", "man_made", "power");

    private static final Set<String> LINEAR_NATURAL = Set.of("coastline", "tree_row", "cliff", "ridge", "valley", "arete");
    private static final Set<String> LINEAR_MAN_MADE = Set.of("embankment", "pipeline", "cutline", "dyke", "goods_conveyor", "breakwater", "groyne");

    /** Whether a CLOSED way with these tags should be treated as a polygon rather than a ring-shaped line. */
    public static boolean isAreaTagged(Map<String, String> tags) {
        String area = tags.get("area");
        if ("no".equals(area)) return false;
        if ("yes".equals(area)) return true;
        if (tags.containsKey("highway") || tags.containsKey("barrier") || tags.containsKey("waterway")
                || tags.containsKey("railway") || tags.containsKey("route")) {
            return false;
        }
        String natural = tags.get("natural");
        if (natural != null && LINEAR_NATURAL.contains(natural)) return false;
        String manMade = tags.get("man_made");
        if (manMade != null && LINEAR_MAN_MADE.contains(manMade)) return false;
        if ("line".equals(tags.get("power")) || "minor_line".equals(tags.get("power"))) return false;
        for (String k : AREA_KEYS) {
            if (tags.containsKey(k)) return true;
        }
        return false;
    }

    public static boolean isTrue(String v) {
        return v != null && !"no".equals(v) && !"false".equals(v) && !"0".equals(v);
    }

    public static boolean has(Map<String, String> tags, String key) {
        String v = tags.get(key);
        return v != null && !"no".equals(v);
    }

    public static String get(Map<String, String> tags, String key, String def) {
        String v = tags.get(key);
        return v == null ? def : v;
    }

    /**
     * Parses the leading number of a tag value, tolerating units ("12 m",
     * "40'", "3,5"). Feet are converted to metres. Returns fallback if no
     * number is found.
     */
    public static double parseLength(String s, double fallback) {
        if (s == null) return fallback;
        String t = s.trim().replace(',', '.');
        StringBuilder num = new StringBuilder();
        boolean seenDigit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c) || (c == '.' && seenDigit) || (c == '-' && num.length() == 0)) {
                num.append(c);
                if (Character.isDigit(c)) seenDigit = true;
            } else if (seenDigit) {
                break;
            }
        }
        if (!seenDigit) return fallback;
        try {
            double v = Double.parseDouble(num.toString());
            String rest = t.substring(num.length()).trim().toLowerCase();
            if (rest.startsWith("'") || rest.startsWith("ft") || rest.startsWith("feet")) v *= 0.3048;
            else if (rest.startsWith("km")) v *= 1000.0;
            else if (rest.startsWith("mi")) v *= 1609.344;
            return v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return (int) Math.round(parseLength(s, fallback));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
