package com.berg.orbis.landmark;

/**
 * Generates a height profile for a domed roof over a circular footprint,
 * plus optional corner minarets — a generic approximation used when a
 * building is tagged as domed (mosque, temple, cathedral) but has no
 * hand-authored schematic in {@link LandmarkRegistry#FAMOUS_LANDMARKS}.
 *
 * This is deliberately simple (a hemisphere, not an onion dome or any
 * specific architectural style) — it exists to stop domed buildings from
 * being flat slabs, not to be historically precise. Real precision comes
 * from schematics for specific landmarks.
 */
public class DomeGenerator {

    /**
     * Returns the additional height (in blocks) the dome contributes at a
     * point (dx, dz) relative to the footprint center, or 0 outside the
     * dome's radius. Add this on top of the building's wall height.
     *
     * @param dx, dz offset from footprint center, in blocks
     * @param domeRadius radius of the dome base, in blocks (typically ~40-60% of the shorter footprint dimension)
     * @param domeHeight height of the dome from its base to its peak, in blocks
     */
    public static double domeHeightAt(double dx, double dz, double domeRadius, double domeHeight) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist >= domeRadius) return 0;
        // Hemisphere profile: height = domeHeight * sqrt(1 - (dist/radius)^2)
        double normalized = dist / domeRadius;
        return domeHeight * Math.sqrt(1 - normalized * normalized);
    }

    /**
     * Returns true if (dx, dz) falls within one of four corner minaret
     * columns placed just inside the footprint corners — a common mosque
     * silhouette element. minaretRadius/footprintHalfWidth/HalfDepth in blocks.
     */
    public static boolean isMinaretColumn(double dx, double dz, double footprintHalfWidth,
                                            double footprintHalfDepth, double minaretRadius) {
        double[] cornersX = {footprintHalfWidth * 0.85, -footprintHalfWidth * 0.85};
        double[] cornersZ = {footprintHalfDepth * 0.85, -footprintHalfDepth * 0.85};
        for (double cx : cornersX) {
            for (double cz : cornersZ) {
                double dist = Math.hypot(dx - cx, dz - cz);
                if (dist <= minaretRadius) return true;
            }
        }
        return false;
    }
}
