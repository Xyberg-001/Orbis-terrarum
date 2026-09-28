package com.berg.orbis.dem;

/** A source of square elevation tiles in the slippy-map (z/x/y) scheme, values in metres. */
public interface TileSource {
    /** @throws RuntimeException if the tile cannot be obtained (network failure, no data). */
    float[][] getTile(int zoom, int x, int y);
}
