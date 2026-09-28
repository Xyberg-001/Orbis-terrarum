package com.berg.orbis.dem;

/** A source of elevation data, queryable by lat/lon, with a declared native resolution. */
public interface DemSource {
    /** Elevation in meters at this coordinate, or NaN if this source has no coverage here. */
    double sampleMeters(double lat, double lon);

    /** Approximate native ground resolution in meters/pixel — lower is more accurate. */
    double nativeResolutionMeters();

    /** Whether this source has real data coverage at this coordinate. */
    boolean hasCoverage(double lat, double lon);
}
