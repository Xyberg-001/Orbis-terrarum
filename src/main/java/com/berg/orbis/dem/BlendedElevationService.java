package com.berg.orbis.dem;

import java.util.List;

/**
 * Picks the best available elevation source per coordinate. Sources are
 * tried in the order given; the first one that both claims coverage and
 * returns a real (non-NaN) value wins. Pass sources ordered from most to
 * least accurate — e.g. [HighResDemSource, GlobalDemSource].
 */
public class BlendedElevationService {

    private final List<DemSource> sourcesByPriority;
    private volatile DemSource bathymetry;
    private volatile double maxDepthMeters = 300;

    /**
     * A sea-floor source consulted wherever the land sources say the ground is at or below sea level (they
     * report 0 over water): its depth, capped so the floor stays inside the dimension, replaces the flat 0.
     */
    public void setBathymetry(DemSource source, double maxDepthMeters) {
        this.bathymetry = source;
        this.maxDepthMeters = maxDepthMeters;
    }

    public BlendedElevationService(List<DemSource> sourcesByPriority) {
        if (sourcesByPriority.isEmpty()) {
            throw new IllegalArgumentException("Need at least one DEM source");
        }
        this.sourcesByPriority = sourcesByPriority;
    }

    public double sampleMeters(double lat, double lon) {
        double land = landMeters(lat, lon);
        DemSource sea = bathymetry;
        if (sea != null && (Double.isNaN(land) || land <= 0.5)) {
            double depth;
            try {
                depth = sea.sampleMeters(lat, lon);
            } catch (RuntimeException e) {
                depth = Double.NaN;
            }
            if (!Double.isNaN(depth) && depth < 0) return Math.max(depth, -maxDepthMeters);
        }
        return land;
    }

    private double landMeters(double lat, double lon) {
        for (DemSource source : sourcesByPriority) {
            if (!source.hasCoverage(lat, lon)) continue;
            double v = source.sampleMeters(lat, lon);
            if (!Double.isNaN(v)) return v;
        }
        // Last resort: fall back to the lowest-priority source even if it
        // claimed no coverage, rather than returning nothing.
        return sourcesByPriority.get(sourcesByPriority.size() - 1).sampleMeters(lat, lon);
    }

    /** Which resolution the winning source for this coordinate provides, for diagnostics/LOD decisions. */
    public double resolutionAt(double lat, double lon) {
        for (DemSource source : sourcesByPriority) {
            if (source.hasCoverage(lat, lon)) return source.nativeResolutionMeters();
        }
        return sourcesByPriority.get(sourcesByPriority.size() - 1).nativeResolutionMeters();
    }
}
