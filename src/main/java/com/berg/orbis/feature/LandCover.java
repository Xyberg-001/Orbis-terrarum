package com.berg.orbis.feature;

/**
 * Ground-cover classes derived from OSM landuse/natural/leisure/amenity
 * polygons. Stored as a byte per column in {@link RegionRaster}, so keep this
 * under 128 entries and never reorder existing ones casually (ordinal is
 * the on-raster code, though rasters are never persisted to disk).
 */
public enum LandCover {
    NONE,
    GRASS,
    MEADOW,
    PARK,
    GARDEN,
    FOREST,            // unknown leaf type
    FOREST_CONIFER,
    FOREST_BROADLEAF,
    SCRUB,
    HEATH,
    FARMLAND,
    ORCHARD,
    VINEYARD,
    ALLOTMENTS,
    RESIDENTIAL,
    COMMERCIAL,
    RETAIL,
    INDUSTRIAL,
    PARKING,
    PITCH,
    PITCH_ARTIFICIAL,
    PITCH_HARD,
    PLAYGROUND,
    CEMETERY,
    SAND,
    BEACH,
    BARE_ROCK,
    SCREE,
    GRAVEL_AREA,
    WETLAND,
    MUD,
    GLACIER,
    CONSTRUCTION,
    QUARRY,
    LANDFILL,
    PEDESTRIAN,
    SQUARE,
    RAILWAY_LAND,
    PIER,
    GOLF,
    SCHOOL,
    HOSPITAL,
    MILITARY,
    AIRPORT_APRON,
    RUNWAY,
    REEF,
    GRASS_SPORT,
    SALT_MARSH,
    ROCK_OUTCROP,
    MARKETPLACE,       // amenity=marketplace: a paved square with a wandering trader
    MARINA;            // leisure=marina: moored boats on the water part, a quay on the land part

    private static final LandCover[] VALUES = values();

    public static LandCover byCode(int code) {
        return code < 0 || code >= VALUES.length ? NONE : VALUES[code];
    }

    public byte code() {
        return (byte) ordinal();
    }

    public boolean isForest() {
        return this == FOREST || this == FOREST_CONIFER || this == FOREST_BROADLEAF;
    }

    public boolean isPaved() {
        return this == PARKING || this == PEDESTRIAN || this == SQUARE || this == AIRPORT_APRON
                || this == RUNWAY || this == PITCH_HARD || this == PIER || this == MARKETPLACE || this == MARINA;
    }

    /** Whether procedural trees may be scattered over this cover. */
    public boolean allowsTrees() {
        return this == NONE || this == GRASS || this == MEADOW || this == PARK || this == GARDEN || isForest()
                || this == SCRUB || this == HEATH || this == ORCHARD || this == RESIDENTIAL || this == CEMETERY
                || this == GOLF || this == SCHOOL || this == HOSPITAL || this == WETLAND;
    }
}
