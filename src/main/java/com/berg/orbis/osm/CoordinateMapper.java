package com.berg.orbis.osm;

import java.util.Locale;

/**
 * Maps real-world lat/lon to Minecraft block X/Z and back.
 *
 * Two projections, both centred on the chosen origin:
 * <ul>
 *   <li>{@code EQUIRECTANGULAR}: metres east/north of the origin with the
 *   east-west scale fixed at the origin's latitude. Exact enough for a city;
 *   a country spanning 13 degrees of latitude comes out up to 25 % too wide
 *   or narrow at its ends.</li>
 *   <li>{@code TRANSVERSE_MERCATOR}: conformal, with the origin's meridian as
 *   the central meridian, so shapes are right everywhere and the scale error
 *   stays under one percent even across all of Norway (the projection UTM
 *   is built on).</li>
 * </ul>
 * Minecraft convention: +X = east, +Z = south (so north is -Z).
 */
public class CoordinateMapper {

    public enum Projection {
        EQUIRECTANGULAR, TRANSVERSE_MERCATOR;

        public static Projection of(String s) {
            if (s == null) return EQUIRECTANGULAR;
            String v = s.trim().toLowerCase(Locale.ROOT);
            return v.startsWith("transverse") || v.equals("tm") || v.equals("utm") ? TRANSVERSE_MERCATOR : EQUIRECTANGULAR;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final double originLat;
    private final double originLon;
    /** Real-world metres represented by one Minecraft block. 1.0 = true 1:1 scale. */
    private final double metersPerBlock;
    private final double cosOrigin;
    private final Projection projection;
    private final double phi0, lambda0;

    public CoordinateMapper(double originLat, double originLon, double metersPerBlock) {
        this(originLat, originLon, metersPerBlock, Projection.EQUIRECTANGULAR);
    }

    public CoordinateMapper(double originLat, double originLon, double metersPerBlock, Projection projection) {
        this.originLat = originLat;
        this.originLon = originLon;
        this.metersPerBlock = metersPerBlock;
        this.cosOrigin = Math.cos(Math.toRadians(originLat));
        this.projection = projection == null ? Projection.EQUIRECTANGULAR : projection;
        this.phi0 = Math.toRadians(originLat);
        this.lambda0 = Math.toRadians(originLon);
    }

    /** Exact (fractional) block coordinates -- use for polygon geometry. */
    public double[] toBlockExact(double lat, double lon) {
        double x, z;
        if (projection == Projection.TRANSVERSE_MERCATOR) {
            double phi = Math.toRadians(lat);
            double dl = Math.toRadians(lon - originLon);
            double b = Math.cos(phi) * Math.sin(dl);
            if (b > 0.999999) b = 0.999999;
            if (b < -0.999999) b = -0.999999;
            x = EARTH_RADIUS_M * 0.5 * Math.log((1 + b) / (1 - b));
            double y = EARTH_RADIUS_M * (Math.atan2(Math.tan(phi), Math.cos(dl)) - phi0);
            z = -y;
        } else {
            double dLat = Math.toRadians(lat - originLat);
            double dLon = Math.toRadians(lon - originLon);
            x = dLon * EARTH_RADIUS_M * cosOrigin;
            z = -dLat * EARTH_RADIUS_M;
        }
        return new double[]{x / metersPerBlock, z / metersPerBlock};
    }

    public int[] toBlock(double lat, double lon) {
        double[] e = toBlockExact(lat, lon);
        return new int[]{(int) Math.floor(e[0]), (int) Math.floor(e[1])};
    }

    /** Lat/lon of the CENTRE of a block column. */
    public double[] toLatLon(int blockX, int blockZ) {
        return toLatLonExact(blockX + 0.5, blockZ + 0.5);
    }

    public double[] toLatLonExact(double blockX, double blockZ) {
        double x = blockX * metersPerBlock;
        double z = blockZ * metersPerBlock;
        if (projection == Projection.TRANSVERSE_MERCATOR) {
            double y = -z;
            double d = y / EARTH_RADIUS_M + phi0;
            double xr = x / EARTH_RADIUS_M;
            double phi = Math.asin(Math.sin(d) / Math.cosh(xr));
            double lambda = lambda0 + Math.atan2(Math.sinh(xr), Math.cos(d));
            return new double[]{Math.toDegrees(phi), Math.toDegrees(lambda)};
        }
        double dLat = Math.toDegrees(-z / EARTH_RADIUS_M);
        double dLon = Math.toDegrees(x / (EARTH_RADIUS_M * cosOrigin));
        return new double[]{originLat + dLat, originLon + dLon};
    }

    public double metersPerBlock() {
        return metersPerBlock;
    }

    public double originLat() {
        return originLat;
    }

    public double originLon() {
        return originLon;
    }

    public Projection projection() {
        return projection;
    }

    /** Converts a real-world length in metres to blocks. */
    public double blocks(double meters) {
        return meters / metersPerBlock;
    }
}
