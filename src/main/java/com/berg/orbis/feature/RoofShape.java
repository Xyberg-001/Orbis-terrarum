package com.berg.orbis.feature;

import java.util.Locale;

/** OSM roof:shape values we know how to draw. */
public enum RoofShape {
    FLAT, GABLED, HIPPED, HALF_HIPPED, PYRAMIDAL, SKILLION, GAMBREL, MANSARD, ROUND, DOME, ONION, CONE, SALTBOX;

    public static RoofShape parse(String value, RoofShape fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "flat" -> FLAT;
            case "gabled", "gable", "pitched" -> GABLED;
            case "hipped", "hip" -> HIPPED;
            case "half-hipped", "half_hipped", "jerkinhead" -> HALF_HIPPED;
            case "pyramidal", "pyramid" -> PYRAMIDAL;
            case "skillion", "shed", "lean_to", "mono-pitched", "monopitch" -> SKILLION;
            case "gambrel" -> GAMBREL;
            case "mansard" -> MANSARD;
            case "round", "barrel", "vault", "quarter-round" -> ROUND;
            case "dome", "hemisphere" -> DOME;
            case "onion" -> ONION;
            case "cone", "conical", "spire" -> CONE;
            case "saltbox" -> SALTBOX;
            default -> fallback;
        };
    }

    public boolean isPitched() {
        return this != FLAT;
    }
}
