package com.berg.orbis.dem;

/**
 * WGS84 latitude/longitude to the national map grids that some surface models are only served in. Closed formulas,
 * no projection library: each is good to about a metre, which is what a 0.5-1 m surface model under a building
 * needs (the datum shifts are inside that, or built into the formula).
 */
public final class NationalGrids {

    private NationalGrids() {}

    /** {easting, northing} in the given grid ("EPSG:2056", "EPSG:3979"), or null for a grid this class does not know. */
    public static double[] project(String crs, double lat, double lon) {
        return switch (crs) {
            case "EPSG:2056" -> swissLv95(lat, lon);
            case "EPSG:3979" -> canadaAtlasLambert(lat, lon);
            default -> null;
        };
    }

    public static boolean knows(String crs) {
        return "EPSG:2056".equals(crs) || "EPSG:3979".equals(crs);
    }

    /** Switzerland, CH1903+/LV95: swisstopo's approximate formulas (WGS84 to LV95 directly, about 1 m). */
    static double[] swissLv95(double lat, double lon) {
        double p = (lat * 3600 - 169028.66) / 10000, l = (lon * 3600 - 26782.5) / 10000;
        double e = 2600072.37 + 211455.93 * l - 10938.51 * l * p - 0.36 * l * p * p - 44.54 * l * l * l;
        double n = 1200147.07 + 308807.95 * p + 3745.25 * l * l + 76.63 * p * p - 194.56 * l * l * p + 119.79 * p * p * p;
        return new double[]{e, n};
    }

    // Canada Atlas Lambert (NAD83(CSRS), within a metre or two of WGS84): Lambert conformal conic, 2 standard
    // parallels 49 and 77 degrees, origin 49 N 95 W, GRS80.
    private static final double A = 6378137.0, F = 1 / 298.257222101, E = Math.sqrt(2 * F - F * F);
    private static final double LAT1 = Math.toRadians(49), LAT2 = Math.toRadians(77), LAT0 = Math.toRadians(49), LON0 = Math.toRadians(-95);
    private static final double N_, AF, RHO0;

    static {
        double m1 = m(LAT1), m2 = m(LAT2), t1 = t(LAT1), t2 = t(LAT2);
        N_ = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        AF = A * m1 / (N_ * Math.pow(t1, N_));
        RHO0 = AF * Math.pow(t(LAT0), N_);
    }

    private static double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - E * E * s * s);
    }

    private static double t(double phi) {
        double s = Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - E * s) / (1 + E * s), E / 2);
    }

    static double[] canadaAtlasLambert(double lat, double lon) {
        double rho = AF * Math.pow(t(Math.toRadians(lat)), N_);
        double theta = N_ * (Math.toRadians(lon) - LON0);
        return new double[]{rho * Math.sin(theta), RHO0 - rho * Math.cos(theta)};
    }
}
