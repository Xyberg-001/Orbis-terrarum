package com.berg.orbis.feature;

/**
 * Small point/line features placed on top of the finished surface during
 * the decoration step (trees, lamps, fences, ...). One byte per column.
 */
public enum DecorType {
    NONE,
    TREE,            // decorData = species ordinal, or 0 for "pick by biome"
    TREE_ROW,
    BUSH,
    LAMP,
    FENCE,           // decorData = fence material variant
    WALL,
    HEDGE,
    RETAINING_WALL,
    CITY_WALL,
    GUARD_RAIL,
    BOLLARD,
    POWER_TOWER,
    POWER_POLE,
    BENCH,
    FOUNTAIN,
    FLAGPOLE,
    MAST,
    LIGHTHOUSE,
    BRIDGE_PILLAR,
    TRAFFIC_SIGNAL,
    CROSSING,
    BUS_STOP,
    SHELTER,
    WASTE_BASKET,
    CROSS,           // cemetery / church grave marker
    KERB,
    TRAIN_STOP,      // railway station / tram stop node: a minecart waits on the nearest rail, name on a sign
    EMBEDDED_RAIL;   // rail centreline running inside a street: decorData = direction octant | 8 for a powered-rail spot

    private static final DecorType[] VALUES = values();

    public static DecorType byCode(int code) {
        return code < 0 || code >= VALUES.length ? NONE : VALUES[code];
    }

    public byte code() {
        return (byte) ordinal();
    }
}
