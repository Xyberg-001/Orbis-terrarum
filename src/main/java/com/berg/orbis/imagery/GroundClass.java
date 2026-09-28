package com.berg.orbis.imagery;

/** What an aerial-imagery pixel most likely shows. Stored as a byte per column. */
public enum GroundClass {
    UNKNOWN, GRASS, TREE_CANOPY, PAVED_LIGHT, PAVED_DARK, BARE_SOIL, SAND, ROCK, SNOW, WATER;

    private static final GroundClass[] VALUES = values();

    public static GroundClass byCode(int code) {
        return code < 0 || code >= VALUES.length ? UNKNOWN : VALUES[code];
    }

    public byte code() {
        return (byte) ordinal();
    }

    /**
     * Heuristic RGB classifier tuned for orthophotos: vegetation is green
     * and saturated (dark = canopy, bright = lawn), pavement is grey, soil
     * and sand are warm hues, snow is bright and neutral, water is blue.
     */
    public static GroundClass classify(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double v = max;
        double s = max < 1e-6 ? 0 : (max - min) / max;
        double delta = max - min;
        double h = 0;
        if (delta > 1e-6) {
            if (max == r) h = 60 * (((g - b) / delta) % 6);
            else if (max == g) h = 60 * (((b - r) / delta) + 2);
            else h = 60 * (((r - g) / delta) + 4);
            if (h < 0) h += 360;
        }
        // Vegetation index: green clearly above red and blue.
        double greenness = g - Math.max(r, b);
        if (greenness > 0.04 && s > 0.15 && h > 60 && h < 170) {
            return v < 0.42 ? TREE_CANOPY : GRASS;
        }
        if (v > 0.9 && s < 0.12) return SNOW;
        if (s < 0.16) {
            if (v < 0.22) return PAVED_DARK;        // asphalt / deep shadow
            if (v < 0.55) return PAVED_DARK;
            return PAVED_LIGHT;                     // concrete, light roofs
        }
        if (h >= 180 && h <= 260 && s > 0.25 && v > 0.15) return WATER;
        if (h >= 10 && h <= 55 && s > 0.18) {
            if (v > 0.72 && s < 0.45) return SAND;
            return BARE_SOIL;
        }
        if (v < 0.25) return PAVED_DARK;
        if (s < 0.28 && v > 0.45) return ROCK;
        return UNKNOWN;
    }
}
