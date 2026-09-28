package com.berg.orbis.net;

import java.util.ArrayList;
import java.util.List;

/**
 * The outline of a named area (a country, region or city) as one or more
 * polygons of [lat, lon] vertices, plus its bounding box.
 */
public record AreaOutline(String name, List<double[][]> polygons, double south, double west, double north, double east) {

    public static AreaOutline of(String name, List<double[][]> polygons) {
        double s = 90, w = 180, n = -90, e = -180;
        for (double[][] poly : polygons) {
            for (double[] p : poly) {
                s = Math.min(s, p[0]);
                n = Math.max(n, p[0]);
                w = Math.min(w, p[1]);
                e = Math.max(e, p[1]);
            }
        }
        return new AreaOutline(name, polygons, s, w, n, e);
    }

    /** Only the largest polygon (the mainland of a country with islands or overseas parts). */
    public AreaOutline largestOnly() {
        double[][] best = null;
        double bestArea = -1;
        for (double[][] poly : polygons) {
            double a = areaKm2(poly);
            if (a > bestArea) {
                bestArea = a;
                best = poly;
            }
        }
        List<double[][]> one = new ArrayList<>();
        if (best != null) one.add(best);
        return of(name, one);
    }

    /**
     * Polygon area in km² on the sphere (the spherical-excess shoelace), right for a city and for Russia alike;
     * the flat version with one latitude's scale overstated large countries (Russia 19.5 instead of 17 million).
     */
    public static double areaKm2(double[][] ring) {
        if (ring.length < 3) return 0;
        final double r = 6371.0088;
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            double[] p = ring[i], q = ring[(i + 1) % ring.length];
            double dLon = Math.toRadians(q[1] - p[1]);
            if (dLon > Math.PI) dLon -= 2 * Math.PI;
            if (dLon < -Math.PI) dLon += 2 * Math.PI;
            sum += dLon * (2 + Math.sin(Math.toRadians(p[0])) + Math.sin(Math.toRadians(q[0])));
        }
        return Math.abs(sum) * r * r / 2.0;
    }

    public double totalKm2() {
        double a = 0;
        for (double[][] poly : polygons) a += areaKm2(poly);
        return a;
    }

    public int vertexCount() {
        int n = 0;
        for (double[][] poly : polygons) n += poly.length;
        return n;
    }
}
