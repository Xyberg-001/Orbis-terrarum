package com.berg.orbis.water;

import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.LatLon;
import com.berg.orbis.osm.OsmArea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The beds of lakes and rivers, as depth below the water surface for each column (in blocks), instead of one depth
 * for a whole water body with vertical banks.
 * <ul>
 * <li>A lake surveyed by NVE (Norway) gets its real bed: the smoothest surface through its depth contours and
 * sounded points with the shore at 0 m, and past the deepest contour down to the lake's recorded greatest
 * depth.</li>
 * <li>Any other lake or reservoir is a bowl: its depth grows with the distance from the shore (islands included),
 * reaching the greatest depth at the point furthest from any shore, as GLOBathy builds its beds. The greatest
 * depth is NVE's figure, else GLOBathy's (imported per area), else an estimate from the lake's area.</li>
 * <li>A river drawn as an area is a bowl too, as deep as a river of its width is (see {@link #riverDepthM}).</li>
 * </ul>
 * Everything is computed from a water body's whole outline, never from the part inside one region, so a lake
 * crossing many regions gets one continuous bed.
 */
public final class WaterBeds {

    /** A water body of a region to shape: its code in the raster, kind and OpenStreetMap outline. */
    public record Body(short code, WaterFeature.Kind kind, OsmArea area) {}

    private static final int MAX_GRID = 1024;

    private final CoordinateMapper mapper;
    private final double metresPerBlock;
    private final NveLakes nve;
    private final GlobalLakeDepths global;
    /** Depth contours and soundings outside Norway (Finland, Minnesota, Ontario); null without any. */
    private volatile LakeSurveys surveys;
    private final Map<Long, Shape> shapes = new ConcurrentHashMap<>();

    public WaterBeds(CoordinateMapper mapper, double metresPerBlock, NveLakes nve, GlobalLakeDepths global) {
        this.mapper = mapper;
        this.metresPerBlock = metresPerBlock;
        this.nve = nve;
        this.global = global;
    }

    public WaterBeds withSurveys(LakeSurveys surveys) {
        this.surveys = surveys;
        return this;
    }

    /** Depth (m) of a river of this width (m): the usual hydraulic-geometry relation, about 1.3 m at 12 m wide, 3.3 m at 50 m, 7.5 m at 200 m. */
    /** Exact distance (in cells) from each inside cell to the nearest outside cell; 0 outside. */
    public static float[] distanceInside(boolean[] inside, int w, int h) {
        return DistanceField.edt(inside, w, h);
    }

    public static double riverDepthM(double widthM) {
        return Math.max(0.5, Math.min(25, 0.3 * Math.pow(Math.max(1, widthM), 0.6)));
    }

    /** Fills {@code r.bedDepth} for the given water bodies. */
    public void shape(RegionRaster r, List<Body> bodies) {
        if (bodies.isEmpty()) return;
        Body[] byCode = new Body[r.waters.size() + 1];
        for (Body b : bodies) if (b.code() > 0 && b.code() < byCode.length) byCode[b.code()] = b;
        if (r.bedDepth == null) r.bedDepth = new short[r.stride * r.stride];
        Map<Short, Shape> local = new java.util.HashMap<>();
        for (int dz = 0; dz < r.stride; dz++) {
            for (int dx = 0; dx < r.stride; dx++) {
                int idx = dz * r.stride + dx;
                int code = r.water[idx];
                if (code <= 0 || code >= byCode.length || byCode[code] == null) continue;
                Body b = byCode[code];
                Shape s = local.computeIfAbsent(b.code(), k -> shapeOf(b));
                if (s == null) continue;
                double depthM = s.depthM(r.originX + dx + 0.5, r.originZ + dz + 0.5);
                if (Double.isNaN(depthM)) continue;
                r.bedDepth[idx] = (short) Math.max(1, Math.min(Short.MAX_VALUE, Math.round(depthM / metresPerBlock)));
            }
        }
    }

    // ------------------------------------------------------------------ a water body's shape

    private Shape shapeOf(Body b) {
        Shape cached = shapes.get(b.area().id());
        if (cached != null) return cached;
        Shape s;
        try {
            s = build(b);
        } catch (RuntimeException e) {
            System.err.println("[orbis] Lake bed of " + b.area().id() + " not shaped: " + e);
            return null;
        }
        if (s != null) {
            if (shapes.size() > 24) shapes.clear(); // a surveyed lake holds a depth grid of up to 4 MB
            shapes.put(b.area().id(), s);
        }
        return s;
    }

    private Shape build(Body b) {
        List<double[][]> rings = new ArrayList<>(); // block coordinates, outers and islands alike
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double south = 90, west = 180, north = -90, east = -180;
        List<List<LatLon>> all = new ArrayList<>(b.area().outers());
        all.addAll(b.area().inners());
        for (List<LatLon> ring : all) {
            double[][] pts = new double[ring.size()][];
            for (int i = 0; i < ring.size(); i++) {
                LatLon p = ring.get(i);
                pts[i] = mapper.toBlockExact(p.lat(), p.lon());
                minX = Math.min(minX, pts[i][0]);
                maxX = Math.max(maxX, pts[i][0]);
                minZ = Math.min(minZ, pts[i][1]);
                maxZ = Math.max(maxZ, pts[i][1]);
                south = Math.min(south, p.lat());
                north = Math.max(north, p.lat());
                west = Math.min(west, p.lon());
                east = Math.max(east, p.lon());
            }
            rings.add(pts);
        }
        if (rings.isEmpty() || maxX <= minX || maxZ <= minZ) return null;
        DistanceField field = new DistanceField(rings, minX, minZ, maxX, maxZ);
        double areaKm2 = b.area().approxAreaM2() / 1e6;

        // A lake NVE surveyed: its contours and its greatest depth.
        if (nve != null && b.kind() != WaterFeature.Kind.RIVER && NveLakes.covers(south, west, north, east)) {
            NveLakes.Lake best = null;
            double bestShare = 0.5;
            for (NveLakes.Lake l : nve.lakesIn(south, west, north, east)) {
                double share = field.shareInside(l, mapper);
                if (share > bestShare) {
                    bestShare = share;
                    best = l;
                }
            }
            if (best != null) {
                List<NveLakes.Sounding> sounded = nve.soundings(best.id());
                if (sounded.size() >= 8) return new Surveyed(field, sounded, best.maxDepthM(), rings, mapper);
                if (!Double.isNaN(best.maxDepthM())) return new Bowl(field, best.maxDepthM());
            }
        }
        // Depth contours and soundings of another country's survey (Finland, Minnesota, Ontario): those inside this
        // lake build the same bed as NVE's do.
        LakeSurveys sv = surveys;
        if (sv != null && b.kind() != WaterFeature.Kind.RIVER && sv.covers(south, west, north, east)) {
            List<NveLakes.Sounding> inside = new ArrayList<>();
            boolean deep = false;
            for (NveLakes.Sounding sd : sv.soundings(south, west, north, east)) {
                double[] p = mapper.toBlockExact(sd.lat(), sd.lon());
                if (field.distance(p[0], p[1]) <= 0.5) continue; // outside this water (another lake's, or a few metres off)
                inside.add(sd);
                deep |= sd.depthM() > 0.5;
            }
            if (inside.size() >= 8 && deep) return new Surveyed(field, inside, Double.NaN, rings, mapper);
        }
        if (b.kind() == WaterFeature.Kind.RIVER) {
            return new Bowl(field, riverDepthM(2 * field.inradius() * metresPerBlock));
        }
        // GLOBathy: the lake whose outlet lies on (or just off) this shore and whose area matches.
        if (global != null) {
            double pad = 0.01;
            double bestScore = Double.MAX_VALUE, depth = Double.NaN;
            for (GlobalLakeDepths.Entry e : global.near(south - pad, west - pad, north + pad, east + pad)) {
                double ratio = e.areaKm2() / Math.max(1e-6, areaKm2);
                if (ratio < 1 / 3.0 || ratio > 3.0) continue;
                double[] p = mapper.toBlockExact(e.lat(), e.lon());
                double off = field.distanceOutside(p[0], p[1]) * metresPerBlock;
                if (off > Math.max(150, 0.05 * Math.sqrt(areaKm2 * 1e6))) continue;
                double score = Math.abs(Math.log(ratio)) + off / 500.0;
                if (score < bestScore) {
                    bestScore = score;
                    depth = e.maxDepthM();
                }
            }
            if (!Double.isNaN(depth) && depth > 0) return new Bowl(field, depth);
        }
        return new Bowl(field, estimateMaxDepthM(b.kind(), areaKm2));
    }

    /** Greatest depth (m) of a lake nobody measured: grows with its area (3 m for a small tarn, about 8 m at 1 km², 16 m at 10 km²). */
    static double estimateMaxDepthM(WaterFeature.Kind kind, double areaKm2) {
        if (kind == WaterFeature.Kind.POND) return 2;
        return Math.max(3, Math.min(60, 8 * Math.pow(Math.max(1e-4, areaKm2), 0.3)));
    }

    private interface Shape {
        /** Depth (m) below the surface at a block position, NaN when unknown. */
        double depthM(double x, double z);
    }

    /** Depth grows with the distance from the shore to the greatest depth at the point furthest from it. */
    private record Bowl(DistanceField field, double maxDepthM) implements Shape {
        public double depthM(double x, double z) {
            double rin = field.inradius();
            if (rin <= 0) return maxDepthM;
            double d = field.distance(x, z);
            return Math.max(0.5, maxDepthM * Math.pow(Math.min(1, d / rin), 0.8));
        }
    }

    /**
     * A surveyed bed. The contours (and sounded points) are fixed heights on the lake's grid, the shore is fixed at
     * 0 m, and the bed between them is the smoothest surface through them (the solution of Laplace's equation,
     * solved coarse to fine): it slopes evenly from one contour to the next, never shelves or steps, and follows
     * the contours round bays and headlands. Past the deepest contour, where that surface is flat, it deepens on
     * towards the recorded greatest depth with the distance from that contour.
     */
    private static final class Surveyed implements Shape {
        private final DistanceField field;
        private final float[] depth; // metres, on the field's grid

        Surveyed(DistanceField field, List<NveLakes.Sounding> sounded, double maxDepthM, List<double[][]> shoreRings, CoordinateMapper mapper) {
            this.field = field;
            int w = field.w, h = field.h, n = w * h;
            float[] fixed = new float[n];
            Arrays.fill(fixed, Float.NaN);
            for (int i = 0; i < n; i++) if (field.dist[i] <= 0) fixed[i] = 0f; // land: the shore's height
            double deepest = 0;
            // Each contour as an unbroken line of fixed cells (its points come in order, about 5 m apart): lone fixed
            // points would leave a dimple round each one.
            double[] prev = null;
            double prevDepth = Double.NaN;
            for (NveLakes.Sounding sd : sounded) {
                double[] b = mapper.toBlockExact(sd.lat(), sd.lon());
                double gx = (b[0] - field.minX) / field.cell, gz = (b[1] - field.minZ) / field.cell;
                boolean joined = prev != null && prevDepth == sd.depthM() && Math.hypot(gx - prev[0], gz - prev[1]) * field.cell * 1.0 < 12;
                int steps = joined ? Math.max(1, (int) Math.ceil(Math.hypot(gx - prev[0], gz - prev[1]) * 2)) : 0;
                for (int k = joined ? 1 : 0; k <= steps; k++) {
                    double t = steps == 0 ? 1 : (double) k / steps;
                    int cx = (int) Math.floor(joined ? prev[0] + (gx - prev[0]) * t : gx), cz = (int) Math.floor(joined ? prev[1] + (gz - prev[1]) * t : gz);
                    if (cx < 0 || cz < 0 || cx >= w || cz >= h) continue;
                    int i = cz * w + cx;
                    if (field.dist[i] <= 0) continue; // on land (the two maps differ by a few metres)
                    fixed[i] = (float) sd.depthM();
                }
                deepest = Math.max(deepest, sd.depthM());
                prev = new double[]{gx, gz};
                prevDepth = sd.depthM();
            }
            double max = Double.isNaN(maxDepthM) ? deepest : Math.max(deepest, maxDepthM);
            float[] v = Laplace.solve(fixed, w, h);
            // Past the deepest contour: deeper with the distance from it, to the greatest depth at the furthest point.
            if (max > deepest + 0.5 && deepest > 0) {
                boolean[] basin = new boolean[n];
                for (int i = 0; i < n; i++) basin[i] = field.dist[i] > 0 && Float.isNaN(fixed[i]) && v[i] >= deepest - 0.5;
                float[] into = DistanceField.edt(basin, w, h);
                float far = 0;
                for (float f : into) far = Math.max(far, f);
                if (far > 0) {
                    for (int i = 0; i < n; i++) {
                        if (basin[i]) v[i] = (float) (deepest + (max - deepest) * Math.pow(into[i] / far, 0.8));
                    }
                }
            }
            for (int i = 0; i < n; i++) v[i] = (float) Math.max(0, Math.min(max, v[i]));
            this.depth = v;
        }

        public double depthM(double x, double z) {
            return Math.max(0.5, field.sample(depth, x, z));
        }
    }

    /** Laplace's equation over a grid with fixed cells (NaN = free), solved coarse to fine by over-relaxation. */
    private static final class Laplace {
        static float[] solve(float[] fixed, int w, int h) {
            if (Math.max(w, h) > 96) {
                // Solve a half-size grid first and start from it (a fine grid alone needs thousands of sweeps).
                int cw = (w + 1) / 2, ch = (h + 1) / 2;
                float[] cf = new float[cw * ch];
                for (int z = 0; z < ch; z++) {
                    for (int x = 0; x < cw; x++) {
                        double sum = 0;
                        int cnt = 0;
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int fx = 2 * x + dx, fz = 2 * z + dz;
                                if (fx >= w || fz >= h) continue;
                                float f = fixed[fz * w + fx];
                                if (!Float.isNaN(f)) {
                                    sum += f;
                                    cnt++;
                                }
                            }
                        }
                        cf[z * cw + x] = cnt == 0 ? Float.NaN : (float) (sum / cnt);
                    }
                }
                float[] coarse = solve(cf, cw, ch);
                float[] v = new float[w * h];
                for (int z = 0; z < h; z++) {
                    for (int x = 0; x < w; x++) {
                        float f = fixed[z * w + x];
                        v[z * w + x] = Float.isNaN(f) ? coarse[Math.min(ch - 1, z / 2) * cw + Math.min(cw - 1, x / 2)] : f;
                    }
                }
                relax(v, fixed, w, h, 60);
                return v;
            }
            float[] v = new float[w * h];
            double mean = 0;
            int cnt = 0;
            for (float f : fixed) {
                if (!Float.isNaN(f)) {
                    mean += f;
                    cnt++;
                }
            }
            float m = cnt == 0 ? 0 : (float) (mean / cnt);
            for (int i = 0; i < v.length; i++) v[i] = Float.isNaN(fixed[i]) ? m : fixed[i];
            relax(v, fixed, w, h, 600);
            return v;
        }

        private static void relax(float[] v, float[] fixed, int w, int h, int sweeps) {
            final float omega = 1.85f;
            for (int s = 0; s < sweeps; s++) {
                for (int z = 0; z < h; z++) {
                    for (int x = 0; x < w; x++) {
                        int i = z * w + x;
                        if (!Float.isNaN(fixed[i])) continue;
                        float sum = (x > 0 ? v[i - 1] : 0) + (x < w - 1 ? v[i + 1] : 0) + (z > 0 ? v[i - w] : 0) + (z < h - 1 ? v[i + w] : 0);
                        v[i] += omega * (sum * 0.25f - v[i]);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ distance to the shore

    /**
     * Distance to the nearest shore (outer edge or island) over a lake's whole outline: the outline filled into a
     * grid of at most {@value #MAX_GRID} cells a side (one block a cell for lakes up to a kilometre across) and an
     * exact Euclidean distance transform over it.
     */
    private static final class DistanceField {
        final double minX, minZ, cell;
        final int w, h;
        final float[] dist; // grid cells; 0 outside the water
        final List<double[][]> rings;
        private final double inradius;

        DistanceField(List<double[][]> rings, double minX, double minZ, double maxX, double maxZ) {
            this.rings = rings;
            double span = Math.max(maxX - minX, maxZ - minZ);
            this.cell = Math.max(1.0, span / MAX_GRID);
            this.minX = minX - cell;
            this.minZ = minZ - cell;
            this.w = (int) Math.ceil((maxX - minX) / cell) + 3;
            this.h = (int) Math.ceil((maxZ - minZ) / cell) + 3;
            boolean[] inside = new boolean[w * h];
            // Even-odd scanline fill at cell centres.
            double[] xs = new double[64];
            for (int gz = 0; gz < h; gz++) {
                double z = this.minZ + (gz + 0.5) * cell;
                int n = 0;
                for (double[][] ring : rings) {
                    for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                        if ((ring[i][1] > z) != (ring[j][1] > z)) {
                            if (n == xs.length) xs = Arrays.copyOf(xs, n * 2);
                            xs[n++] = ring[i][0] + (z - ring[i][1]) / (ring[j][1] - ring[i][1]) * (ring[j][0] - ring[i][0]);
                        }
                    }
                }
                Arrays.sort(xs, 0, n);
                for (int p = 0; p + 1 < n; p += 2) {
                    int a = (int) Math.ceil((xs[p] - this.minX) / cell - 0.5), bnd = (int) Math.floor((xs[p + 1] - this.minX) / cell - 0.5);
                    for (int gx = Math.max(0, a); gx <= Math.min(w - 1, bnd); gx++) inside[gz * w + gx] = true;
                }
            }
            dist = edt(inside, w, h);
            double m = 0;
            for (float f : dist) m = Math.max(m, f);
            inradius = m * cell;
        }

        double inradius() {
            return inradius;
        }

        /** Distance (blocks) from a point inside the water to the nearest shore, bilinear over the grid. */
        double distance(double x, double z) {
            return sample(dist, x, z) * cell;
        }

        /** A value on this grid at a block position, bilinear. */
        double sample(float[] grid, double x, double z) {
            double fx = (x - minX) / cell - 0.5, fz = (z - minZ) / cell - 0.5;
            int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
            double tx = fx - x0, tz = fz - z0;
            return at(grid, x0, z0) * (1 - tx) * (1 - tz) + at(grid, x0 + 1, z0) * tx * (1 - tz)
                    + at(grid, x0, z0 + 1) * (1 - tx) * tz + at(grid, x0 + 1, z0 + 1) * tx * tz;
        }

        private double at(float[] grid, int x, int z) {
            if (x < 0 || z < 0 || x >= w || z >= h) return 0;
            return grid[z * w + x];
        }

        /** How far (blocks) a point lies outside the water (0 inside), from the outline itself. */
        double distanceOutside(double x, double z) {
            if (distance(x, z) > 0.5) return 0;
            double best = Double.MAX_VALUE;
            for (double[][] ring : rings) {
                for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                    best = Math.min(best, segDist(x, z, ring[j], ring[i]));
                }
            }
            return best;
        }

        /** Share of the water's grid cells that lie inside a surveyed lake's outline. */
        double shareInside(NveLakes.Lake lake, CoordinateMapper mapper) {
            int total = 0, in = 0;
            int step = Math.max(1, Math.max(w, h) / 24);
            for (int gz = 0; gz < h; gz += step) {
                for (int gx = 0; gx < w; gx += step) {
                    if (dist[gz * w + gx] <= 0) continue;
                    total++;
                    double[] ll = mapper.toLatLonExact(minX + (gx + 0.5) * cell, minZ + (gz + 0.5) * cell);
                    if (NveLakes.contains(lake, ll[0], ll[1])) in++;
                }
            }
            return total == 0 ? 0 : (double) in / total;
        }

        private static double segDist(double x, double z, double[] a, double[] b) {
            double vx = b[0] - a[0], vz = b[1] - a[1];
            double len2 = vx * vx + vz * vz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * vx + (z - a[1]) * vz) / len2));
            return Math.hypot(x - (a[0] + t * vx), z - (a[1] + t * vz));
        }

        /** Exact Euclidean distance (in cells) from each inside cell to the nearest outside cell (Felzenszwalb-Huttenlocher). */
        static float[] edt(boolean[] inside, int w, int h) {
            final double inf = 1e20;
            double[] f = new double[Math.max(w, h)];
            double[] g = new double[w * h];
            double[] d = new double[Math.max(w, h)];
            int[] v = new int[Math.max(w, h)];
            double[] zz = new double[Math.max(w, h) + 1];
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) f[y] = inside[y * w + x] ? inf : 0;
                dt1(f, h, d, v, zz);
                for (int y = 0; y < h; y++) g[y * w + x] = d[y];
            }
            float[] out = new float[w * h];
            for (int y = 0; y < h; y++) {
                System.arraycopy(g, y * w, f, 0, w);
                dt1(f, w, d, v, zz);
                for (int x = 0; x < w; x++) out[y * w + x] = inside[y * w + x] ? (float) Math.sqrt(d[x]) : 0f;
            }
            return out;
        }

        private static void dt1(double[] f, int n, double[] d, int[] v, double[] z) {
            int k = 0;
            v[0] = 0;
            z[0] = -1e30;
            z[1] = 1e30;
            for (int q = 1; q < n; q++) {
                double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
                while (s <= z[k]) {
                    k--;
                    s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
                }
                k++;
                v[k] = q;
                z[k] = s;
                z[k + 1] = 1e30;
            }
            k = 0;
            for (int q = 0; q < n; q++) {
                while (z[k + 1] < q) k++;
                d[q] = (double) (q - v[k]) * (q - v[k]) + f[v[k]];
            }
        }
    }
}
