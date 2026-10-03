package com.berg.orbis.osm;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.DecorType;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import com.berg.orbis.feature.RoofShape;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.imagery.GroundClass;
import com.berg.orbis.imagery.ImageryProvider;
import com.berg.orbis.landmark.DomeGenerator;
import com.berg.orbis.render.BlockPalette;
import com.berg.orbis.landmark.LandmarkRegistry;
import com.berg.orbis.render.Materials;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the vector OSM data for one region into the per-column lookup
 * tables in {@link RegionRaster}. All the geometry (polygon fills, thick
 * lines, coastline flood fill, roof profiles, building edges/doors) happens
 * here, once per region, so the chunk generator itself only reads arrays.
 */
public final class FeatureRasterizer {

    /** Elevation lookup in block coordinates; NaN when unavailable. */
    @FunctionalInterface
    public interface Elevation {
        double sample(int blockX, int blockZ);
    }

    private final CoordinateMapper mapper;
    private final OrbisConfig cfg;
    private final Elevation elevation;
    /** The same in metres above sea level (null: worked out from the block heights). */
    private volatile Elevation elevationMeters;
    /** Surface model (tree/building tops) in block units, or null when no source is configured. */
    private final Elevation surfaceModel;
    private final ImageryProvider imagery;
    private final LandmarkRegistry landmarks;

    public FeatureRasterizer(CoordinateMapper mapper, OrbisConfig cfg, Elevation elevation, LandmarkRegistry landmarks) {
        this(mapper, cfg, elevation, null, null, landmarks);
    }

    public FeatureRasterizer(CoordinateMapper mapper, OrbisConfig cfg, Elevation elevation, Elevation surfaceModel,
                             ImageryProvider imagery, LandmarkRegistry landmarks) {
        this.mapper = mapper;
        this.cfg = cfg;
        this.elevation = elevation;
        this.surfaceModel = surfaceModel;
        this.imagery = imagery != null && imagery.isEnabled() ? imagery : null;
        this.landmarks = landmarks;
        this.tunnelCover = heightBlocks(6.5, 3.5);
        this.portalMargin = (int) Math.max(4, Math.round(mapper.blocks(16.0)));
    }

    /**
     * A real height (tunnel cover, a bridge's clearance) in blocks at this world's scale, never less than what a
     * player needs to pass. These were fixed block counts, right at 1:1 only: at 1:2 a tunnel needed 13 m of rock
     * over it and a bridge 11 m over a road, so on flat ground (Nygardstangen) the portals sank 4 blocks and the
     * decks rose, and their cuttings and ramps spread over the whole interchange.
     */
    private double heightBlocks(double metres, double minBlocks) {
        return Math.max(minBlocks, metres / cfg.metersPerBlock);
    }

    /** Ways around a point, from the same map source the region data came from (extract or Overpass, cached). */
    public interface WayLookup {
        /** Ways and areas within the radius of a point; null when nothing can be fetched. */
        OsmData dataAround(double lat, double lon, double radiusMeters);
    }

    private volatile WayLookup wayLookup;

    /**
     * Lets the tunnel pass see the ways that continue past the edge of a region's data: whether a tunnel's end
     * node is a portal or a hand-over to the next tunnel way must be decided the same way by every region that
     * draws the tunnel, or the floors disagree along the region boundary.
     */
    public void setWayLookup(WayLookup lookup) {
        this.wayLookup = lookup;
    }

    private volatile NvdbRoads.Store nvdb;
    /** Road databases outside Norway (widths, lanes); null without any. */
    private volatile RoadDatabases roadDbs;
    private volatile Elevation canopy;
    /** National and city building databases (heights, floors); null without any. */
    private volatile BuildingDatabases buildingDbs;
    /** Loads the surface model under a whole region (lat/lon south, west, north, east) before its trees are read. */
    private volatile java.util.function.Consumer<double[]> canopyPrefetch;

    /** Surveyed road widths and lanes from the Norwegian road database, used where OSM has no width. */
    public void setNvdb(NvdbRoads.Store store) {
        this.nvdb = store;
    }

    /** Tree canopy height in blocks above the ground (lidar surface minus terrain, or the global canopy map), NaN where unknown. */
    private com.berg.orbis.landcover.WorldCoverProvider worldCover;
    private HeightStore heights;

    /** Building heights from the GlobalBuildingAtlas (tools/gba_heights.py), for buildings the map gives no height. */
    public void setHeights(HeightStore store) {
        this.heights = store;
    }

    /** Global 10 m land cover used wherever OSM has no landuse / natural polygon. */
    private com.berg.orbis.geology.Rocks bedrock;
    private com.berg.orbis.water.WaterBeds waterBeds;

    /** Lake and river beds shaped from surveys, lake depths and the shoreline, instead of one depth per water body. */
    public void setWaterBeds(com.berg.orbis.water.WaterBeds beds) {
        this.waterBeds = beds;
    }

    /** Loads the surface model under a list of lat/lon boxes (south, west, north, east) in parallel. */
    public interface SurfacePrefetch {
        void prefetch(List<double[]> boxes);
    }

    private SurfacePrefetch surfacePrefetch;

    public void setSurfacePrefetch(SurfacePrefetch prefetch) {
        this.surfacePrefetch = prefetch;
    }

    /** The geological maps: each column's rock for the stone under it and for bare rock. */
    public void setBedrock(com.berg.orbis.geology.Rocks map) {
        this.bedrock = map;
    }

    public void setElevationMeters(Elevation metres) {
        this.elevationMeters = metres;
    }

    /** Real elevation in metres at a block column (NaN when unknown). */
    private double metresAt(int x, int z) {
        Elevation m = elevationMeters;
        if (m != null) return m.sample(x, z);
        double y = elevationAt(x, z);
        return Double.isNaN(y) ? Double.NaN : (y - cfg.seaLevelY) * cfg.metersPerBlock;
    }

    public void setWorldCover(com.berg.orbis.landcover.WorldCoverProvider provider) {
        this.worldCover = provider;
    }

    public void setBuildingDatabases(BuildingDatabases dbs) {
        this.buildingDbs = dbs;
    }

    public void setCanopyPrefetch(java.util.function.Consumer<double[]> prefetch) {
        this.canopyPrefetch = prefetch;
    }

    public void setCanopy(Elevation canopyBlocks) {
        this.canopy = canopyBlocks;
    }

    /** True when this region would ask NVDB for road widths (so the caller can fetch them alongside the map data). */
    public boolean wantsNvdb(double south, double west, double north, double east) {
        RoadDatabases dbs = roadDbs;
        return nvdbCovers(south, west, north, east)
                || (dbs != null && cfg.nvdbRoadWidths && cfg.metersPerBlock <= 2.0 && dbs.covers(south, west, north, east));
    }

    public void setRoadDatabases(RoadDatabases dbs) {
        this.roadDbs = dbs;
    }

    /** The surveyed road lines of a box: NVDB's in Norway, the road databases' where they cover. */
    public List<NvdbRoads.Segment> roadSegments(double south, double west, double north, double east) {
        List<NvdbRoads.Segment> out = new ArrayList<>();
        NvdbRoads.Store store = nvdb;
        if (store != null && nvdbCovers(south, west, north, east)) out.addAll(store.get(south, west, north, east));
        RoadDatabases dbs = roadDbs;
        if (dbs != null && cfg.nvdbRoadWidths && cfg.metersPerBlock <= 2.0) out.addAll(dbs.get(south, west, north, east));
        return out;
    }

    private boolean nvdbCovers(double south, double west, double north, double east) {
        return nvdb != null && cfg.nvdbRoadWidths && cfg.metersPerBlock <= 2.0 && NvdbRoads.inNorway(south, west, north, east)
                && com.berg.orbis.config.DataSources.covers("nvdb-roads", south, west, north, east);
    }

    public NvdbRoads.Store nvdbStore() {
        return nvdb;
    }

    /** Fetches the terrain tiles under a region (south, west, north, east) in parallel before it is drawn. */
    private java.util.function.Consumer<double[]> terrainPrefetch;

    public void setTerrainPrefetch(java.util.function.Consumer<double[]> prefetch) {
        this.terrainPrefetch = prefetch;
    }

    public RegionRaster rasterize(int regionX, int regionZ, OsmData data) {
        return rasterize(regionX, regionZ, data, null);
    }

    /** As above, with NVDB segments already fetched for the region (null = fetch here if wanted). */
    public RegionRaster rasterize(int regionX, int regionZ, OsmData data, List<NvdbRoads.Segment> nvdbSegments) {
        RegionRaster r = new RegionRaster(regionX, regionZ, cfg.regionSizeBlocks, cfg.regionMarginBlocks);
        StepTimer st = new StepTimer();
        if (imagery != null) {
            // Pull every imagery tile for the region in parallel before the
            // per-cell sampling below asks for them one by one.
            double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride);
            double[] b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
            imagery.prefetch(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
            stepDone(st, "imagery");
        }
        if (terrainPrefetch != null) {
            double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride);
            double[] b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
            terrainPrefetch.accept(new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])});
            stepDone(st, "terrain");
        }
        if (data != null) {
            r.hasOsmData = !data.areas().isEmpty() || !data.ways().isEmpty() || !data.nodes().isEmpty();

            List<OsmArea> areasBigFirst = new ArrayList<>(data.areas());
            areasBigFirst.sort(Comparator.comparingDouble(OsmArea::approxAreaM2).reversed());

            if (cfg.generateLandCover) rasterizeLandCover(r, areasBigFirst);
            stepDone(st, "landcover");
            if (cfg.generateWater) {
                List<com.berg.orbis.water.WaterBeds.Body> bodies = rasterizeWaterAreas(r, areasBigFirst);
                rasterizeWaterways(r, data.ways());
                rasterizeCoastline(r, data.ways());
                measureSeaShore(r);
                if (waterBeds != null && cfg.realWaterDepths) waterBeds.shape(r, bodies);
            }
            stepDone(st, "water");
            if (cfg.generateLandCover && cfg.worldCoverLandCover && worldCover != null) fillLandCoverGaps(r);
            stepDone(st, "worldcover");
            if (cfg.generateRoads) {
                List<Cover> covers = cfg.generateBuildings ? buildingCovers(areasBigFirst) : List.of();
                rasterizeTransport(r, data.ways(), nvdbIndex(r, nvdbSegments), covers);
            }
            stepDone(st, "roads");
            if (cfg.generateBuildings) {
                HeightStore.Loaded atlas = null;
                if (heights != null && cfg.atlasBuildingHeights && !heights.isEmpty()) {
                    double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride);
                    double[] b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
                    atlas = heights.load(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
                }
                rasterizeBuildings(r, areasBigFirst, atlas);
            }
            stepDone(st, "buildings");
            if (cfg.generateStreetFurniture || cfg.generateTrees) rasterizeDecor(r, data);
            if (cfg.generateRoads && cfg.generateStreetFurniture && cfg.streetSigns && cfg.metersPerBlock <= 2.0) rasterizeStreetSigns(r, data.ways());
            stepDone(st, "decor");
        }
        if (imagery != null && cfg.imageryGroundClassification) classifyGroundFromImagery(r);
        stepDone(st, "ground");
        if (cfg.generateTrees && cfg.treesFromCanopy && canopy != null) {
            java.util.function.Consumer<double[]> pre = canopyPrefetch;
            if (pre != null) {
                double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride), b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
                pre.accept(new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])});
            }
            rasterizeCanopyTrees(r);
        }
        stepDone(st, "canopy");
        if (bedrock != null && cfg.bedrockTypes) rasterizeBedrock(r);
        stepDone(st, "rock");
        st.report(regionX, regionZ);
        return r;
    }

    private volatile boolean cancelled;

    /**
     * The world model this rasterizer belongs to was replaced (another world, other settings): a region being built
     * stops at its next step instead of running on for minutes with the old world's downloads (abandoned Paris
     * attempts kept French lidar and photo services busy beside a new Stavanger world, 2 Oct 2026).
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * A region without coastline of its own (open water, or inland): sea wherever the ground lies below sea level,
     * written into the raster so that everything asking it agrees. The painter always decided so for such regions,
     * but the rest (vanilla villages and other surface structures, trees, settlements, street life) asked only the
     * coastline's sea and took open water for empty land: villages stood on the sea (Stavanger 1:2, 2 Oct 2026).
     * A coarse look first, so a region with no ground below sea level costs a few hundred samples.
     */
    private void seaFromHeights(RegionRaster r) {
        if (!cfg.seaFromElevation) return;
        int stride = r.stride;
        boolean any = false;
        for (int lz = 0; lz < stride && !any; lz += 16) {
            for (int lx = 0; lx < stride && !any; lx += 16) {
                double y = elevation.sample(r.originX + lx, r.originZ + lz);
                any = !Double.isNaN(y) && y < cfg.seaLevelY;
            }
        }
        if (!any) return;
        for (int i = 0, n = stride * stride; i < n; i++) {
            double y = elevation.sample(r.originX + i % stride, r.originZ + i / stride);
            if (!Double.isNaN(y) && y < cfg.seaLevelY) r.sea[i] = 1;
        }
        r.hasCoastline = true; // the sea array is now the answer, as with coastline
    }

    private void stepDone(StepTimer st, String name) {
        st.step(name);
        if (cancelled) throw new java.util.concurrent.CancellationException("world model replaced");
    }

    /** -Dorbis.profileRegions=true: how long each step of a region took (to find what makes a fresh region slow). */
    private static final class StepTimer {
        private static final boolean ON = Boolean.getBoolean("orbis.profileRegions");
        private final StringBuilder sb = ON ? new StringBuilder() : null;
        private long t = System.nanoTime();

        void step(String name) {
            if (!ON) return;
            long now = System.nanoTime();
            long ms = (now - t) / 1_000_000;
            if (ms >= 50) sb.append(' ').append(name).append(' ').append(ms).append(" ms,");
            t = now;
        }

        void report(int rx, int rz) {
            if (ON) System.out.println("[orbis] Region " + rx + "," + rz + " steps:" + sb);
        }
    }

    /** Each column's rock from the bedrock map, where it covers the region. */
    private void rasterizeBedrock(RegionRaster r) {
        double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride);
        double[] b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
        double south = Math.min(a[0], b[0]), west = Math.min(a[1], b[1]), north = Math.max(a[0], b[0]), east = Math.max(a[1], b[1]);
        if (!bedrock.overlaps(south, west, north, east)) return;
        bedrock.prefetch(south, west, north, east);
        byte[] rock = new byte[r.stride * r.stride];
        boolean any = false;
        for (int dz = 0; dz < r.stride; dz++) {
            for (int dx = 0; dx < r.stride; dx++) {
                double[] ll = mapper.toLatLonExact(r.originX + dx + 0.5, r.originZ + dz + 0.5);
                int code = bedrock.rockAt(ll[0], ll[1]).ordinal();
                rock[dz * r.stride + dx] = (byte) code;
                any |= code != 0;
            }
        }
        if (any) r.rock = rock;
    }

    // =====================================================================
    // Trees from a canopy height model
    // =====================================================================

    /**
     * Every tree the canopy model shows, where it really stands and as tall as it really is: a cell is a tree
     * top when its canopy height is at least 2.5 blocks, no cell within a crown radius (a third of the height,
     * 1 to 4 cells) is taller, and enough neighbours are high too (a lamp post or mast is a spike, a crown is
     * a mound). Marked as TREE decor with the height in the data byte, like a surveyed OSM tree; the region is
     * then flagged so the decorator scatters no random trees over it.
     */
    private void rasterizeCanopyTrees(RegionRaster r) {
        int n = r.stride * r.stride;
        float[] h = new float[n];
        int known = 0, considered = 0;
        for (int lz = 0; lz < r.stride; lz++) {
            for (int lx = 0; lx < r.stride; lx++) {
                int idx = lz * r.stride + lx;
                h[idx] = Float.NaN;
                if (r.building[idx] != 0 || r.road[idx] != 0 || r.water[idx] != 0 || r.sea[idx] != 0) continue;
                LandCover lc = r.landCoverAt(idx);
                switch (lc) {
                    case PITCH, PITCH_ARTIFICIAL, PITCH_HARD, PARKING, RUNWAY, AIRPORT_APRON, PIER, GLACIER, QUARRY -> {
                        continue;
                    }
                    default -> { }
                }
                considered++;
                double v = canopy.sample(r.originX + lx, r.originZ + lz);
                if (Double.isNaN(v)) continue;
                known++;
                h[idx] = (float) Math.max(0, v);
            }
        }
        if (considered == 0 || known < considered * 0.3) return; // the model does not cover this region
        int trees = 0;
        for (int lz = 1; lz < r.stride - 1; lz++) {
            for (int lx = 1; lx < r.stride - 1; lx++) {
                int idx = lz * r.stride + lx;
                float v = h[idx];
                if (Float.isNaN(v) || v < 2.5f || v > 60f) continue;
                int radius = Math.max(1, Math.min(4, Math.round(v / 3f)));
                boolean top = true;
                int highNeighbours = 0;
                for (int dz = -radius; dz <= radius && top; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = lx + dx, nz = lz + dz;
                        if (nx < 0 || nz < 0 || nx >= r.stride || nz >= r.stride) continue;
                        float o = h[nz * r.stride + nx];
                        if (Float.isNaN(o)) continue;
                        if (o > v || (o == v && (nz * r.stride + nx) < idx)) {
                            top = false;
                            break;
                        }
                        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && o >= 0.4f * v) highNeighbours++;
                    }
                }
                if (!top || highNeighbours < 3) continue;
                if (r.decor[idx] != 0) continue;
                int hb = Math.max(1, Math.min(15, Math.round(v / 2f)));
                r.decor[idx] = DecorType.TREE.code();
                r.decorData[idx] = (byte) (hb << 4); // species 0 = by land cover and climate
                trees++;
            }
        }
        r.canopyTrees = true;
        if (cfg.debugLogging) System.out.println("[orbis] Region " + r.regionX + "," + r.regionZ + ": " + trees + " trees from the canopy model");
    }

    // =====================================================================
    // Aerial imagery: real ground cover where OSM has no polygon
    // =====================================================================

    /** Broad zoning classes that say nothing about what the ground actually looks like. */
    private static boolean imageryMayOverride(LandCover lc) {
        return switch (lc) {
            case NONE, RESIDENTIAL, COMMERCIAL, RETAIL, INDUSTRIAL, SCHOOL, HOSPITAL, MILITARY, CONSTRUCTION, GRASS, MEADOW, PARK, HEATH, SCRUB -> true;
            default -> false;
        };
    }

    private void classifyGroundFromImagery(RegionRaster r) {
        int n = r.stride * r.stride;
        byte[] raw = new byte[n];
        int sampled = 0, classified = 0;
        for (int lz = 0; lz < r.stride; lz++) {
            for (int lx = 0; lx < r.stride; lx++) {
                int idx = lz * r.stride + lx;
                if (r.building[idx] != 0 || r.road[idx] != 0 || r.water[idx] != 0 || r.sea[idx] != 0) continue;
                if (!imageryMayOverride(r.landCoverAt(idx))) continue;
                double[] ll = mapper.toLatLon(r.originX + lx, r.originZ + lz);
                int rgb = imagery.colourAt(ll[0], ll[1]);
                if (rgb < 0) continue;
                GroundClass gc = GroundClass.classify(rgb);
                if (gc == GroundClass.WATER) gc = GroundClass.UNKNOWN; // never trust "blue" (roofs, shadows)
                sampled++;
                raw[idx] = gc.code();
                if (gc != GroundClass.UNKNOWN) classified++;
            }
        }
        if (sampled == 0) return;
        // 3x3 majority filter removes single-pixel noise, then a "no dark
        // pavement right next to a building" rule removes building shadows.
        int[] counts = new int[GroundClass.values().length];
        for (int lz = 0; lz < r.stride; lz++) {
            for (int lx = 0; lx < r.stride; lx++) {
                int idx = lz * r.stride + lx;
                if (raw[idx] == 0) continue;
                java.util.Arrays.fill(counts, 0);
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = lx + dx, nz = lz + dz;
                        if (nx < 0 || nz < 0 || nx >= r.stride || nz >= r.stride) continue;
                        counts[raw[nz * r.stride + nx] & 0xFF]++;
                    }
                }
                int best = 0;
                for (int c = 1; c < counts.length; c++) if (counts[c] > counts[best]) best = c;
                GroundClass gc = GroundClass.byCode(best);
                if (gc == GroundClass.PAVED_DARK && nearBuilding(r, lx, lz, 3)) gc = GroundClass.UNKNOWN;
                r.groundClass[idx] = gc.code();
            }
        }
        if (cfg.debugLogging) {
            System.out.println("[orbis] Region " + r.regionX + "," + r.regionZ + " imagery: " + sampled + " columns sampled, " + classified + " classified");
        }
    }

    private static boolean nearBuilding(RegionRaster r, int lx, int lz, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = lx + dx, nz = lz + dz;
                if (nx < 0 || nz < 0 || nx >= r.stride || nz >= r.stride) continue;
                if (r.building[nz * r.stride + nx] != 0) return true;
            }
        }
        return false;
    }

    /**
     * The colour the streets have in this region's photo (0xRRGGBB), or -1 when too few street cells are photographed.
     * Asphalt is a dark neutral grey, so whatever cast and brightness the photo gives it, it gives the roofs too.
     */
    private int streetColour(RegionRaster r) {
        if (r.imageryStreetColour != -2) return r.imageryStreetColour;
        List<int[]> samples = new ArrayList<>();
        int step = 7;
        for (int dz = 0; dz < r.stride; dz += step) {
            for (int dx = (dz / step) % step; dx < r.stride; dx += step) {
                int idx = dz * r.stride + dx;
                RoadFeature rf = r.roadAt(idx);
                if (rf == null || rf.kind != RoadFeature.Kind.ROAD || rf.bridge || rf.tunnel || r.building[idx] != 0) continue;
                double[] ll = mapper.toLatLon(r.originX + dx, r.originZ + dz);
                int rgb = imagery.colourAt(ll[0], ll[1]);
                if (rgb >= 0) samples.add(new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF});
            }
        }
        int result = -1;
        if (samples.size() >= 60) {
            // Without the brightest fifth (markings, cars, sunlit concrete) and the darkest (shadows, wet patches).
            samples.sort(java.util.Comparator.comparingInt(c -> c[0] + c[1] + c[2]));
            List<int[]> core = samples.subList(samples.size() / 5, samples.size() * 4 / 5);
            long sr = 0, sg = 0, sb = 0;
            for (int[] c : core) {
                sr += c[0];
                sg += c[1];
                sb += c[2];
            }
            result = (int) (sr / core.size()) << 16 | (int) (sg / core.size()) << 8 | (int) (sb / core.size());
        }
        r.imageryStreetColour = result;
        return result;
    }

    /**
     * A roof's photographed colour as the material's own: the photo's cast removed (the streets are neutral), and its
     * brightness mapped so a roof as bright as the street is a dark grey, spread by a gentle curve. Measured on Bergen's
     * roofs tagged with a colour in OpenStreetMap (Esri imagery, which shows black roofs mid-grey and everything with a
     * pinkish haze): the nearest roof block matched the tag's kind for 46 of 80 roofs instead of 37, black roofs
     * dark for 24 of 29 instead of 6.
     */
    private static int correctRoofColour(int rgb, int street) {
        if (street < 0) return rgb;
        int[] ref = {(street >> 16) & 0xFF, (street >> 8) & 0xFF, street & 0xFF};
        double lum = (ref[0] + ref[1] + ref[2]) / 3.0;
        if (lum < 8) return rgb;
        int out = 0;
        for (int ch = 0; ch < 3; ch++) {
            int v = (rgb >> (16 - 8 * ch)) & 0xFF;
            double balanced = v * lum / Math.max(1, ref[ch]);
            int c = (int) Math.max(0, Math.min(255, Math.round(ROOF_STREET_GREY * Math.pow(balanced / lum, ROOF_CURVE))));
            out |= c << (16 - 8 * ch);
        }
        return out;
    }

    /** The grey a roof as bright as the street becomes, and the curve that spreads darker and lighter roofs from it. */
    private static final double ROOF_STREET_GREY = 0x58, ROOF_CURVE = 1.3;

    /** Median imagery colour over a building's footprint cells, or -1. */
    private int roofColourFromImagery(RegionRaster r, short code, int[] bbox) {
        if (imagery == null) return -1;
        List<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
        int step = Math.max(1, (int) Math.sqrt(Math.max(1, (bbox[2] - bbox[0] + 1) * (bbox[3] - bbox[1] + 1) / 40.0)));
        for (int z = bbox[1]; z <= bbox[3]; z += step) {
            for (int x = bbox[0]; x <= bbox[2]; x += step) {
                int idx = r.index(x, z);
                if (idx < 0 || r.building[idx] != code) continue;
                if ((r.buildingFlags[idx] & RegionRaster.FLAG_EDGE) != 0) continue; // avoid wall/shadow pixels at the outline
                double[] ll = mapper.toLatLon(x, z);
                int rgb = imagery.colourAt(ll[0], ll[1]);
                if (rgb < 0) continue;
                rs.add((rgb >> 16) & 0xFF);
                gs.add((rgb >> 8) & 0xFF);
                bs.add(rgb & 0xFF);
            }
        }
        if (rs.size() < 3) return -1;
        return (medianInt(rs) << 16) | (medianInt(gs) << 8) | medianInt(bs);
    }

    private static int medianInt(List<Integer> v) {
        List<Integer> s = new ArrayList<>(v);
        s.sort(Integer::compareTo);
        return s.get(s.size() / 2);
    }

    /** Even-odd point-in-polygon over a set of rings (block coordinates). */
    private static boolean pointInRings(double px, double pz, List<List<double[]>> rings) {
        boolean inside = false;
        for (List<double[]> ring : rings) {
            int m = ring.size();
            for (int i = 0, j = m - 1; i < m; j = i++) {
                double[] a = ring.get(i), b = ring.get(j);
                if ((a[1] > pz) != (b[1] > pz)) {
                    double x = a[0] + (pz - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    if (px < x) inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * Building height from a lidar surface model: the 80th percentile of the
     * surface height over interior points minus the terrain base. Returns
     * NaN when no surface model covers the building.
     */
    private double heightFromSurfaceModel(List<List<double[]>> rings, double cx, double cz, double ax, double az,
                                          double halfA, double halfB, double baseY) {
        if (surfaceModel == null) return Double.NaN;
        List<Double> tops = new ArrayList<>();
        int grid = 5;
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                double u = (-0.7 + 1.4 * i / (grid - 1)) * halfA;
                double v = (-0.7 + 1.4 * j / (grid - 1)) * halfB;
                double px = cx + u * ax - v * az, pz = cz + u * az + v * ax;
                if (!pointInRings(px, pz, rings)) continue;
                double s = surfaceModel.sample((int) Math.floor(px), (int) Math.floor(pz));
                if (!Double.isNaN(s)) tops.add(s);
            }
        }
        if (tops.size() < 3) return Double.NaN;
        tops.sort(Double::compareTo);
        double top = tops.get(Math.min(tops.size() - 1, (int) (tops.size() * 0.8)));
        return top - baseY;
    }

    /**
     * The roof read off the lidar surface model: {eave height, ridge height, shape} in blocks above the base,
     * shape 0 = flat, 1 = gabled, 2 = hipped, -1 = irregular (keep the default shape, use the heights). Null
     * when the footprint is too small or the model does not cover it. The surface is sampled on a 9 x 7 grid
     * aligned with the footprint's axes: the eave is the 20th percentile, the ridge the 92nd; a roof whose
     * middle row across the short axis stands clearly above the two edge rows is pitched; if its middle
     * columns along the long axis also stand above the end columns it is hipped, otherwise gabled.
     */
    private double[] roofFromSurfaceModel(List<List<double[]>> rings, double cx, double cz, double ax, double az,
                                          double halfA, double halfB, double baseY) {
        if (surfaceModel == null || halfB < 2.5 || halfA < 3) return null;
        int nu = 9, nv = 7;
        double[][] grid = new double[nu][nv];
        List<Double> all = new ArrayList<>();
        for (int i = 0; i < nu; i++) {
            for (int j = 0; j < nv; j++) {
                double u = (-0.85 + 1.7 * i / (nu - 1)) * halfA;
                double v = (-0.85 + 1.7 * j / (nv - 1)) * halfB;
                double px = cx + u * ax - v * az, pz = cz + u * az + v * ax;
                grid[i][j] = Double.NaN;
                if (!pointInRings(px, pz, rings)) continue;
                double s = surfaceModel.sample((int) Math.floor(px), (int) Math.floor(pz));
                if (Double.isNaN(s)) continue;
                grid[i][j] = s - baseY;
                all.add(grid[i][j]);
            }
        }
        if (all.size() < 15) return null;
        all.sort(Double::compareTo);
        double eave = all.get((int) (all.size() * 0.2));
        double ridge = all.get(Math.min(all.size() - 1, (int) (all.size() * 0.92)));
        if (ridge < 2.0 || ridge > cfg.maxBuildingHeightBlocks) return null;
        eave = Math.max(1.5, eave);
        if (ridge - eave < 1.2) return new double[]{ridge, ridge, 0};
        double[] rowV = new double[nv];
        int[] cntV = new int[nv];
        double[] colU = new double[nu];
        int[] cntU = new int[nu];
        for (int i = 0; i < nu; i++) {
            for (int j = 0; j < nv; j++) {
                if (Double.isNaN(grid[i][j])) continue;
                rowV[j] += grid[i][j];
                cntV[j]++;
                colU[i] += grid[i][j];
                cntU[i]++;
            }
        }
        for (int j = 0; j < nv; j++) rowV[j] = cntV[j] > 0 ? rowV[j] / cntV[j] : Double.NaN;
        for (int i = 0; i < nu; i++) colU[i] = cntU[i] > 0 ? colU[i] / cntU[i] : Double.NaN;
        double midV = mean(rowV[2], rowV[3], rowV[4]), edgeV = mean(rowV[0], rowV[6]);
        double midU = mean(colU[3], colU[4], colU[5]), endU = mean(colU[0], colU[8]);
        double pitch = ridge - eave;
        boolean peakAcross = !Double.isNaN(midV) && !Double.isNaN(edgeV) && midV - edgeV >= 0.35 * pitch;
        boolean lowerEnds = !Double.isNaN(midU) && !Double.isNaN(endU) && midU - endU >= Math.max(1.0, 0.35 * pitch);
        if (peakAcross) return new double[]{eave, ridge, lowerEnds ? 2 : 1};
        if (lowerEnds) return new double[]{eave, ridge, 1}; // the ridge runs across the short axis: still a gable
        return new double[]{eave, ridge, -1};
    }

    private static double mean(double... v) {
        double s = 0;
        int n = 0;
        for (double x : v) {
            if (Double.isNaN(x)) continue;
            s += x;
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
    }

    // =====================================================================
    // Geometry helpers
    // =====================================================================

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int idx, int x, int z);
    }

    private List<double[]> toBlockRing(List<LatLon> ring) {
        List<double[]> out = new ArrayList<>(ring.size());
        for (LatLon p : ring) out.add(mapper.toBlockExact(p.lat(), p.lon()));
        return out;
    }

    /** Even-odd scanline fill over a set of rings (outers + inners together), in world block coordinates. */
    private void fillRings(RegionRaster r, List<List<double[]>> rings, CellVisitor visitor) {
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (List<double[]> ring : rings) {
            for (double[] p : ring) {
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
        }
        if (minZ == Double.MAX_VALUE) return;
        int z0 = Math.max(r.originZ, (int) Math.floor(minZ));
        int z1 = Math.min(r.originZ + r.stride - 1, (int) Math.ceil(maxZ));
        double[] xs = new double[64];
        for (int z = z0; z <= z1; z++) {
            double zc = z + 0.5;
            int n = 0;
            for (List<double[]> ring : rings) {
                int m = ring.size();
                for (int i = 0; i < m; i++) {
                    double[] p1 = ring.get(i);
                    double[] p2 = ring.get((i + 1) % m);
                    if ((p1[1] <= zc && p2[1] > zc) || (p2[1] <= zc && p1[1] > zc)) {
                        double t = (zc - p1[1]) / (p2[1] - p1[1]);
                        if (n == xs.length) xs = Arrays.copyOf(xs, n * 2);
                        xs[n++] = p1[0] + t * (p2[0] - p1[0]);
                    }
                }
            }
            if (n < 2) continue;
            Arrays.sort(xs, 0, n);
            for (int i = 0; i + 1 < n; i += 2) {
                int xa = Math.max(r.originX, (int) Math.ceil(xs[i] - 0.5));
                int xb = Math.min(r.originX + r.stride - 1, (int) Math.floor(xs[i + 1] - 0.5));
                for (int x = xa; x <= xb; x++) {
                    visitor.visit(r.index(x, z), x, z);
                }
            }
        }
    }

    private void fillArea(RegionRaster r, OsmArea area, CellVisitor visitor) {
        List<List<double[]>> rings = new ArrayList<>();
        for (List<LatLon> ring : area.outers()) rings.add(toBlockRing(ring));
        for (List<LatLon> ring : area.inners()) rings.add(toBlockRing(ring));
        fillRings(r, rings, visitor);
    }

    @FunctionalInterface
    private interface SegmentCellVisitor {
        /** @param dist distance from the centreline in blocks; @param along cumulative distance along the way. */
        void visit(int idx, int x, int z, double dist, double along, int dirOctant);
    }

    /**
     * Visits every cell within halfWidth (+0.5) of the polyline. Distance is
     * exact point-to-segment distance so lane markings and sidewalks can be
     * derived from it later. Long segments are split so the bounding-box
     * loop stays cheap for diagonal lines.
     */
    private void walkThickLine(RegionRaster r, List<double[]> pts, double halfWidth, SegmentCellVisitor visitor) {
        double along = 0;
        double reach = halfWidth + 0.5;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            double segLen = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (segLen < 1e-9) continue;
            int octant = octant(b[0] - a[0], b[1] - a[1]);
            int pieces = Math.max(1, (int) Math.ceil(segLen / 24.0));
            for (int p = 0; p < pieces; p++) {
                double t0 = (double) p / pieces, t1 = (double) (p + 1) / pieces;
                double ax = a[0] + (b[0] - a[0]) * t0, az = a[1] + (b[1] - a[1]) * t0;
                double bx = a[0] + (b[0] - a[0]) * t1, bz = a[1] + (b[1] - a[1]) * t1;
                double pieceStart = along + segLen * t0;
                int x0 = Math.max(r.originX, (int) Math.floor(Math.min(ax, bx) - reach));
                int x1 = Math.min(r.originX + r.stride - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
                int z0 = Math.max(r.originZ, (int) Math.floor(Math.min(az, bz) - reach));
                int z1 = Math.min(r.originZ + r.stride - 1, (int) Math.ceil(Math.max(az, bz) + reach));
                double dx = bx - ax, dz = bz - az;
                double len2 = dx * dx + dz * dz;
                for (int z = z0; z <= z1; z++) {
                    double cz = z + 0.5;
                    for (int x = x0; x <= x1; x++) {
                        double cx = x + 0.5;
                        double t = ((cx - ax) * dx + (cz - az) * dz) / len2;
                        if (t < 0) t = 0;
                        else if (t > 1) t = 1;
                        double px = ax + t * dx, pz = az + t * dz;
                        double d = Math.hypot(cx - px, cz - pz);
                        if (d <= reach) {
                            visitor.visit(r.index(x, z), x, z, d, pieceStart + t * Math.sqrt(len2), octant);
                        }
                    }
                }
            }
            along += segLen;
        }
    }

    /** 0 = east, 1 = south-east, 2 = south, ... (Minecraft +Z is south). */
    private static int octant(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        int o = (int) Math.round(angle / (Math.PI / 4));
        return ((o % 8) + 8) % 8;
    }

    /** 4-connected line walk (no diagonal steps) so fences/walls connect and floods can't leak through. */
    private void walkThinLine4(RegionRaster r, List<double[]> pts, CellVisitor visitor) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            int x = (int) Math.floor(a[0]), z = (int) Math.floor(a[1]);
            int xe = (int) Math.floor(b[0]), ze = (int) Math.floor(b[1]);
            double dx = b[0] - a[0], dz = b[1] - a[1];
            int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
            double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1) - a[0] : a[0] - x) / Math.abs(dx));
            double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1) - a[1] : a[1] - z) / Math.abs(dz));
            double tDeltaX = dx == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
            double tDeltaZ = dz == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);
            int guard = 0;
            while (true) {
                int idx = r.index(x, z);
                if (idx >= 0) visitor.visit(idx, x, z);
                if (x == xe && z == ze) break;
                if (++guard > 100000) break;
                if (tMaxX < tMaxZ) {
                    tMaxX += tDeltaX;
                    x += stepX;
                } else {
                    tMaxZ += tDeltaZ;
                    z += stepZ;
                }
                if (Math.abs(x - xe) > 100000 || Math.abs(z - ze) > 100000) break;
            }
        }
    }

    private double elevationAt(double bx, double bz) {
        return elevation.sample((int) Math.floor(bx), (int) Math.floor(bz));
    }

    // =====================================================================
    // Land cover
    // =====================================================================

    private void rasterizeLandCover(RegionRaster r, List<OsmArea> areasBigFirst) {
        for (OsmArea area : areasBigFirst) {
            LandCover lc = landCoverFor(area.tags());
            if (lc == null) continue;
            byte code = lc.code();
            fillArea(r, area, (idx, x, z) -> {
                if (idx >= 0) r.landCover[idx] = code;
            });
        }
    }

    /**
     * Every cell OSM left without land cover gets ESA WorldCover's class for it (10 m): forest, shrub,
     * grassland, cropland, built-up, bare ground, snow, wetland. Water is left alone (the coastline and the
     * OSM water bodies decide that), and so are cells with no data. Bare ground is sand in the warm latitudes
     * below 1500 m (the deserts) and rock elsewhere.
     */
    private void fillLandCoverGaps(RegionRaster r) {
        int filled = 0;
        for (int lz = 0; lz < r.stride; lz++) {
            for (int lx = 0; lx < r.stride; lx++) {
                int idx = lz * r.stride + lx;
                if (r.landCover[idx] != 0 || r.water[idx] != 0 || (r.hasCoastline && r.isSea(idx))) continue;
                int x = r.originX + lx, z = r.originZ + lz;
                double[] ll = mapper.toLatLon(x, z);
                LandCover lc = worldCoverClass(worldCover.classAt(ll[0], ll[1]), ll[0], x, z);
                if (lc == null) continue;
                r.landCover[idx] = lc.code();
                filled++;
            }
        }
        if (filled > 0) r.hasOsmData = true;
    }

    private LandCover worldCoverClass(int code, double lat, int x, int z) {
        return switch (code) {
            case com.berg.orbis.landcover.WorldCoverProvider.TREE_COVER -> LandCover.FOREST;
            case com.berg.orbis.landcover.WorldCoverProvider.SHRUBLAND -> LandCover.SCRUB;
            case com.berg.orbis.landcover.WorldCoverProvider.GRASSLAND -> LandCover.MEADOW;
            case com.berg.orbis.landcover.WorldCoverProvider.CROPLAND -> LandCover.FARMLAND;
            case com.berg.orbis.landcover.WorldCoverProvider.BUILT_UP -> LandCover.RESIDENTIAL;
            case com.berg.orbis.landcover.WorldCoverProvider.BARE -> {
                // Metres, not blocks above sea level: at 1:2 "1500" meant 3000 m, and Himalayan valleys became sand
                // (beach biomes, buried treasure at 4000 m).
                double e = metresAt(x, z);
                boolean warm = Math.abs(lat) < 35 && (Double.isNaN(e) || e < 1500);
                yield warm ? LandCover.SAND : LandCover.BARE_ROCK;
            }
            case com.berg.orbis.landcover.WorldCoverProvider.SNOW_ICE -> LandCover.GLACIER;
            case com.berg.orbis.landcover.WorldCoverProvider.WETLAND, com.berg.orbis.landcover.WorldCoverProvider.MANGROVES -> LandCover.WETLAND;
            case com.berg.orbis.landcover.WorldCoverProvider.MOSS_LICHEN -> LandCover.HEATH;
            default -> null;
        };
    }

    static LandCover landCoverFor(Map<String, String> tags) {
        String landuse = tags.get("landuse");
        String natural = tags.get("natural");
        String leisure = tags.get("leisure");
        String amenity = tags.get("amenity");
        String surface = tags.get("surface");
        if (landuse != null) {
            switch (landuse) {
                case "forest": return forestType(tags);
                case "grass", "village_green", "recreation_ground", "greenfield", "religious", "flowerbed": return "flowerbed".equals(landuse) ? LandCover.GARDEN : LandCover.GRASS;
                case "meadow": return LandCover.MEADOW;
                case "farmland", "greenhouse_horticulture": return LandCover.FARMLAND;
                case "farmyard", "garages", "depot": return LandCover.GRAVEL_AREA;
                case "orchard", "plant_nursery": return LandCover.ORCHARD;
                case "vineyard": return LandCover.VINEYARD;
                case "allotments": return LandCover.ALLOTMENTS;
                case "residential": return LandCover.RESIDENTIAL;
                case "commercial": return LandCover.COMMERCIAL;
                case "retail": return LandCover.RETAIL;
                case "industrial", "port": return LandCover.INDUSTRIAL;
                case "railway": return LandCover.RAILWAY_LAND;
                case "cemetery": return LandCover.CEMETERY;
                case "construction", "brownfield": return LandCover.CONSTRUCTION;
                case "quarry": return LandCover.QUARRY;
                case "landfill": return LandCover.LANDFILL;
                case "military": return LandCover.MILITARY;
                case "education": return LandCover.SCHOOL;
                default: break;
            }
        }
        if (natural != null) {
            switch (natural) {
                case "wood": return forestType(tags);
                case "scrub", "shrubbery": return LandCover.SCRUB;
                case "heath", "fell", "tundra", "moor": return LandCover.HEATH;
                case "grassland": return LandCover.MEADOW;
                case "sand", "dune": return LandCover.SAND;
                case "beach": return LandCover.BEACH;
                case "bare_rock", "rock", "stone", "cliff": return "cliff".equals(natural) ? null : LandCover.BARE_ROCK;
                case "scree", "blockfield": return LandCover.SCREE;
                case "shingle": return LandCover.GRAVEL_AREA;
                case "wetland": {
                    String w = tags.get("wetland");
                    if ("tidalflat".equals(w) || "saltmarsh".equals(w)) return LandCover.SALT_MARSH;
                    if ("mud".equals(w)) return LandCover.MUD;
                    return LandCover.WETLAND;
                }
                case "mud": return LandCover.MUD;
                case "glacier": return LandCover.GLACIER;
                case "reef": return LandCover.REEF;
                default: break;
            }
        }
        if (leisure != null) {
            switch (leisure) {
                case "park", "common": return LandCover.PARK;
                case "garden": return LandCover.GARDEN;
                case "pitch": {
                    String sport = tags.get("sport");
                    if (surface != null) {
                        switch (surface) {
                            case "artificial_turf", "artificial_grass": return LandCover.PITCH_ARTIFICIAL;
                            case "asphalt", "concrete", "tartan", "clay", "acrylic", "paved", "hard", "rubber": return LandCover.PITCH_HARD;
                            case "grass", "ground", "dirt", "earth": return LandCover.PITCH;
                            default: break;
                        }
                    }
                    if (sport != null && (sport.contains("tennis") || sport.contains("basketball") || sport.contains("skate")
                            || sport.contains("volleyball") || sport.contains("padel") || sport.contains("handball"))) {
                        return LandCover.PITCH_HARD;
                    }
                    return LandCover.PITCH;
                }
                case "playground": return LandCover.PLAYGROUND;
                case "golf_course", "miniature_golf": return LandCover.GOLF;
                case "sports_centre", "stadium", "dog_park", "recreation_ground", "schoolyard": return LandCover.GRASS_SPORT;
                case "track": return LandCover.PITCH_HARD;
                case "marina": return LandCover.MARINA;
                case "beach_resort": return LandCover.BEACH;
                default: break;
            }
        }
        if (amenity != null) {
            switch (amenity) {
                case "parking": {
                    String p = tags.get("parking");
                    if ("multi-storey".equals(p) || "underground".equals(p) || "rooftop".equals(p)) return null;
                    if (surface != null && (surface.equals("grass") || surface.equals("gravel") || surface.equals("unpaved") || surface.equals("ground"))) {
                        return surface.equals("grass") ? LandCover.GRASS : LandCover.GRAVEL_AREA;
                    }
                    return LandCover.PARKING;
                }
                case "school", "university", "college", "kindergarten": return LandCover.SCHOOL;
                case "hospital": return LandCover.HOSPITAL;
                case "grave_yard": return LandCover.CEMETERY;
                case "marketplace": return LandCover.MARKETPLACE;
                case "bus_station": return LandCover.PARKING;
                default: break;
            }
        }
        String highway = tags.get("highway");
        if (highway != null) {
            if ("pedestrian".equals(highway) || "footway".equals(highway) || "living_street".equals(highway)) return LandCover.PEDESTRIAN;
            if ("services".equals(highway) || "rest_area".equals(highway)) return LandCover.PARKING;
        }
        if ("square".equals(tags.get("place"))) return LandCover.SQUARE;
        String aeroway = tags.get("aeroway");
        if (aeroway != null) {
            switch (aeroway) {
                case "apron", "helipad", "heliport": return LandCover.AIRPORT_APRON;
                case "runway", "taxiway": return LandCover.RUNWAY;
                default: break;
            }
        }
        String manMade = tags.get("man_made");
        if (manMade != null) {
            switch (manMade) {
                case "pier": return LandCover.PIER;
                case "breakwater", "groyne": return LandCover.BARE_ROCK;
                case "wastewater_plant", "works", "water_works": return LandCover.INDUSTRIAL;
                default: break;
            }
        }
        if ("platform".equals(tags.get("railway")) || "platform".equals(tags.get("public_transport"))) return LandCover.PEDESTRIAN;
        if (tags.containsKey("military")) return LandCover.MILITARY;
        String power = tags.get("power");
        if ("substation".equals(power) || "plant".equals(power)) return LandCover.INDUSTRIAL;
        if ("camp_site".equals(tags.get("tourism")) || "caravan_site".equals(tags.get("tourism"))) return LandCover.GRASS;
        return null;
    }

    private static LandCover forestType(Map<String, String> tags) {
        String leaf = tags.get("leaf_type");
        if ("needleleaved".equals(leaf)) return LandCover.FOREST_CONIFER;
        if ("broadleaved".equals(leaf)) return LandCover.FOREST_BROADLEAF;
        String wood = tags.get("wood");
        if ("coniferous".equals(wood)) return LandCover.FOREST_CONIFER;
        if ("deciduous".equals(wood) || "broadleaved".equals(wood)) return LandCover.FOREST_BROADLEAF;
        return LandCover.FOREST;
    }

    // =====================================================================
    // Water
    // =====================================================================

    /** Fills the water areas; returns the lakes, reservoirs, ponds and river areas whose beds can be shaped. */
    private List<com.berg.orbis.water.WaterBeds.Body> rasterizeWaterAreas(RegionRaster r, List<OsmArea> areasBigFirst) {
        List<com.berg.orbis.water.WaterBeds.Body> bodies = new ArrayList<>();
        for (OsmArea area : areasBigFirst) {
            WaterFeature.Kind kind = waterAreaKind(area.tags());
            if (kind == null) continue;
            Map<String, String> tags = area.tags();

            // Sample elevation at shoreline vertices so the lake gets ONE flat
            // surface. Only vertices near this region are used: a 40 km lake
            // would otherwise pull in DEM tiles from its far shore.
            List<Double> samples = new ArrayList<>();
            int budget = 24;
            int near = 512;
            for (List<LatLon> ring : area.outers()) {
                int step = Math.max(1, ring.size() / 48);
                for (int i = 0; i < ring.size() && budget > 0; i += step) {
                    double[] b = mapper.toBlockExact(ring.get(i).lat(), ring.get(i).lon());
                    if (b[0] < r.originX - near || b[0] > r.originX + r.stride + near
                            || b[1] < r.originZ - near || b[1] > r.originZ + r.stride + near) continue;
                    double e = elevationAt(b[0], b[1]);
                    if (!Double.isNaN(e)) samples.add(e);
                    budget--;
                }
            }
            double surface = samples.isEmpty() ? cfg.seaLevelY : median(samples);
            boolean atSea = kind == WaterFeature.Kind.SEA || isTidal(tags) || surface <= cfg.seaLevelY + 1.0;
            if (atSea && kind != WaterFeature.Kind.SWIMMING_POOL && kind != WaterFeature.Kind.FOUNTAIN_BASIN
                    && kind != WaterFeature.Kind.BASIN) {
                kind = WaterFeature.Kind.SEA;
            }
            double areaM2 = area.approxAreaM2();
            int depth = switch (kind) {
                case SEA -> 6;
                case LAKE -> areaM2 > 1_000_000 ? 12 : areaM2 > 100_000 ? 7 : 4;
                case RESERVOIR -> areaM2 > 500_000 ? 10 : 6;
                case POND -> 2;
                case RIVER -> 3;
                case CANAL -> 3;
                case STREAM, DITCH -> 1;
                case BASIN -> 2;
                case SWIMMING_POOL -> 2;
                case FOUNTAIN_BASIN, WETLAND_WATER -> 1;
            };
            WaterFeature wf = new WaterFeature(area.id(), kind, depth, atSea, OsmTags.isTrue(tags.get("intermittent")), tags.get("name"));
            wf.surfaceY = atSea ? cfg.seaLevelY : (int) Math.round(surface);
            r.waters.add(wf);
            short code = (short) r.waters.size();
            fillArea(r, area, (idx, x, z) -> {
                if (idx >= 0) r.water[idx] = code;
            });
            if (!atSea && (kind == WaterFeature.Kind.LAKE || kind == WaterFeature.Kind.RESERVOIR || kind == WaterFeature.Kind.POND
                    || kind == WaterFeature.Kind.RIVER)) {
                bodies.add(new com.berg.orbis.water.WaterBeds.Body(code, kind, area));
            }
        }
        return bodies;
    }

    private static boolean isTidal(Map<String, String> tags) {
        String water = tags.get("water");
        if (OsmTags.isTrue(tags.get("tidal"))) return true;
        if (water != null) {
            switch (water) {
                case "lagoon", "harbour", "harbor", "bay", "fjord", "strait", "tidal", "sea", "ocean": return true;
                default: break;
            }
        }
        String natural = tags.get("natural");
        if ("bay".equals(natural) || "strait".equals(natural)) return true;
        String name = tags.get("name");
        return name != null && (name.toLowerCase(Locale.ROOT).contains("fjord") || name.toLowerCase(Locale.ROOT).contains("harbour")
                || name.toLowerCase(Locale.ROOT).contains("harbor") || name.toLowerCase(Locale.ROOT).contains("sound"));
    }

    static WaterFeature.Kind waterAreaKind(Map<String, String> tags) {
        String natural = tags.get("natural");
        String water = tags.get("water");
        String landuse = tags.get("landuse");
        String leisure = tags.get("leisure");
        String waterway = tags.get("waterway");
        if ("swimming_pool".equals(leisure)) return WaterFeature.Kind.SWIMMING_POOL;
        if ("fountain".equals(tags.get("amenity"))) return WaterFeature.Kind.FOUNTAIN_BASIN;
        if ("riverbank".equals(waterway) || "dock".equals(waterway)) return "dock".equals(waterway) ? WaterFeature.Kind.SEA : WaterFeature.Kind.RIVER;
        if ("reservoir".equals(landuse)) return WaterFeature.Kind.RESERVOIR;
        if ("basin".equals(landuse) || "salt_pond".equals(landuse) || "aquaculture".equals(landuse)) return WaterFeature.Kind.BASIN;
        if ("bay".equals(natural) || "strait".equals(natural)) return WaterFeature.Kind.SEA;
        if (!"water".equals(natural) && water == null) return null;
        if (water == null) return WaterFeature.Kind.LAKE;
        return switch (water) {
            case "lake", "oxbow" -> WaterFeature.Kind.LAKE;
            case "pond", "fishpond", "moat", "reflecting_pool", "pool" -> WaterFeature.Kind.POND;
            case "reservoir", "wastewater" -> WaterFeature.Kind.RESERVOIR;
            case "river", "rapids" -> WaterFeature.Kind.RIVER;
            case "canal", "lock" -> WaterFeature.Kind.CANAL;
            case "stream", "stream_pool" -> WaterFeature.Kind.STREAM;
            case "ditch", "drain" -> WaterFeature.Kind.DITCH;
            case "basin" -> WaterFeature.Kind.BASIN;
            case "lagoon", "harbour", "harbor", "bay", "fjord", "strait", "tidal", "sea", "ocean" -> WaterFeature.Kind.SEA;
            default -> WaterFeature.Kind.LAKE;
        };
    }

    private void rasterizeWaterways(RegionRaster r, List<OsmWay> ways) {
        for (OsmWay way : ways) {
            String ww = way.tags().get("waterway");
            if (ww == null) continue;
            if (OsmTags.has(way.tags(), "tunnel") || "culvert".equals(way.tags().get("tunnel"))) continue;
            if (way.tags().containsKey("layer") && OsmTags.parseInt(way.tags().get("layer"), 0) < 0) continue;
            WaterFeature.Kind kind;
            double defaultWidth;
            int depth;
            switch (ww) {
                case "river" -> { kind = WaterFeature.Kind.RIVER; defaultWidth = 12; depth = 3; }
                case "stream" -> { kind = WaterFeature.Kind.STREAM; defaultWidth = 2.5; depth = 1; }
                case "canal" -> { kind = WaterFeature.Kind.CANAL; defaultWidth = 10; depth = 3; }
                case "drain", "ditch" -> { kind = WaterFeature.Kind.DITCH; defaultWidth = 1.5; depth = 1; }
                case "tidal_channel" -> { kind = WaterFeature.Kind.RIVER; defaultWidth = 8; depth = 2; }
                default -> { continue; }
            }
            double width = mapper.blocks(OsmTags.parseLength(way.tags().get("width"), defaultWidth));
            int total = Math.max(1, (int) Math.round(width));
            int half = Math.max(0, (total - 1) / 2);
            boolean intermittent = OsmTags.isTrue(way.tags().get("intermittent"));
            WaterFeature wf = new WaterFeature(way.id(), kind, intermittent ? 1 : depth, false, intermittent, way.tags().get("name"));
            wf.surfaceY = Integer.MIN_VALUE; // follow terrain
            r.waters.add(wf);
            short code = (short) r.waters.size();
            List<double[]> pts = toBlockRing(way.points());
            // A river or canal line is a channel as deep as a river of its width, deepest along its middle.
            boolean channel = waterBeds != null && cfg.realWaterDepths && !intermittent
                    && (kind == WaterFeature.Kind.RIVER || kind == WaterFeature.Kind.CANAL);
            double channelDepth = channel ? com.berg.orbis.water.WaterBeds.riverDepthM(OsmTags.parseLength(way.tags().get("width"), defaultWidth)) / cfg.metersPerBlock : 0;
            if (channel && r.bedDepth == null) r.bedDepth = new short[r.stride * r.stride];
            walkThickLine(r, pts, half, (idx, x, z, dist, along, dir) -> {
                if (idx >= 0 && r.water[idx] == 0) {
                    r.water[idx] = code;
                    if (channel) {
                        double across = Math.min(1, dist / (half + 1.0));
                        r.bedDepth[idx] = (short) Math.max(1, Math.round(channelDepth * (1 - across * across)));
                    }
                }
            });
        }
    }

    /**
     * How far each sea column (the coastline's sea, or water mapped at sea level) lies from the shore, so the sea bed
     * can shelve down from the shore where the elevation data has no depth (it records the sea surface as 0 m).
     */
    private static void measureSeaShore(RegionRaster r) {
        int n = r.stride * r.stride;
        boolean[] inside = new boolean[n];
        boolean any = false;
        for (int idx = 0; idx < n; idx++) {
            boolean s = r.sea[idx] != 0;
            if (!s) {
                WaterFeature wf = r.waterAt(idx);
                s = wf != null && wf.atSeaLevel;
            }
            inside[idx] = s;
            any |= s;
        }
        if (!any) return;
        float[] d = com.berg.orbis.water.WaterBeds.distanceInside(inside, r.stride, r.stride);
        r.seaShore = new byte[n];
        for (int idx = 0; idx < n; idx++) {
            if (inside[idx]) r.seaShore[idx] = (byte) Math.max(1, Math.min(127, Math.round(d[idx])));
        }
    }

    /**
     * natural=coastline ways have land on the LEFT and water on the RIGHT (in walking direction). They are drawn as
     * a 4-connected barrier, which splits the region into pieces; every piece then takes the side most of the
     * samples along its coastline point to (a sample 1.6 blocks to the right votes sea, to the left land). A vote,
     * not a race: a breakwater or pier a few blocks wide put "land" samples on its far side, in the sea, and the
     * flood fill that used to label cells by whichever side reached them first ran from there across the sea,
     * leaving dry sea floor (Bergen 1:2, 2 Oct 2026); the sea, bordered by coastline all round, outvotes them.
     * A piece with no samples (cut off by the region's edge) is sea where it lies below sea level. Coastline that
     * only passes near the region (ways from the map cells around it, no point inside) gives no samples at all:
     * the region is then left to the heights, as one with no coastline.
     */
    private void rasterizeCoastline(RegionRaster r, List<OsmWay> ways) {
        List<OsmWay> coast = new ArrayList<>();
        for (OsmWay w : ways) {
            if ("coastline".equals(w.tags().get("natural"))) coast.add(w);
        }
        if (coast.isEmpty()) {
            seaFromHeights(r);
            return;
        }

        int stride = r.stride, n = stride * stride;
        byte[] barrier = new byte[n];
        for (OsmWay w : coast) {
            List<double[]> pts = toBlockRing(w.points());
            walkThinLine4(r, pts, (idx, x, z) -> barrier[idx] = 1);
        }
        // The pieces between the coastlines (4-connected, as the barrier is).
        int[] piece = new int[n];
        int pieces = 0;
        int[] stack = new int[n];
        for (int i = 0; i < n; i++) {
            if (barrier[i] != 0 || piece[i] != 0) continue;
            int id = ++pieces, top = 0;
            piece[i] = id;
            stack[top++] = i;
            while (top > 0) {
                int idx = stack[--top];
                int x = idx % stride, z = idx / stride;
                if (x > 0 && barrier[idx - 1] == 0 && piece[idx - 1] == 0) { piece[idx - 1] = id; stack[top++] = idx - 1; }
                if (x < stride - 1 && barrier[idx + 1] == 0 && piece[idx + 1] == 0) { piece[idx + 1] = id; stack[top++] = idx + 1; }
                if (z > 0 && barrier[idx - stride] == 0 && piece[idx - stride] == 0) { piece[idx - stride] = id; stack[top++] = idx - stride; }
                if (z < stride - 1 && barrier[idx + stride] == 0 && piece[idx + stride] == 0) { piece[idx + stride] = id; stack[top++] = idx + stride; }
            }
        }
        int[] seaVotes = new int[pieces + 1], landVotes = new int[pieces + 1];
        long votes = 0;
        for (OsmWay w : coast) {
            List<double[]> pts = toBlockRing(w.points());
            for (int i = 0; i + 1 < pts.size(); i++) {
                double[] a = pts.get(i), b = pts.get(i + 1);
                double dx = b[0] - a[0], dz = b[1] - a[1];
                double len = Math.hypot(dx, dz);
                if (len < 1e-6) continue;
                double ux = dx / len, uz = dz / len;
                // Right-hand normal in Minecraft coordinates (see class notes).
                double nx = -uz, nz = ux;
                int samples = Math.max(1, (int) (len / 2.0));
                for (int s = 0; s <= samples; s++) {
                    double t = (double) s / samples;
                    double px = a[0] + dx * t, pz = a[1] + dz * t;
                    int sea = r.index((int) Math.floor(px + nx * 1.6), (int) Math.floor(pz + nz * 1.6));
                    if (sea >= 0 && piece[sea] != 0) { seaVotes[piece[sea]]++; votes++; }
                    int land = r.index((int) Math.floor(px - nx * 1.6), (int) Math.floor(pz - nz * 1.6));
                    if (land >= 0 && piece[land] != 0) { landVotes[piece[land]]++; votes++; }
                }
            }
        }
        if (votes == 0) {
            seaFromHeights(r); // no coastline in the region itself: the heights decide
            return;
        }
        r.hasCoastline = true;
        for (int i = 0; i < n; i++) {
            if (barrier[i] != 0) {
                r.sea[i] = 1; // the line itself is the water's edge
                continue;
            }
            int p = piece[i];
            if (seaVotes[p] > landVotes[p]) {
                r.sea[i] = 1;
            } else if (seaVotes[p] == landVotes[p] && cfg.seaFromElevation) {
                double y = elevation.sample(r.originX + i % stride, r.originZ + i / stride);
                if (!Double.isNaN(y) && y < cfg.seaLevelY) r.sea[i] = 1;
            }
        }
    }

    // =====================================================================
    // Roads, paths, rails, runways, piers
    // =====================================================================

    /** Approach ramps and lifted decks climb one block per this many blocks along the way. */
    private static final double RAMP_GRADE = 1.0 / 8.0;
    /** How far (blocks) back along an approach road a ramp may reach. */
    private static final int RAMP_REACH = 96;

    private record Drawn(RoadFeature rf, short code, List<double[]> pts, double length) {}


    /**
     * Road grading: half-window of the smoothing along a road, and how far the road may stand off the ground at its
     * centreline. Both are small on purpose: grading is there to keep a road level across its width (no sideways
     * tilt, no terraces within the carriageway); a long smoothing window lifted paths out of dips and sank them
     * into crests, and one-block steps along a road are left to the ramps.
     */
    private static final double GRADE_WINDOW_M = 4.0;
    private static final double GRADE_MAX_OFFSET = 1.0;
    /** How far beyond a road's edges the road bed is looked for (a mapped line can be metres off the real street). */
    private static final double GRADE_SEARCH_M = 6.0;

    /**
     * The graded height of a ground road along its length, one value per block: the ground under the centreline,
     * smoothed over about 4 m either way, pinned to the ground at both end nodes (so every way meeting at a junction
     * arrives at the same height) and never more than one block off the ground at the centreline.
     */
    private double[] gradeProfile(Drawn g, boolean freeStart, boolean freeEnd) {
        List<double[]> pts = g.pts();
        int n = (int) Math.ceil(g.length()) + 1;
        double[] raw = new double[n];
        int half = g.rf().halfWidth + (g.rf().sidewalk ? 1 : 0);
        int span = half + Math.max(2, (int) Math.round(mapper.blocks(GRADE_SEARCH_M)));
        double[] cross = new double[2 * span + 1];
        int seg = 0;
        double segStart = 0;
        double last = Double.NaN;
        for (int a = 0; a < n; a++) {
            double s = Math.min(a, g.length());
            while (seg + 2 < pts.size()) {
                double[] p0 = pts.get(seg), p1 = pts.get(seg + 1);
                double len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
                if (segStart + len >= s) break;
                segStart += len;
                seg++;
            }
            double[] p0 = pts.get(seg), p1 = pts.get(Math.min(seg + 1, pts.size() - 1));
            double len = Math.max(1e-9, Math.hypot(p1[0] - p0[0], p1[1] - p0[1]));
            double t = Math.max(0, Math.min(1, (s - segStart) / len));
            double ux = (p1[0] - p0[0]) / len, uz = (p1[1] - p0[1]) / len;
            double e = roadBed(p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t, -uz, ux, half, span, cross);
            if (Double.isNaN(e)) e = last;
            raw[a] = e;
            if (!Double.isNaN(e)) last = e;
        }
        double first = Double.NaN;
        for (double v : raw) if (!Double.isNaN(v)) { first = v; break; }
        if (Double.isNaN(first)) return null;
        for (int a = 0; a < n && Double.isNaN(raw[a]); a++) raw[a] = first;
        double node0 = elevationAt(pts.get(0)[0], pts.get(0)[1]);
        double node1 = elevationAt(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]);
        if (Double.isNaN(node0)) node0 = raw[0];
        if (Double.isNaN(node1)) node1 = raw[n - 1];
        // An end at a bridge or tunnel is left to its ramp or cutting: pinned to the portal node's ground (up on the
        // slope), the road's last cross-section stood as a ridge above the cutting.
        boolean pin0 = !freeStart, pin1 = !freeEnd;
        int w = Math.max(2, (int) Math.round(mapper.blocks(GRADE_WINDOW_M)));
        // The ends meet the ground at their nodes (shared by every way there); the blend to them is kept gradual.
        int pinReach = Math.max(4, w);
        double[] profile = new double[n];
        if (n - 1 < 2 * pinReach) {
            // A short way (a link between two junctions): a straight line between its two node heights.
            double e0 = pin0 ? node0 : raw[0], e1 = pin1 ? node1 : raw[n - 1];
            for (int a = 0; a < n; a++) profile[a] = n == 1 ? e0 : e0 + (e1 - e0) * a / (double) (n - 1);
            limitSlope(profile, maxRoadSlope(), pin0, pin1);
            return profile;
        }
        double[] prefix = new double[n + 1];
        for (int a = 0; a < n; a++) prefix[a + 1] = prefix[a] + raw[a];
        for (int a = 0; a < n; a++) {
            int lo = Math.max(0, a - w), hi = Math.min(n - 1, a + w);
            double v = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
            v = Math.max(raw[a] - GRADE_MAX_OFFSET, Math.min(raw[a] + GRADE_MAX_OFFSET, v));
            double w0 = Math.max(0, 1 - a / (double) pinReach), w1 = Math.max(0, 1 - (n - 1 - a) / (double) pinReach);
            if (pin0 && w0 > 0) v += w0 * (node0 - v);
            if (pin1 && w1 > 0) v += w1 * (node1 - v);
            profile[a] = v;
        }
        limitSlope(profile, maxRoadSlope(), pin0, pin1);
        return profile;
    }

    /**
     * The steepest a graded road may climb, in blocks per block along it: a block per block (45 degrees, far past
     * any real street) up to 1:2, half the metres per block beyond (a 50% grade: at 1:12 a steep street really
     * climbs several blocks per block). Steeper jumps came from the road-bed search switching strips between two
     * samples; rounded, they showed as a sawtooth of two-block steps across diagonal streets.
     */
    private double maxRoadSlope() {
        return Math.max(1.0, 0.5 * cfg.metersPerBlock);
    }

    /**
     * Evens out any step between consecutive samples that is steeper than {@code s}, moving both sides towards each
     * other (a pinned end stays at its junction height), then clamps forwards as the guarantee.
     */
    static void limitSlope(double[] p, double s, boolean fix0, boolean fix1) {
        int last = p.length - 1;
        for (int iter = 0; iter < 64; iter++) {
            boolean changed = false;
            for (int i = 0; i < last; i++) {
                double d = p[i + 1] - p[i];
                if (Math.abs(d) <= s + 1e-9) continue;
                double excess = (Math.abs(d) - s) * Math.signum(d);
                boolean lock0 = fix0 && i == 0, lock1 = fix1 && i + 1 == last;
                if (lock0 && lock1) continue;
                if (lock0) p[i + 1] -= excess;
                else if (lock1) p[i] += excess;
                else {
                    p[i] += excess / 2;
                    p[i + 1] -= excess / 2;
                }
                changed = true;
            }
            if (!changed) return;
        }
        for (int i = 0; i < last; i++) p[i + 1] = Math.max(p[i] - s, Math.min(p[i] + s, p[i + 1]));
    }

    /**
     * The height of the road bed at a point of a road: the ground is sampled across the road and a little beyond it
     * (perpendicular direction px, pz), and the flattest strip as wide as the road, preferring one near the mapped
     * line, gives its middle height. A street is a flat strip cut into the slope, so a line drawn a few metres off
     * the real street, on the bank beside it, still finds the street; on even ground the centre wins. NaN when the
     * ground is unknown here.
     */
    private double roadBed(double cx, double cz, double px, double pz, int half, int span, double[] cross) {
        double centre = elevationAt(cx, cz);
        if (Double.isNaN(centre)) return centre;
        if (half < 1) return centre; // a one-block path is its own bed
        for (int i = -span; i <= span; i++) {
            double v = elevationAt(cx + px * i, cz + pz * i);
            cross[i + span] = Double.isNaN(v) ? centre : v;
        }
        int len = 2 * half + 1;
        int bestStart = span - half;
        double bestScore = Double.MAX_VALUE;
        for (int start = 0; start + len <= cross.length; start++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = start; i < start + len; i++) {
                lo = Math.min(lo, cross[i]);
                hi = Math.max(hi, cross[i]);
            }
            double score = (hi - lo) + 0.25 * Math.abs(start + half - span);
            if (score < bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        double[] strip = java.util.Arrays.copyOfRange(cross, bestStart, bestStart + len);
        java.util.Arrays.sort(strip);
        return strip[len / 2];
    }

    /** Identity of a way endpoint: connected ways share the OSM node, so they share exact coordinates. */
    private static long nodeKey(double[] p) {
        return (Math.round(p[0] * 16) << 32) ^ (Math.round(p[1] * 16) & 0xffffffffL);
    }

    /** NVDB widths and lanes for the region, or null when off, outside Norway, at coarse scale or unavailable. */
    private NvdbRoads.Index nvdbIndex(RegionRaster r, List<NvdbRoads.Segment> prefetched) {
        List<NvdbRoads.Segment> segs = prefetched;
        if (segs == null) {
            NvdbRoads.Store store = nvdb;
            double[] a = mapper.toLatLonExact(r.originX, r.originZ + r.stride);
            double[] b = mapper.toLatLonExact(r.originX + r.stride, r.originZ);
            double south = Math.min(a[0], b[0]), north = Math.max(a[0], b[0]), west = Math.min(a[1], b[1]), east = Math.max(a[1], b[1]);
            if (!wantsNvdb(south, west, north, east)) return null;
            segs = roadSegments(south, west, north, east);
        }
        if (segs.isEmpty()) return null;
        NvdbRoads.Index index = new NvdbRoads.Index(segs, mapper);
        return index.isEmpty() ? null : index;
    }

    /**
     * Marks a rail centreline cell that a street has taken over (a tram in the road): the painter lays the rail
     * on the street's surface and the transit pass joins it up like any other rail.
     */
    private static void embedRail(RegionRaster r, int idx, int octant, int along) {
        int dec = r.decor[idx];
        if (dec != 0 && dec != DecorType.LAMP.code() && dec != DecorType.EMBEDDED_RAIL.code()) return;
        r.decor[idx] = DecorType.EMBEDDED_RAIL.code();
        r.decorData[idx] = (byte) ((octant & 7) | (Math.floorMod(along, 10) == 0 ? 8 : 0));
    }

    /** A building footprint with its floor height, known before the roads are drawn so a tunnel can dive under it. */
    private record Cover(long id, List<List<double[]>> rings, double minX, double minZ, double maxX, double maxZ, int baseY) {
        boolean covers(double px, double pz) {
            return px >= minX && px <= maxX && pz >= minZ && pz <= maxZ && pointInRings(px, pz, rings);
        }
    }

    /** Footprints and floor heights of the buildings (same base rule as {@link #rasterizeBuilding}). */
    private List<Cover> buildingCovers(List<OsmArea> areas) {
        List<Cover> out = new ArrayList<>();
        for (OsmArea a : areas) {
            String b = a.tags().get("building");
            if (b == null || "no".equals(b) || a.outers().isEmpty()) continue;
            List<List<double[]>> rings = new ArrayList<>();
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            double base = Double.MAX_VALUE;
            int budget = 32;
            for (List<LatLon> ring : a.outers()) {
                List<double[]> pts = toBlockRing(ring);
                rings.add(pts);
                int step = Math.max(1, pts.size() / 16);
                for (int i = 0; i < pts.size(); i++) {
                    double[] p = pts.get(i);
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minZ = Math.min(minZ, p[1]);
                    maxZ = Math.max(maxZ, p[1]);
                    if (i % step == 0 && budget > 0) {
                        budget--;
                        double e = elevationAt(p[0], p[1]);
                        if (!Double.isNaN(e)) base = Math.min(base, e);
                    }
                }
            }
            LatLon c = a.centroid();
            if (c != null) {
                double[] cb = mapper.toBlockExact(c.lat(), c.lon());
                double e = elevationAt(cb[0], cb[1]);
                if (!Double.isNaN(e)) base = Math.min(base, e);
            }
            if (base == Double.MAX_VALUE) continue;
            out.add(new Cover(a.id(), rings, minX, minZ, maxX, maxZ, (int) Math.round(Math.max(base, cfg.seaLevelY - 1))));
        }
        return out;
    }

    private void rasterizeTransport(RegionRaster r, List<OsmWay> ways, NvdbRoads.Index nvdbIndex, List<Cover> covers) {
        List<OsmWay> lines = new ArrayList<>();
        for (OsmWay w : ways) {
            if (transportKind(w.tags()) != null) lines.add(w);
        }
        // Ground-level ways first (minor before major so major roads win at
        // junctions), then tunnels, then bridges from the lowest layer up, so
        // every deck can see exactly what it has to clear or dive under.
        lines.sort(Comparator.comparingInt((OsmWay w) -> OsmTags.has(w.tags(), "bridge") ? 2 : isTunnelWay(w.tags()) ? 1 : 0)
                .thenComparingInt(w -> OsmTags.has(w.tags(), "bridge") ? OsmTags.parseInt(w.tags().get("layer"), 0)
                        : isTunnelWay(w.tags()) ? -OsmTags.parseInt(w.tags().get("layer"), 0) : 0)
                .thenComparingInt(w -> transportPriority(w.tags())));

        BlockState marking = switch (cfg.roadCenterLineColour.toLowerCase(Locale.ROOT)) {
            case "yellow" -> Blocks.CONCRETE.yellow().defaultBlockState();
            case "none", "off", "false" -> null;
            default -> Blocks.CONCRETE.white().defaultBlockState();
        };

        // Deck height at each bridge end, so the ways leading up to it get ramps;
        // road height at each sunken tunnel portal, so they get cuttings.
        Map<Long, Double> nodeDeck = new HashMap<>();
        Map<Long, Double> nodeCut = new HashMap<>();
        // Ends of bridges and tunnels: a graded ground road is not pinned to the ground there (the approach ramp or
        // the cutting sets its height), since the node of a portal lies on the slope above the road.
        java.util.Set<Long> structureEnds = new java.util.HashSet<>();
        // Where one tunnel way hands over to the next (OSM splits long tunnels at junctions and attribute
        // changes), the shared node is inside the mountain, not a portal: every way through it must meet at one
        // floor height. A first pass computes what each way can reach there on its own; the lowest value wins
        // for all of them, so no way ends up climbing towards the surface at a node another way keeps deep.
        Map<Long, Integer> tunnelEndCount = new HashMap<>();
        java.util.Set<Long> groundEnds = new java.util.HashSet<>();
        List<OsmWay> tunnelWays = new ArrayList<>();
        java.util.Set<Long> knownWays = new java.util.HashSet<>();
        for (OsmWay w : lines) {
            if (w.points().size() < 2) continue;
            knownWays.add(w.id());
            List<double[]> p = toBlockRing(w.points());
            long a = nodeKey(p.get(0)), b = nodeKey(p.get(p.size() - 1));
            if (isRealRoadTunnel(w)) {
                tunnelEndCount.merge(a, 1, Integer::sum);
                tunnelEndCount.merge(b, 1, Integer::sum);
                tunnelWays.add(w);
            } else {
                groundEnds.add(a);
                groundEnds.add(b);
            }
        }
        // A tunnel end near or beyond the edge of this region's data: ask the map for the ways around that node,
        // so the ways that continue in the next region count here too (same answer in both regions).
        WayLookup lookup = wayLookup;
        Map<Long, List<OsmArea>> nodeAreas = new HashMap<>();
        if (lookup != null) {
            int edge = NODE_COVER_RADIUS + 4;
            java.util.Set<Long> asked = new java.util.HashSet<>();
            int ownWays = tunnelWays.size();
            for (OsmWay w : new ArrayList<>(tunnelWays)) {
                List<double[]> p = toBlockRing(w.points());
                for (double[] end : new double[][]{p.get(0), p.get(p.size() - 1)}) {
                    boolean nearEdge = end[0] < r.originX + edge || end[0] > r.originX + r.stride - edge
                            || end[1] < r.originZ + edge || end[1] > r.originZ + r.stride - edge;
                    if (!nearEdge || !asked.add(nodeKey(end))) continue;
                    double[] ll = mapper.toLatLon((int) Math.floor(end[0]), (int) Math.floor(end[1]));
                    OsmData near;
                    try {
                        near = lookup.dataAround(ll[0], ll[1], NODE_COVER_RADIUS * cfg.metersPerBlock);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (near == null) continue;
                    nodeAreas.put(nodeKey(end), near.areas());
                    List<OsmWay> around = near.ways();
                    for (OsmWay o : around) {
                        if (transportKind(o.tags()) == null || o.points().size() < 2 || !knownWays.add(o.id())) continue;
                        List<double[]> q = toBlockRing(o.points());
                        long a = nodeKey(q.get(0)), b = nodeKey(q.get(q.size() - 1));
                        if (isRealRoadTunnel(o)) {
                            tunnelEndCount.merge(a, 1, Integer::sum);
                            tunnelEndCount.merge(b, 1, Integer::sum);
                            tunnelWays.add(o);
                        } else {
                            groundEnds.add(a);
                            groundEnds.add(b);
                        }
                    }
                }
            }
            // The ways that lookup added end somewhere else again, often far outside this raster: the buildings
            // there shape the floor those ways bring to the nodes inside, so fetch them too (buildings only; the
            // ways around those far ends are not needed).
            for (OsmWay w : new ArrayList<>(tunnelWays.subList(ownWays, tunnelWays.size()))) {
                List<double[]> p = toBlockRing(w.points());
                for (double[] end : new double[][]{p.get(0), p.get(p.size() - 1)}) {
                    boolean nearEdge = end[0] < r.originX + edge || end[0] > r.originX + r.stride - edge
                            || end[1] < r.originZ + edge || end[1] > r.originZ + r.stride - edge;
                    if (!nearEdge || !asked.add(nodeKey(end))) continue;
                    double[] ll = mapper.toLatLon((int) Math.floor(end[0]), (int) Math.floor(end[1]));
                    OsmData near;
                    try {
                        near = lookup.dataAround(ll[0], ll[1], NODE_COVER_RADIUS * cfg.metersPerBlock);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (near != null) nodeAreas.put(nodeKey(end), near.areas());
                }
            }
        }
        // Buildings near an interior node: a building's floor is a lower ceiling than the ground, so the floor a
        // way needs at the node depends on them, and every region must see the same set: those of this region
        // within the radius plus whatever the lookup returned around the node.
        Map<Long, List<Cover>> nodeCovers = new HashMap<>();
        for (OsmWay w : tunnelWays) {
            List<double[]> p = toBlockRing(w.points());
            for (double[] end : new double[][]{p.get(0), p.get(p.size() - 1)}) {
                long k = nodeKey(end);
                if (nodeCovers.containsKey(k)) continue;
                List<Cover> near = new ArrayList<>();
                java.util.Set<Long> ids = new java.util.HashSet<>();
                for (Cover c : covers) {
                    if (c.maxX() < end[0] - NODE_COVER_RADIUS || c.minX() > end[0] + NODE_COVER_RADIUS
                            || c.maxZ() < end[1] - NODE_COVER_RADIUS || c.minZ() > end[1] + NODE_COVER_RADIUS) continue;
                    if (ids.add(c.id())) near.add(c);
                }
                List<OsmArea> extra = nodeAreas.get(k);
                if (extra != null && cfg.generateBuildings) {
                    List<OsmArea> fresh = new ArrayList<>();
                    for (OsmArea a : extra) if (!ids.contains(a.id())) fresh.add(a);
                    for (Cover c : buildingCovers(fresh)) {
                        if (c.maxX() < end[0] - NODE_COVER_RADIUS || c.minX() > end[0] + NODE_COVER_RADIUS
                                || c.maxZ() < end[1] - NODE_COVER_RADIUS || c.minZ() > end[1] + NODE_COVER_RADIUS) continue;
                        if (ids.add(c.id())) near.add(c);
                    }
                }
                nodeCovers.put(k, near);
            }
        }
        Map<Long, Double> nodeTunnel = new HashMap<>();
        for (OsmWay w : tunnelWays) {
            List<double[]> p = toBlockRing(w.points());
            long a = nodeKey(p.get(0)), b = nodeKey(p.get(p.size() - 1));
            boolean inA = interiorTunnelNode(a, tunnelEndCount, groundEnds), inB = interiorTunnelNode(b, tunnelEndCount, groundEnds);
            if (!inA && !inB) continue;
            List<Cover> endCovers = new ArrayList<>(nodeCovers.getOrDefault(a, List.of()));
            for (Cover c : nodeCovers.getOrDefault(b, List.of())) if (!endCovers.contains(c)) endCovers.add(c);
            Map<String, String> wt = w.tags();
            int preHalf = roadHalfWidth(w, transportKind(wt), wt.get("highway"), wt.get("railway"), nvdbIndex);
            double len = 0;
            for (int i = 0; i + 1 < p.size(); i++) len += Math.hypot(p.get(i + 1)[0] - p.get(i)[0], p.get(i + 1)[1] - p.get(i)[1]);
            double e0 = elevationAt(p.get(0)[0], p.get(0)[1]);
            double e1 = elevationAt(p.get(p.size() - 1)[0], p.get(p.size() - 1)[1]);
            if (Double.isNaN(e0)) e0 = Double.isNaN(e1) ? cfg.seaLevelY : e1;
            if (Double.isNaN(e1)) e1 = e0;
            // Terrain along the whole way at the way's real width, plus the buildings near both ends, but no streets
            // (only known inside this raster). Both ends are treated as inside the rock, whatever they are: the
            // value this way brings to the node then depends on nothing this region knows differently from the
            // next one (whether a far end is a portal is only known near that end). A portal end is merely
            // assumed a little deeper than it will be.
            double[] pre = tunnelProfile(r, null, p, preHalf, Math.max(1e-6, len), e0 - tunnelCover, e1 - tunnelCover,
                    other -> false, endCovers, 0, 0);
            if (inA) nodeTunnel.merge(a, pre[0], Math::min);
            if (inB) nodeTunnel.merge(b, pre[pre.length - 1], Math::min);
            if (DEBUG_TUNNELS) {
                System.out.println(String.format(Locale.ROOT, "[orbis] tunnel pre way %d region %d,%d len %.0f half %d covers %d (A %d, B %d): A %.0f,%.0f in=%b e=%.1f pre=%.1f | B %.0f,%.0f in=%b e=%.1f pre=%.1f",
                        w.id(), r.regionX, r.regionZ, len, preHalf, endCovers.size(), nodeCovers.getOrDefault(a, List.of()).size(), nodeCovers.getOrDefault(b, List.of()).size(),
                        p.get(0)[0], p.get(0)[1], inA, e0, pre[0], p.get(p.size() - 1)[0], p.get(p.size() - 1)[1], inB, e1, pre[pre.length - 1]));
            }
        }
        List<Drawn> groundWays = new ArrayList<>();
        List<Drawn> bridges = new ArrayList<>();
        // End nodes of every drawn way: a road that shares an end node with a
        // bridge is its approach, not something the bridge crosses.
        Map<RoadFeature, long[]> wayEnds = new HashMap<>();

        for (OsmWay way : lines) {
            Map<String, String> tags = way.tags();
            RoadFeature.Kind kind = transportKind(tags);
            if (kind == null) continue;
            String highway = tags.get("highway");
            String railway = tags.get("railway");
            if (cfg.majorRoadsOnly && !isMajorTransport(kind, highway)) continue;
            boolean bridge = OsmTags.has(tags, "bridge");
            boolean tunnel = isTunnelWay(tags);
            // A passage through/under a building stays at street level; a real tunnel dives.
            boolean passage = "covered".equals(tags.get("tunnel")) || "building_passage".equals(tags.get("tunnel"));
            int layer = OsmTags.parseInt(tags.get("layer"), 0);
            if (bridge && layer < 1) layer = 1; // an untagged bridge is still above the ground
            if (tunnel && !passage && layer > -1) layer = -1; // and an untagged tunnel is below it
            if (kind == RoadFeature.Kind.RAIL && (tunnel || layer < 0)) continue; // subways etc.
            if (tunnel && layer < -1 && kind != RoadFeature.Kind.ROAD) continue;

            int lanes = OsmTags.parseInt(tags.get("lanes"), -1);
            if (lanes < 0 && nvdbIndex != null && kind == RoadFeature.Kind.ROAD) {
                double[] m = halfForOneWay(way, nvdbIndex.match(toBlockRing(way.points())));
                if (m[1] > 0) lanes = (int) m[1];
            }
            int total = roadWidthBlocks(way, kind, highway, railway, nvdbIndex);
            int half = Math.max(0, (total - 1) / 2);

            boolean sidewalk = false;
            if (kind == RoadFeature.Kind.ROAD && cfg.roadSidewalks && !bridge && !tunnel) {
                String sw = tags.get("sidewalk");
                if (sw != null) {
                    sidewalk = !(sw.equals("no") || sw.equals("none") || sw.equals("separate"));
                } else {
                    sidewalk = switch (highway == null ? "" : highway) {
                        case "residential", "tertiary", "secondary", "primary", "living_street", "unclassified" -> true;
                        default -> false;
                    };
                }
            }
            BlockState surface = Materials.roadSurface(kind, highway, tags.get("surface"), tags.get("tracktype"));
            BlockState sidewalkBlock = Materials.sidewalkBlock(surface);
            boolean centreLine = kind == RoadFeature.Kind.ROAD && marking != null && total >= 5
                    && !"service".equals(highway) && !"living_street".equals(highway) && !"track".equals(highway)
                    && !OsmTags.isTrue(tags.get("oneway")) && surface.is(Blocks.CONCRETE.gray());
            boolean edgeLines = kind == RoadFeature.Kind.ROAD && marking != null && total >= 9 && surface.is(Blocks.CONCRETE.gray())
                    && (highway != null && (highway.startsWith("motorway") || highway.startsWith("trunk") || highway.equals("primary")));
            if (kind == RoadFeature.Kind.RUNWAY) {
                centreLine = true;
                edgeLines = false;
            }
            BlockState markBlock = kind == RoadFeature.Kind.TAXIWAY ? Blocks.CONCRETE.yellow().defaultBlockState()
                    : kind == RoadFeature.Kind.RUNWAY ? Blocks.CONCRETE.white().defaultBlockState() : marking;

            RoadFeature rf = new RoadFeature(way.id(), kind, highway, railway, half, bridge, tunnel, layer, sidewalk,
                    OsmTags.isTrue(tags.get("oneway")), lanes, OsmTags.isTrue(tags.get("lit")), transportPriority(tags),
                    surface, sidewalkBlock, markBlock, centreLine, edgeLines, tags.get("name"));
            r.roads.add(rf);
            short code = (short) r.roads.size();

            List<double[]> pts = toBlockRing(way.points());
            double totalLen = 0;
            for (int i = 0; i + 1 < pts.size(); i++) totalLen += Math.hypot(pts.get(i + 1)[0] - pts.get(i)[0], pts.get(i + 1)[1] - pts.get(i)[1]);
            final double wayLen = Math.max(1e-6, totalLen);

            final int fullHalf = half + (sidewalk ? 1 : 0);
            final long k0 = nodeKey(pts.get(0)), k1 = nodeKey(pts.get(pts.size() - 1));
            wayEnds.put(rf, new long[]{k0, k1});
            if (bridge || tunnel) {
                structureEnds.add(k0);
                structureEnds.add(k1);
            }
            final java.util.function.Predicate<RoadFeature> isApproach = other -> {
                long[] ends = wayEnds.get(other);
                return ends != null && (ends[0] == k0 || ends[0] == k1 || ends[1] == k0 || ends[1] == k1);
            };

            // Deck height for bridges / tunnels / piers. Tunnels and piers
            // interpolate between the terrain at both ends; a bridge deck is
            // additionally lifted wherever it must clear what it crosses.
            double yStart = Double.NaN, yEnd = Double.NaN;
            double[] deckProfile = null;
            boolean explicitY = bridge || tunnel || kind == RoadFeature.Kind.PIER;
            if (explicitY) {
                double e0 = elevationAt(pts.get(0)[0], pts.get(0)[1]);
                double e1 = elevationAt(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]);
                if (Double.isNaN(e0)) e0 = Double.isNaN(e1) ? cfg.seaLevelY : e1;
                if (Double.isNaN(e1)) e1 = e0;
                if (kind == RoadFeature.Kind.PIER) {
                    double base = Math.max(cfg.seaLevelY, Math.min(e0, e1));
                    yStart = yEnd = base + 1;
                } else if (bridge) {
                    // Ends: a little above both banks, or level with a bridge
                    // that already ends at the same node.
                    Double d0 = nodeDeck.get(k0), d1 = nodeDeck.get(k1);
                    yStart = d0 != null ? Math.max(d0, e0 + 1) : e0 + 1;
                    yEnd = d1 != null ? Math.max(d1, e1 + 1) : e1 + 1;
                    deckProfile = bridgeProfile(r, rf, pts, fullHalf, wayLen, yStart, yEnd, isApproach);
                    yStart = deckProfile[0];
                    yEnd = deckProfile[deckProfile.length - 1];
                    nodeDeck.merge(k0, yStart, Math::max);
                    nodeDeck.merge(k1, yEnd, Math::max);
                } else {
                    yStart = e0;
                    yEnd = e1;
                    if (!passage) {
                        boolean in0 = interiorTunnelNode(k0, tunnelEndCount, groundEnds), in1 = interiorTunnelNode(k1, tunnelEndCount, groundEnds);
                        double s0 = in0 ? nodeTunnel.getOrDefault(k0, e0 - tunnelCover) : e0;
                        double s1 = in1 ? nodeTunnel.getOrDefault(k1, e1 - tunnelCover) : e1;
                        deckProfile = tunnelProfile(r, rf, pts, fullHalf, wayLen, s0, s1, isApproach, covers, in0 ? 0 : portalMargin, in1 ? 0 : portalMargin);
                        // Every way through an interior node meets it at the agreed floor: whatever this way alone
                        // would dig deeper near the node (a street above it, a building the node pass did not see)
                        // is ramped back up to the node at the usual grade rather than left as a step.
                        // The ramp never lifts the floor above the straight line between the two ends: the other end
                        // stays where it is (a portal at ground level, or its own node floor) even when the two node
                        // floors are further apart than the grade allows over this way's length.
                        int last = deckProfile.length - 1;
                        for (int i = 0; i <= last; i++) {
                            double straight = last == 0 ? s0 : s0 + (s1 - s0) * ((double) i / last);
                            double floor = Double.NEGATIVE_INFINITY;
                            if (in0) floor = Math.max(floor, s0 - RAMP_GRADE * i);
                            if (in1) floor = Math.max(floor, s1 - RAMP_GRADE * (last - i));
                            deckProfile[i] = Math.max(deckProfile[i], Math.min(straight, floor));
                        }
                        yStart = deckProfile[0];
                        yEnd = deckProfile[deckProfile.length - 1];
                        if (DEBUG_TUNNELS) {
                            System.out.println(String.format(Locale.ROOT, "[orbis] tunnel way %d region %d,%d len %.0f: start in=%b e0=%.1f s0=%.1f y=%.1f | end in=%b e1=%.1f s1=%.1f y=%.1f",
                                    way.id(), r.regionX, r.regionZ, wayLen, in0, e0, s0, yStart, in1, e1, s1, yEnd));
                        }
                        // A portal below ground level: the approach road must dig down to it.
                        if (!in0 && yStart < e0 - 1) nodeCut.merge(k0, yStart, Math::min);
                        if (!in1 && yEnd < e1 - 1) nodeCut.merge(k1, yEnd, Math::min);
                    }
                }
            }
            final double fy0 = yStart, fy1 = yEnd;
            final double[] profile = deckProfile;
            final boolean isBridge = bridge, isTunnel = tunnel;

            walkThickLine(r, pts, fullHalf, (idx, x, z, dist, along, dir) -> {
                if (idx < 0) return;
                // A bridge deck ends square at its end nodes (the approach road
                // supplies the joint); the round cap would put railings across
                // the carriageway.
                if (isBridge && (along <= 1e-6 || along >= wayLen - 1e-6)) return;
                int d = (int) Math.round(dist);
                short yHere;
                if (profile != null) {
                    int a = (int) Math.max(0, Math.min(profile.length - 1, Math.round(along)));
                    yHere = (short) Math.round(profile[a]);
                } else if (explicitY) {
                    yHere = (short) Math.round(fy0 + (fy1 - fy0) * Math.min(1.0, along / wayLen));
                } else {
                    yHere = RegionRaster.NO_Y;
                }
                RoadFeature existing = r.roadAt(idx);
                if (existing == rf) {
                    // Same way, another segment/piece: keep whichever is closer to the centreline.
                    if (r.roadDistAt(idx) <= d) return;
                } else if (existing != null) {
                    if (existing.layer > rf.layer) {
                        if (isTunnel && !isApproach.test(existing)) {
                            // The tunnel keeps running under the street (or the shallower tunnel) above it.
                            r.ensureUnderLayers();
                            if (r.roadUnder[idx] == 0) {
                                r.roadUnder[idx] = code;
                                r.roadUnderDist[idx] = (byte) Math.min(127, d);
                                r.roadUnderY[idx] = yHere;
                                if (rf.layer < 0 && yHere != RegionRaster.NO_Y) r.minTunnelY = Math.min(r.minTunnelY, yHere);
                            }
                        }
                        return;
                    }
                    if (existing.layer == rf.layer) {
                        if (existing.priority > rf.priority) {
                            // A tram line drawn into a busier street: the street keeps the surface, the rail runs on it.
                            if (rf.isRail() && !existing.isRail() && d == 0 && yHere == RegionRaster.NO_Y) embedRail(r, idx, dir, (int) along);
                            return;
                        }
                        if (existing.priority == rf.priority && r.roadDistAt(idx) <= d) return;
                        if (existing.isRail() && !rf.isRail() && r.roadDistAt(idx) == 0 && r.roadY[idx] == RegionRaster.NO_Y) {
                            // A busier street drawn over a tram line: same thing, the other way round.
                            embedRail(r, idx, r.roadDirAt(idx), r.roadTAt(idx));
                        }
                    } else if (isBridge && !isApproach.test(existing)) {
                        // The street (or tunnel) below a flyover keeps its place.
                        r.ensureUnderLayers();
                        RoadFeature kept = r.roadUnderAt(idx);
                        // (a tunnel already stored under the street stays: it must not be cut by the flyover)
                        if (kept == null || (kept.layer <= existing.layer && !kept.tunnel)) {
                            r.roadUnder[idx] = r.road[idx];
                            r.roadUnderDist[idx] = r.roadDist[idx];
                            r.roadUnderY[idx] = r.roadY[idx];
                        }
                    }
                }
                r.road[idx] = code;
                r.roadDist[idx] = (byte) Math.min(127, d);
                r.roadT[idx] = (byte) (((int) along) & 0xFF);
                r.roadDir[idx] = (byte) dir;
                r.roadY[idx] = yHere;
                if (isTunnel && rf.layer < 0 && yHere != RegionRaster.NO_Y) r.minTunnelY = Math.min(r.minTunnelY, yHere);
                if (isBridge && d == 0 && (((int) along) % 12) == 6 && r.decor[idx] == 0
                        && (r.roadUnder == null || r.roadUnder[idx] == 0)) {
                    r.decor[idx] = DecorType.BRIDGE_PILLAR.code();
                }
                // Street lamps every 24 m on both edges of lit / urban streets, so
                // towns are lit at night the way they really are and hostile mobs
                // keep to the dark places.
                if (cfg.streetLights && kind == RoadFeature.Kind.ROAD && !isBridge && !isTunnel && d == fullHalf && fullHalf >= 2
                        && (((int) along) % 24) == 12 && r.decor[idx] == 0
                        && (rf.lit || urbanCover(r.landCoverAt(idx)) || "residential".equals(highway) || "living_street".equals(highway))) {
                    r.decor[idx] = DecorType.LAMP.code();
                }
            });
            if (!explicitY && kind != RoadFeature.Kind.STEPS) {
                groundWays.add(new Drawn(rf, code, pts, wayLen));
            }
            if (isBridge) bridges.add(new Drawn(rf, code, pts, wayLen));
        }

        // Road grading: a ground road is level across its width at the height of a smoothed profile along it,
        // instead of following the ground column by column (which tilts it sideways and terraces it on hillsides).
        // Bridge approach ramps and tunnel cuttings below still override it where they apply.
        java.util.Map<Drawn, double[]> graded = new java.util.IdentityHashMap<>();
        if (cfg.roadGrading) {
            // Each cell takes the height of the nearest piece of its road (as it took its surface): on a bend the
            // first piece to reach a cell is not always the nearest, and two pieces meet at different distances along
            // the road, which left a step across Holbergsallmenningen and other bent streets.
            float[] gradeDist = new float[r.stride * r.stride];
            Arrays.fill(gradeDist, Float.MAX_VALUE);
            for (Drawn g : groundWays) {
                if (g.rf().isRail() || g.length() < 2) continue;
                double[] profile = gradeProfile(g, structureEnds.contains(nodeKey(g.pts().get(0))),
                        structureEnds.contains(nodeKey(g.pts().get(g.pts().size() - 1))));
                if (profile == null) continue;
                graded.put(g, profile);
                int half = g.rf().halfWidth + (g.rf().sidewalk ? 1 : 0);
                walkThickLine(r, g.pts(), half, (idx, x, z, dist, along, dir) -> {
                    if (idx < 0 || r.road[idx] != g.code()) return;
                    // Already set by a bridge, tunnel or pier drawn over it, or by a nearer piece of this road.
                    if (r.roadY[idx] != RegionRaster.NO_Y && gradeDist[idx] == Float.MAX_VALUE) return;
                    if (dist >= gradeDist[idx]) return;
                    gradeDist[idx] = (float) dist;
                    int a = (int) Math.max(0, Math.min(profile.length - 1, Math.round(along)));
                    r.roadY[idx] = (short) Math.round(profile[a]);
                });
            }
        }

        // Parallel bridges share one deck. Each carriageway of a big bridge is its own OSM way, and a footbridge
        // often runs alongside; each got its deck from the terrain at its own two ends, which differ when one
        // starts on the ramp and another on the quay, so the decks came out stepped. Every bridge cell is lifted
        // to the highest deck of any other bridge way running the same direction, on the same layer, within 12 m.
        if (bridges.size() > 1) unifyParallelBridges(r, bridges, nodeDeck);

        // Approach ramps: a ground way that ends where a bridge deck starts
        // climbs to that deck at a fixed grade, as an embankment. A ramp that
        // is still above ground at the way's other end continues into the
        // next connected way (roads near interchanges are split into many
        // short ways), hence the passes.
        for (int pass = 0; pass < 4 && !nodeDeck.isEmpty(); pass++) {
            boolean extended = false;
            for (Drawn g : groundWays) {
                long kStart = nodeKey(g.pts().get(0)), kEnd = nodeKey(g.pts().get(g.pts().size() - 1));
                Double dStart = nodeDeck.get(kStart);
                Double dEnd = nodeDeck.get(kEnd);
                if (dStart == null && dEnd == null) continue;
                final double ds = dStart == null ? Double.NEGATIVE_INFINITY : dStart;
                final double de = dEnd == null ? Double.NEGATIVE_INFINITY : dEnd;
                final double[] gp = graded.get(g);
                walkThickLine(r, g.pts(), g.rf().halfWidth + (g.rf().sidewalk ? 1 : 0), (idx, x, z, dist, along, dir) -> {
                    if (idx < 0 || r.road[idx] != g.code()) return;
                    double fromStart = along, fromEnd = g.length() - along;
                    if (fromStart > RAMP_REACH && fromEnd > RAMP_REACH) return;
                    double ramp = Math.max(ds - RAMP_GRADE * fromStart, de - RAMP_GRADE * fromEnd);
                    // Against the graded road at this point along it (the same for the whole width), not the ground
                    // under each cell: per cell, half a road took the ramp and half did not (a sawtooth, and an
                    // eight-block drop in O.J. Brochs gate).
                    double terrain = gp != null ? gp[(int) Math.max(0, Math.min(gp.length - 1, Math.round(along)))] : elevationAt(x, z);
                    if (Double.isNaN(terrain) || ramp < terrain + 1.0) return;
                    short y = (short) Math.round(ramp);
                    if (r.roadY[idx] == RegionRaster.NO_Y || y > r.roadY[idx]) r.roadY[idx] = y;
                });
                // Carry the ramp over to the far end if it has not reached the ground yet.
                if (g.length() <= RAMP_REACH) {
                    double atEnd = ds - RAMP_GRADE * g.length();
                    double atStart = de - RAMP_GRADE * g.length();
                    double[] pe = g.pts().get(g.pts().size() - 1), ps = g.pts().get(0);
                    double te = elevationAt(pe[0], pe[1]), ts = elevationAt(ps[0], ps[1]);
                    if (dEnd == null && !Double.isNaN(te) && atEnd >= te + 1.0) {
                        nodeDeck.put(kEnd, atEnd);
                        extended = true;
                    }
                    if (dStart == null && !Double.isNaN(ts) && atStart >= ts + 1.0) {
                        nodeDeck.put(kStart, atStart);
                        extended = true;
                    }
                }
            }
            if (!extended) break;
        }

        // Cuttings down to sunken tunnel portals, the mirror image of the ramps.
        if (!nodeCut.isEmpty()) {
            for (Drawn g : groundWays) {
                Double cStart = nodeCut.get(nodeKey(g.pts().get(0)));
                Double cEnd = nodeCut.get(nodeKey(g.pts().get(g.pts().size() - 1)));
                if (cStart == null && cEnd == null) continue;
                final double cs = cStart == null ? Double.POSITIVE_INFINITY : cStart;
                final double ce = cEnd == null ? Double.POSITIVE_INFINITY : cEnd;
                final double[] gp = graded.get(g);
                walkThickLine(r, g.pts(), g.rf().halfWidth + (g.rf().sidewalk ? 1 : 0), (idx, x, z, dist, along, dir) -> {
                    if (idx < 0 || r.road[idx] != g.code()) return;
                    double fromStart = along, fromEnd = g.length() - along;
                    if (fromStart > RAMP_REACH && fromEnd > RAMP_REACH) return;
                    double cut = Math.min(cs + RAMP_GRADE * fromStart, ce + RAMP_GRADE * fromEnd);
                    // As for the ramps: decided per point along the road, so the cutting takes its whole width.
                    double terrain = gp != null ? gp[(int) Math.max(0, Math.min(gp.length - 1, Math.round(along)))] : elevationAt(x, z);
                    if (Double.isNaN(terrain) || cut > terrain - 1.0) return;
                    short y = (short) Math.round(cut);
                    if (r.roadY[idx] == RegionRaster.NO_Y || y < r.roadY[idx]) r.roadY[idx] = y;
                });
            }
        }

        // Side slopes of the graded roads: the columns just outside a road blend from the road height back to the
        // ground over SHOULDER blocks, so a road cut into a hillside or raised over a dip has earth banks, not
        // vertical walls. Buildings, water and other roads keep their own heights.
        if (!graded.isEmpty()) {
            r.ensureShoulders();
            for (java.util.Map.Entry<Drawn, double[]> e : graded.entrySet()) {
                Drawn g = e.getKey();
                double[] profile = e.getValue();
                int half = g.rf().halfWidth + (g.rf().sidewalk ? 1 : 0);
                walkThickLine(r, g.pts(), half + RegionRaster.SHOULDER, (idx, x, z, dist, along, dir) -> {
                    if (idx < 0 || r.road[idx] != 0 || r.building[idx] != 0 || r.water[idx] != 0) return;
                    if (r.hasCoastline && r.isSea(idx)) return;
                    int k = Math.max(1, (int) Math.ceil(dist - half - 0.5));
                    if (k > RegionRaster.SHOULDER) return;
                    if (r.shoulderDist[idx] != 0 && r.shoulderDist[idx] <= k) return;
                    int a = (int) Math.max(0, Math.min(profile.length - 1, Math.round(along)));
                    r.shoulderDist[idx] = (byte) k;
                    r.shoulderY[idx] = (short) Math.round(profile[a]);
                });
            }
        }
    }

    private void unifyParallelBridges(RegionRaster r, List<Drawn> bridges, Map<Long, Double> nodeDeck) {
        // Repeated until nothing moves: a way lifted to its neighbour in one round (a carriageway to the other one)
        // lifts the ways beside it in the next (the cycleway beside that carriageway on Puddefjordsbroen stayed 2 to
        // 5 blocks under the deck when every way was compared with the others' heights from before any lift).
        for (int round = 0; round < 4; round++) {
            short[] before = r.roadY.clone();
            unifyParallelBridgesOnce(r, bridges, nodeDeck, before);
            if (Arrays.equals(before, r.roadY)) break;
        }
    }

    private void unifyParallelBridgesOnce(RegionRaster r, List<Drawn> bridges, Map<Long, Double> nodeDeck, short[] before) {
        int radius = Math.max(2, (int) Math.round(mapper.blocks(12.0)));
        for (Drawn b : bridges) {
            double half = b.rf().halfWidth + (b.rf().sidewalk ? 1 : 0);
            int n = (int) Math.ceil(b.length()) + 1;
            // Pass 1: per position along the way, the highest deck of a parallel bridge way near any cell of the
            // full width. Deciding per position (not per cell) keeps the deck level across its width.
            double[] lift = new double[n];
            Arrays.fill(lift, Double.NEGATIVE_INFINITY);
            walkThickLine(r, b.pts(), half, (idx, x, z, dist, along, dir) -> {
                if (idx < 0 || r.road[idx] != b.code()) return;
                int a = (int) Math.max(0, Math.min(n - 1, Math.round(along)));
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int j = r.index(x + dx, z + dz);
                        if (j < 0) continue;
                        short c = r.road[j];
                        if (c == 0 || c == b.code()) continue;
                        RoadFeature o = r.roads.get(c - 1);
                        if (!o.bridge || o.layer != b.rf().layer) continue;
                        int od = r.roadDirAt(j);
                        int dd = Math.abs(od - dir) % 8;
                        if (Math.min(dd, 8 - dd) > 1 && Math.abs(dd - 4) > 1) continue; // must run (anti)parallel, not cross
                        short y = before[j];
                        if (y != RegionRaster.NO_Y && y > lift[a]) lift[a] = y;
                    }
                }
            });
            boolean any = false;
            for (double v : lift) any |= v != Double.NEGATIVE_INFINITY;
            if (!any) continue;
            // A lift never steps: it ramps down at the road grade either side of where it is needed.
            for (int i = 1; i < n; i++) lift[i] = Math.max(lift[i], lift[i - 1] - RAMP_GRADE);
            for (int i = n - 2; i >= 0; i--) lift[i] = Math.max(lift[i], lift[i + 1] - RAMP_GRADE);
            // Pass 2: apply across the whole width.
            walkThickLine(r, b.pts(), half, (idx, x, z, dist, along, dir) -> {
                if (idx < 0 || r.road[idx] != b.code() || before[idx] == RegionRaster.NO_Y) return;
                int a = (int) Math.max(0, Math.min(n - 1, Math.round(along)));
                if (lift[a] == Double.NEGATIVE_INFINITY) return;
                short y = (short) Math.round(lift[a]);
                if (y > r.roadY[idx]) r.roadY[idx] = y;
            });
            // The ramps climb to the lifted deck.
            if (lift[0] != Double.NEGATIVE_INFINITY) nodeDeck.merge(nodeKey(b.pts().get(0)), lift[0], Math::max);
            if (lift[n - 1] != Double.NEGATIVE_INFINITY) nodeDeck.merge(nodeKey(b.pts().get(b.pts().size() - 1)), lift[n - 1], Math::max);
        }
    }

    private static boolean urbanCover(LandCover lc) {
        return switch (lc) {
            case RESIDENTIAL, COMMERCIAL, RETAIL, INDUSTRIAL, PEDESTRIAN, SQUARE, PARKING, SCHOOL, HOSPITAL -> true;
            default -> false;
        };
    }

    static boolean isTunnelWay(Map<String, String> tags) {
        String t = tags.get("tunnel");
        if (t == null) return false;
        return (OsmTags.has(tags, "tunnel") && !"no".equals(t)) || "covered".equals(t) || "building_passage".equals(t);
    }

    private static final boolean DEBUG_TUNNELS = Boolean.getBoolean("orbis.debugTunnels");

    /** Blocks of tunnel after a portal before the road must be fully underground (16 m). */
    private final int portalMargin;
    /** Blocks of rock between a tunnel floor and the ground, street or building floor above it (6.5 m, at least 3.5 blocks). */
    private final double tunnelCover;

    /**
     * Road height along a tunnel: a straight line between the two portals,
     * pushed down wherever it would come too close to the ground above
     * ({@link #tunnelCover} over the whole width of the tunnel, so a
     * hillside falling away beside it does not open the wall) or to a street
     * crossing above, each dip spread at {@link #RAMP_GRADE}.
     *
     * The ground cover is sampled from the terrain along the whole way, not
     * through this region's raster: a tunnel crossing several regions gets the
     * same profile in every one of them. (Sampling only the cells inside the
     * raster gave each region a different set of dips, and a step of up to 3
     * blocks in the floor at the region boundary.)
     */
    /** Blocks around an interior tunnel node within which buildings count towards the node's floor. */
    private static final int NODE_COVER_RADIUS = 64;

    /** Width of a way in blocks: its tag, else the Norwegian road database, else the default for its class. */
    private int roadWidthBlocks(OsmWay way, RoadFeature.Kind kind, String highway, String railway, NvdbRoads.Index nvdbIndex) {
        Map<String, String> tags = way.tags();
        double widthM = OsmTags.parseLength(tags.get("width"), Double.NaN);
        int lanes = OsmTags.parseInt(tags.get("lanes"), -1);
        // The surveyed width and lanes of the road under this way, from a road database (NVDB, BD TOPO, Digiroad, HPMS...).
        if (nvdbIndex != null && kind == RoadFeature.Kind.ROAD && (Double.isNaN(widthM) || lanes < 0)) {
            double[] m = halfForOneWay(way, nvdbIndex.match(toBlockRing(way.points())));
            if (Double.isNaN(widthM) && !Double.isNaN(m[0])) widthM = m[0];
            if (lanes < 0 && m[1] > 0) lanes = (int) m[1];
        }
        if (Double.isNaN(widthM)) widthM = defaultWidth(kind, highway, railway, tags, lanes);
        return Math.max(1, (int) Math.round(mapper.blocks(widthM)));
    }

    /** A database counting both directions (HPMS) matched to one carriageway of a divided road: half of it. */
    private static double[] halfForOneWay(OsmWay way, double[] m) {
        if (m.length < 3 || m[2] == 0) return m;
        String oneway = way.tags().get("oneway");
        String hw = way.tags().get("highway");
        boolean oneWay = "yes".equals(oneway) || "1".equals(oneway) || "-1".equals(oneway)
                || (oneway == null && ("motorway".equals(hw) || "motorway_link".equals(hw)));
        if (!oneWay) return m;
        return new double[]{m[0] / 2, m[1] > 0 ? Math.max(1, Math.round(m[1] / 2)) : m[1], m[2]};
    }

    private int roadHalfWidth(OsmWay way, RoadFeature.Kind kind, String highway, String railway, NvdbRoads.Index nvdbIndex) {
        return Math.max(0, (roadWidthBlocks(way, kind, highway, railway, nvdbIndex) - 1) / 2);
    }

    /** A road tunnel that really dives (not a covered passage through a building). */
    private static boolean isRealRoadTunnel(OsmWay w) {
        String tt = w.tags().get("tunnel");
        return isTunnelWay(w.tags()) && !"covered".equals(tt) && !"building_passage".equals(tt)
                && transportKind(w.tags()) == RoadFeature.Kind.ROAD;
    }

    /** A node where tunnel ways hand over to each other and no surface way touches: inside the rock, not a portal. */
    private static boolean interiorTunnelNode(long node, Map<Long, Integer> tunnelEndCount, java.util.Set<Long> groundEnds) {
        return tunnelEndCount.getOrDefault(node, 0) >= 2 && !groundEnds.contains(node);
    }

    /** A tunnel under another tunnel: its road at least this many blocks under the other's road. */
    private static final int TUNNEL_STACK = 7;

    private double[] tunnelProfile(RegionRaster r, RoadFeature rf, List<double[]> pts, double halfWidth, double wayLen,
                                   double y0, double y1, java.util.function.Predicate<RoadFeature> isApproach, List<Cover> covers,
                                   int margin0, int margin1) {
        int n = (int) Math.ceil(wayLen) + 1;
        double[] cap = new double[n];
        Arrays.fill(cap, Double.POSITIVE_INFINITY);
        if (wayLen > margin0 + margin1) {
            // Buildings the tunnel may pass under: their floor (the lowest corner of the footprint, which on a
            // slope is well below the ground in the middle) is the cover there, not the terrain.
            double wx0 = Double.MAX_VALUE, wz0 = Double.MAX_VALUE, wx1 = -Double.MAX_VALUE, wz1 = -Double.MAX_VALUE;
            for (double[] p : pts) {
                wx0 = Math.min(wx0, p[0]);
                wx1 = Math.max(wx1, p[0]);
                wz0 = Math.min(wz0, p[1]);
                wz1 = Math.max(wz1, p[1]);
            }
            List<Cover> near = new ArrayList<>();
            for (Cover c : covers) {
                if (c.maxX() >= wx0 - halfWidth - 1 && c.minX() <= wx1 + halfWidth + 1
                        && c.maxZ() >= wz0 - halfWidth - 1 && c.minZ() <= wz1 + halfWidth + 1) near.add(c);
            }
            double along = 0;
            for (int i = 0; i + 1 < pts.size(); i++) {
                double[] a = pts.get(i), b = pts.get(i + 1);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (len < 1e-9) continue;
                double ux = (b[0] - a[0]) / len, uz = (b[1] - a[1]) / len;
                for (double s = 0; s <= len; s += 1.0) {
                    double at = along + s;
                    if (at < margin0 || at > wayLen - margin1) continue;
                    int ai = (int) Math.max(0, Math.min(n - 1, Math.round(at)));
                    double px = a[0] + ux * s, pz = a[1] + uz * s;
                    double lowest = Double.POSITIVE_INFINITY;
                    for (int k = -2; k <= 2; k++) {
                        double off = halfWidth * k / 2.0;
                        double qx = px - uz * off, qz = pz + ux * off;
                        double t = elevationAt(qx, qz);
                        if (!Double.isNaN(t)) lowest = Math.min(lowest, t);
                        for (Cover c : near) {
                            if (c.covers(qx, qz)) lowest = Math.min(lowest, c.baseY());
                        }
                    }
                    if (lowest != Double.POSITIVE_INFINITY) cap[ai] = Math.min(cap[ai], lowest - tunnelCover);
                }
                along += len;
            }
        }
        // Streets crossing above it (known only inside this raster; a street in a cutting sits below the terrain), and
        // shallower tunnels: a tube (4 blocks of air, a roof, gravel under the road) needs TUNNEL_STACK blocks under
        // another's road. Without this the two Nygårdstunnelen tubes (layers -1 and -3) shared one floor.
        if (rf != null) walkThickLine(r, pts, halfWidth, (idx, x, z, dist, along, dir) -> {
            if (idx < 0) return;
            RoadFeature above = r.roadAt(idx);
            if (above == null || above == rf || above.layer <= rf.layer || isApproach.test(above)) return;
            if (above.tunnel) {
                int ty = r.roadY[idx];
                if (ty == RegionRaster.NO_Y) return;
                int a = (int) Math.max(0, Math.min(n - 1, Math.round(along)));
                cap[a] = Math.min(cap[a], ty - TUNNEL_STACK);
                return;
            }
            int ay = r.roadY[idx];
            double base = ay != RegionRaster.NO_Y ? ay : elevationAt(x, z);
            if (Double.isNaN(base)) return;
            int a = (int) Math.max(0, Math.min(n - 1, Math.round(along)));
            cap[a] = Math.min(cap[a], base - tunnelCover);
        });
        for (int i = 1; i < n; i++) cap[i] = Math.min(cap[i], cap[i - 1] + RAMP_GRADE);
        for (int i = n - 2; i >= 0; i--) cap[i] = Math.min(cap[i], cap[i + 1] + RAMP_GRADE);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double straight = n == 1 ? y0 : y0 + (y1 - y0) * ((double) i / (n - 1));
            y[i] = Math.min(straight, cap[i]);
        }
        return y;
    }

    /**
     * Deck height along a bridge, per integer distance along the way: a
     * straight line between the two end heights, lifted wherever the deck must
     * clear something below it (a road needs ~5.5 blocks, a railway 6.5, a
     * footpath 3.5, water 2, plain ground 1), with every lift spread out at
     * {@link #RAMP_GRADE} so the deck never jumps. The ends may come out
     * higher than the banks; the approach roads then get ramps.
     */
    private double[] bridgeProfile(RegionRaster r, RoadFeature rf, List<double[]> pts, double halfWidth, double wayLen,
                                   double y0, double y1, java.util.function.Predicate<RoadFeature> isApproach) {
        int n = (int) Math.ceil(wayLen) + 1;
        double[] need = new double[n];
        Arrays.fill(need, Double.NEGATIVE_INFINITY);
        // A viaduct over open ground (parking, grass, an unmapped yard) is still
        // a structure several metres up, not a road lying on the grass: a
        // main-road or railway bridge keeps 5 m over the ground, a minor
        // road 3, a footbridge 2 (in blocks at this world's scale).
        String hw = rf.highway == null ? "" : rf.highway;
        boolean major = hw.startsWith("motorway") || hw.startsWith("trunk") || hw.startsWith("primary") || hw.startsWith("secondary");
        double groundClearance = rf.isRail() || (rf.kind == RoadFeature.Kind.ROAD && major) ? heightBlocks(5.0, 2.0)
                : rf.kind == RoadFeature.Kind.ROAD ? heightBlocks(3.0, 1.5) : heightBlocks(2.0, 1.0);
        // Ground clearance along the whole way from the terrain itself, so a bridge crossing a region boundary
        // gets the same deck in both regions (water and roads below are only known inside this raster).
        double along0 = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len < 1e-9) continue;
            for (double s = 0; s <= len; s += 1.0) {
                double at = along0 + s;
                if (at <= 1e-6 || at >= wayLen - 1e-6) continue;
                double t = elevationAt(a[0] + (b[0] - a[0]) * s / len, a[1] + (b[1] - a[1]) * s / len);
                if (Double.isNaN(t)) continue;
                int ai = (int) Math.max(0, Math.min(n - 1, Math.round(at)));
                need[ai] = Math.max(need[ai], t + groundClearance);
            }
            along0 += len;
        }
        walkThickLine(r, pts, halfWidth, (idx, x, z, dist, along, dir) -> {
            if (idx < 0 || along <= 1e-6 || along >= wayLen - 1e-6) return;
            int a = (int) Math.max(0, Math.min(n - 1, Math.round(along)));
            double req;
            RoadFeature under = r.roadAt(idx);
            if (under != null && under != rf && under.layer < rf.layer && !under.tunnel && !isApproach.test(under)) {
                int uy = r.roadY[idx];
                double base = uy != RegionRaster.NO_Y ? uy : elevationAt(x, z);
                if (Double.isNaN(base)) return;
                // 6.5 m over a railway, 5.5 m over a road, 3.5 m over a path; at least 4 blocks over a road or railway.
                req = base + (under.isRail() ? heightBlocks(6.5, 4.0)
                        : under.kind == RoadFeature.Kind.PATH || under.kind == RoadFeature.Kind.STEPS || under.kind == RoadFeature.Kind.TRACK ? heightBlocks(3.5, 3.5)
                        : heightBlocks(5.5, 4.0));
            } else if (dist <= 0.75) {
                // Centreline only: clear the ground or the water surface.
                WaterFeature wf = r.waterAt(idx);
                if ((r.hasCoastline && r.isSea(idx)) || (wf != null && wf.atSeaLevel)) {
                    req = cfg.seaLevelY + Math.max(2.5, groundClearance);
                } else if (wf != null && wf.surfaceY != WaterFeature.FOLLOW_TERRAIN) {
                    req = wf.surfaceY + Math.max(2.5, groundClearance);
                } else {
                    double t = elevationAt(x, z);
                    if (Double.isNaN(t)) return;
                    req = t + (wf != null ? Math.max(2.0, groundClearance) : groundClearance);
                }
            } else {
                return;
            }
            if (req > need[a]) need[a] = req;
        });
        for (int i = 1; i < n; i++) need[i] = Math.max(need[i], need[i - 1] - RAMP_GRADE);
        for (int i = n - 2; i >= 0; i--) need[i] = Math.max(need[i], need[i + 1] - RAMP_GRADE);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double straight = n == 1 ? y0 : y0 + (y1 - y0) * ((double) i / (n - 1));
            y[i] = Math.max(straight, need[i]);
        }
        return y;
    }

    static RoadFeature.Kind transportKind(Map<String, String> tags) {
        if ("yes".equals(tags.get("area"))) return null;
        String highway = tags.get("highway");
        if (highway != null) {
            return switch (highway) {
                case "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
                     "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
                     "living_street", "service", "road", "busway", "raceway" -> RoadFeature.Kind.ROAD;
                case "track" -> RoadFeature.Kind.TRACK;
                case "footway", "path", "pedestrian", "cycleway", "bridleway", "corridor", "sidewalk" -> RoadFeature.Kind.PATH;
                case "steps" -> RoadFeature.Kind.STEPS;
                default -> null; // construction, proposed, platform, bus_stop, ...
            };
        }
        String railway = tags.get("railway");
        if (railway != null) {
            return switch (railway) {
                case "rail", "light_rail", "narrow_gauge", "tram", "monorail", "preserved", "miniature", "funicular" -> RoadFeature.Kind.RAIL;
                case "disused", "abandoned" -> "abandoned".equals(railway) ? null : RoadFeature.Kind.RAIL;
                default -> null;
            };
        }
        String aeroway = tags.get("aeroway");
        if ("runway".equals(aeroway)) return RoadFeature.Kind.RUNWAY;
        if ("taxiway".equals(aeroway)) return RoadFeature.Kind.TAXIWAY;
        String manMade = tags.get("man_made");
        if ("pier".equals(manMade) || "breakwater".equals(manMade) || "groyne".equals(manMade)) return RoadFeature.Kind.PIER;
        if ("track".equals(tags.get("leisure"))) return RoadFeature.Kind.PATH;
        return null;
    }

    static int transportPriority(Map<String, String> tags) {
        String highway = tags.get("highway");
        if (highway != null) {
            return switch (highway) {
                case "motorway" -> 100;
                case "trunk" -> 95;
                case "motorway_link", "trunk_link" -> 90;
                case "primary" -> 85;
                case "primary_link" -> 80;
                case "secondary" -> 75;
                case "secondary_link" -> 70;
                case "tertiary" -> 65;
                case "tertiary_link" -> 60;
                case "residential", "unclassified", "road", "busway", "raceway" -> 55;
                case "living_street" -> 50;
                case "service" -> 45;
                case "pedestrian" -> 40;
                case "track" -> 35;
                case "cycleway" -> 30;
                case "steps" -> 28;
                case "footway", "sidewalk", "corridor" -> 25;
                case "bridleway", "path" -> 20;
                default -> 10;
            };
        }
        if (tags.containsKey("railway")) return 58;
        if (tags.containsKey("aeroway")) return 99;
        if (tags.containsKey("man_made")) return 42;
        return 15;
    }

    /** For coarse country maps: motorway .. secondary roads, railways and runways only. */
    private static boolean isMajorTransport(RoadFeature.Kind kind, String highway) {
        if (kind == RoadFeature.Kind.RAIL || kind == RoadFeature.Kind.RUNWAY) return true;
        if (kind != RoadFeature.Kind.ROAD || highway == null) return false;
        return switch (highway) {
            case "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link" -> true;
            default -> false;
        };
    }

    private static double defaultWidth(RoadFeature.Kind kind, String highway, String railway, Map<String, String> tags, int lanes) {
        if (kind == RoadFeature.Kind.ROAD && lanes > 0) {
            return lanes * 3.25 + 1.0;
        }
        switch (kind) {
            case RAIL -> {
                return "tram".equals(railway) ? 2.5 : 3.5;
            }
            case RUNWAY -> {
                return 45;
            }
            case TAXIWAY -> {
                return 23;
            }
            case PIER -> {
                return "breakwater".equals(tags.get("man_made")) ? 5 : 3;
            }
            case STEPS -> {
                return 2;
            }
            case TRACK -> {
                return 3;
            }
            case PATH -> {
                return switch (highway == null ? "" : highway) {
                    case "pedestrian" -> 5;
                    case "cycleway" -> 2.5;
                    case "footway", "sidewalk" -> 2;
                    default -> 1.5;
                };
            }
            default -> {
                return switch (highway == null ? "" : highway) {
                    case "motorway" -> 16;
                    case "trunk" -> 14;
                    case "primary" -> 10;
                    case "secondary" -> 9;
                    case "tertiary" -> 8;
                    case "motorway_link", "trunk_link", "primary_link", "secondary_link" -> 6;
                    case "tertiary_link" -> 5;
                    case "residential", "unclassified", "road" -> 6;
                    case "living_street" -> 5;
                    case "service" -> 4;
                    case "busway" -> 7;
                    case "raceway" -> 12;
                    default -> 6;
                };
            }
        }
    }

    // =====================================================================
    // Buildings
    // =====================================================================

    private void rasterizeBuildings(RegionRaster r, List<OsmArea> areasBigFirst, HeightStore.Loaded atlas) {
        List<OsmArea> mains = new ArrayList<>();
        List<OsmArea> parts = new ArrayList<>();
        for (OsmArea a : areasBigFirst) {
            String b = a.tags().get("building");
            String p = a.tags().get("building:part");
            if (p != null && !"no".equals(p)) parts.add(a);
            else if (b != null && !"no".equals(b)) mains.add(a);
        }
        Materials.Style style = Materials.styleForLatitude(mapper.originLat());
        BuildingDatabases dbs = buildingDbs;
        if (dbs != null && !mains.isEmpty()) {
            // The building databases' tiles under the region's buildings, all at once, before the buildings ask.
            double s = 90, w = 180, n = -90, e = -180;
            for (OsmArea a : mains) {
                for (List<LatLon> ring : a.outers()) {
                    for (LatLon p : ring) {
                        s = Math.min(s, p.lat());
                        n = Math.max(n, p.lat());
                        w = Math.min(w, p.lon());
                        e = Math.max(e, p.lon());
                    }
                }
            }
            if (s <= n) dbs.prefetch(s, w, n, e);
        }
        if (surfacePrefetch != null && cfg.buildingHeightsFromSurfaceModel && !mains.isEmpty()) {
            // Only the tiles under buildings: the surface model is big, and on a VPN's data allowance.
            List<double[]> boxes = new ArrayList<>(mains.size());
            for (OsmArea a : mains) {
                double s = 90, w = 180, n = -90, e = -180;
                for (List<LatLon> ring : a.outers()) {
                    for (LatLon p : ring) {
                        s = Math.min(s, p.lat());
                        n = Math.max(n, p.lat());
                        w = Math.min(w, p.lon());
                        e = Math.max(e, p.lon());
                    }
                }
                if (s <= n) boxes.add(new double[]{s, w, n, e});
            }
            surfacePrefetch.prefetch(boxes);
        }
        for (OsmArea a : mains) rasterizeBuilding(r, a, false, style, atlas);
        for (OsmArea a : parts) rasterizeBuilding(r, a, true, style, atlas);
    }

    private void rasterizeBuilding(RegionRaster r, OsmArea area, boolean part, Materials.Style style, HeightStore.Loaded atlas) {
        if (r.buildings.size() >= Short.MAX_VALUE - 1) return;
        Map<String, String> tags = area.tags();
        List<List<double[]>> rings = new ArrayList<>();
        for (List<LatLon> ring : area.outers()) rings.add(toBlockRing(ring));
        for (List<LatLon> ring : area.inners()) rings.add(toBlockRing(ring));
        if (rings.isEmpty() || rings.get(0).size() < 3) return;

        // Geometry: centroid, main axis (longest outer edge), half extents.
        List<double[]> outer = rings.get(0);
        double cx = 0, cz = 0;
        int nPts = 0;
        for (List<double[]> ring : rings) {
            for (double[] p : ring) {
                cx += p[0];
                cz += p[1];
                nPts++;
            }
        }
        cx /= nPts;
        cz /= nPts;
        double bestLen = -1, ax = 1, az = 0;
        for (int i = 0; i + 1 < outer.size(); i++) {
            double dx = outer.get(i + 1)[0] - outer.get(i)[0];
            double dz = outer.get(i + 1)[1] - outer.get(i)[1];
            double len = dx * dx + dz * dz;
            if (len > bestLen) {
                bestLen = len;
                double l = Math.sqrt(len);
                ax = dx / l;
                az = dz / l;
            }
        }
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (double[] p : outer) {
            double dx = p[0] - cx, dz = p[1] - cz;
            double u = dx * ax + dz * az;
            double v = -dx * az + dz * ax;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double halfA = (maxU - minU) / 2.0, halfB = (maxV - minV) / 2.0;
        // Recentre on the OBB middle so u/v are symmetric around the ridge.
        double midU = (maxU + minU) / 2.0, midV = (maxV + minV) / 2.0;
        cx += midU * ax - midV * az;
        cz += midU * az + midV * ax;
        if (halfA < halfB) {
            // Ensure A is the long axis.
            double t = halfA;
            halfA = halfB;
            halfB = t;
            double nax = -az, naz = ax;
            ax = nax;
            az = naz;
        }

        String type = Materials.buildingType(tags);
        int storey = cfg.metersPerStorey;
        // Palaces, museums, temples and other grand buildings have taller floors than flats.
        if ("museum".equals(tags.get("tourism")) || tags.containsKey("historic") || "palace".equals(tags.get("building"))
                || "temple".equals(tags.get("building")) || "cathedral".equals(tags.get("building")) || "church".equals(tags.get("building"))
                || "mosque".equals(tags.get("building")) || "government".equals(tags.get("building")) || "civic".equals(tags.get("building"))) {
            storey = Math.max(storey, 4);
        }
        // A storey is storeyM real metres (for a height known only as floors) and storey blocks apart inside (a floor
        // a player can walk: the same at every scale). At 1:1 the two are equal.
        double storeyM = storey;
        int levels = OsmTags.parseInt(tags.get("building:levels"), -1);
        double heightM = OsmTags.parseLength(tags.get("height"), Double.NaN);
        if (Double.isNaN(heightM)) heightM = OsmTags.parseLength(tags.get("building:height"), Double.NaN);
        boolean domed = LandmarkRegistry.looksDomed(tags);
        boolean roofOnly = "roof".equals(type) || "shelter".equals(type) || "carport".equals(type) && !tags.containsKey("building:levels");
        if (levels <= 0) {
            levels = defaultLevels(type, halfA, halfB, area.id());
        }
        RoofShape shape = Materials.roofShape(tags, type, levels, halfA, halfB, domed, area.id());
        int roofHeight = Materials.roofHeightBlocks(tags, shape, halfA, halfB, storey);

        // Base elevation = lowest sampled footprint vertex (walls start there,
        // uphill terrain inside the footprint is cut down to it).
        double base = Double.MAX_VALUE;
        int budget = 32;
        for (List<double[]> ring : rings) {
            int step = Math.max(1, ring.size() / 16);
            for (int i = 0; i < ring.size() && budget > 0; i += step, budget--) {
                double e = elevationAt(ring.get(i)[0], ring.get(i)[1]);
                if (!Double.isNaN(e)) base = Math.min(base, e);
            }
        }
        double ec = elevationAt(cx, cz);
        if (!Double.isNaN(ec)) base = Math.min(base, ec);
        if (base == Double.MAX_VALUE) base = cfg.seaLevelY;
        int baseY = (int) Math.round(Math.max(base, cfg.seaLevelY - 1));
        if (r.hasCoastline) {
            int cidx = r.index((int) Math.floor(cx), (int) Math.floor(cz));
            if (cidx >= 0 && r.isSea(cidx) && baseY < cfg.seaLevelY) baseY = cfg.seaLevelY;
        }

        // No height tags? Measure the building from a lidar surface model when one covers it.
        String heightSource = "default";
        boolean tagged = !Double.isNaN(heightM) && heightM > 0;
        if (tagged) {
            heightSource = "tags";
        } else if (OsmTags.parseInt(tags.get("building:levels"), -1) > 0) {
            heightSource = "tags";
        } else if (cfg.buildingHeightsFromSurfaceModel && surfaceModel != null && !part) {
            double[] roof = cfg.roofsFromSurfaceModel ? roofFromSurfaceModel(rings, cx, cz, ax, az, halfA, halfB, base) : null;
            if (roof != null) {
                // The lidar profile gives the eave and the ridge: walls to the eave, the roof up to the ridge, and
                // the roof shape from the profile (flat, gabled, hipped) unless the map says otherwise.
                double eave = roof[0], ridge = roof[1];
                heightM = ridge * cfg.metersPerBlock;
                heightSource = "surface-model";
                levels = Math.max(1, (int) Math.round(eave / storey));
                if (tags.get("roof:shape") == null) {
                    if (roof[2] == 0) shape = RoofShape.FLAT;
                    else if (roof[2] == 1) shape = RoofShape.GABLED;
                    else if (roof[2] == 2) shape = RoofShape.HIPPED;
                    else shape = Materials.roofShape(tags, type, levels, halfA, halfB, domed, area.id());
                }
                roofHeight = shape == RoofShape.FLAT ? 0 : Math.max(1, Math.min(14, (int) Math.round(ridge - eave)));
            } else {
                double measured = heightFromSurfaceModel(rings, cx, cz, ax, az, halfA, halfB, base);
                if (!Double.isNaN(measured) && measured >= 2.0 && measured <= cfg.maxBuildingHeightBlocks) {
                    heightM = measured * cfg.metersPerBlock;
                    heightSource = "surface-model";
                    levels = Math.max(1, (int) Math.round(measured / storey));
                    if (tags.get("roof:shape") == null) {
                        shape = Materials.roofShape(tags, type, levels, halfA, halfB, domed, area.id());
                        roofHeight = Materials.roofHeightBlocks(tags, shape, halfA, halfB, storey);
                    }
                }
            }
        }

        // A national or city building database (France, the Netherlands, Slovenia, Vienna, New York): the building's
        // own height or floors, at any scale.
        BuildingDatabases dbs = buildingDbs;
        if ("default".equals(heightSource) && dbs != null && !part) {
            LatLon c = area.centroid();
            BuildingDatabases.Hit hit = dbs.lookup(c.lat(), c.lon());
            if (hit != null) {
                double hm = !Double.isNaN(hit.heightM()) ? hit.heightM() : hit.floors() * storeyM;
                if (hm >= 2.0 && mapper.blocks(hm) <= cfg.maxBuildingHeightBlocks) {
                    heightSource = "database:" + hit.source();
                    levels = hit.floors() > 0 ? hit.floors() : Math.max(1, (int) Math.round(hm / storeyM));
                    if (tags.get("roof:shape") == null) {
                        shape = hit.flatRoof() ? RoofShape.FLAT : Materials.roofShape(tags, type, levels, halfA, halfB, domed, area.id());
                        roofHeight = shape == RoofShape.FLAT ? 0 : Materials.roofHeightBlocks(tags, shape, halfA, halfB, storey);
                    }
                    // A height to the gutter: the roof stands on top of it.
                    heightM = hit.eaveHeight() && shape != RoofShape.FLAT ? hm + roofHeight * cfg.metersPerBlock : hm;
                }
            }
        }

        // Still nothing? The GlobalBuildingAtlas has a height for nearly every building on Earth (satellite-derived,
        // a metre or two of noise on small houses); matched by OSM id, else by position.
        if ("default".equals(heightSource) && atlas != null && !part) {
            LatLon c = area.centroid();
            double h = atlas.heightFor(area.id(), c.lat(), c.lon());
            if (!Double.isNaN(h) && h >= 2.0 && mapper.blocks(h) <= cfg.maxBuildingHeightBlocks) {
                heightM = h;
                heightSource = "atlas";
                levels = Math.max(1, (int) Math.round(mapper.blocks(h) / storey));
                if (tags.get("roof:shape") == null) {
                    shape = Materials.roofShape(tags, type, levels, halfA, halfB, domed, area.id());
                    roofHeight = Materials.roofHeightBlocks(tags, shape, halfA, halfB, storey);
                }
            }
        }

        int wallHeight;
        int realLevels = levels;
        if (!Double.isNaN(heightM) && heightM > 0) {
            int totalBlocks = (int) Math.round(mapper.blocks(heightM));
            wallHeight = Math.max(1, totalBlocks - (shape == RoofShape.FLAT ? 0 : roofHeight));
            if (wallHeight < 2 && totalBlocks >= 3) {
                roofHeight = Math.max(1, totalBlocks - 2);
                wallHeight = totalBlocks - roofHeight;
            }
        } else {
            // Only a floor count (the map's, or the type's usual one): the walls are that many real storeys high, scaled
            // like a measured height, with the roof on top. At 1:2 a four-storey house is 12 m, 6 blocks of wall, like
            // its measured neighbours (it used to get 4 x 3 = 12 blocks there, twice its height).
            wallHeight = Math.max(1, (int) Math.round(mapper.blocks(levels * storeyM)));
            if ("church".equals(type) || "cathedral".equals(type)) wallHeight = Math.max(wallHeight, (int) Math.round(mapper.blocks(10)));
        }
        wallHeight = Math.min(wallHeight, cfg.maxBuildingHeightBlocks);
        // Floors inside, storey blocks apart: no more than the walls hold.
        levels = Math.max(1, Math.min(levels, wallHeight / storey));

        int minHeight = 0;
        double minHeightM = OsmTags.parseLength(tags.get("min_height"), Double.NaN);
        if (!Double.isNaN(minHeightM)) minHeight = (int) Math.round(mapper.blocks(minHeightM));
        else {
            int minLevel = OsmTags.parseInt(tags.get("building:min_level"), 0);
            if (minLevel > 0) minHeight = (int) Math.round(mapper.blocks(minLevel * storeyM));
        }
        if (minHeight >= wallHeight) minHeight = 0;
        if (roofOnly) minHeight = Math.max(minHeight, Math.max(0, wallHeight - 1));

        Materials.BuildingMaterials mats = Materials.building(tags, type, area.id(), realLevels, style, shape, domed);
        boolean minarets = "mosque".equals(type) && halfB >= 6;

        BuildingFeature bf = new BuildingFeature(area.id(), tags, type, wallHeight, minHeight, levels, shape, roofHeight,
                mats.wall(), mats.wallAccent(), mats.roof(), mats.window(), mats.floor(), part, domed, minarets,
                mats.glassCurtain(), storey, cx, cz, ax, az, halfA, halfB, tags.get("name"));
        bf.baseY = baseY;
        bf.heightSource = heightSource;

        LatLon centroidLatLon = area.centroid();
        if (landmarks != null && landmarks.isCoveredBySchematic(centroidLatLon)) bf.suppressed = true;

        r.buildings.add(bf);
        final short code = (short) r.buildings.size();
        final int[] bbox = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        final int[] count = {0};
        final boolean elevatedPart = part && minHeight > 0;
        fillRings(r, rings, (idx, x, z) -> {
            if (idx < 0) return;
            if (elevatedPart && r.building[idx] != 0) {
                BuildingFeature existing = r.buildings.get(r.building[idx] - 1);
                // A part hovering above another building (skybridge): keep the
                // lower building's column, we only store one building per column.
                if (!existing.part) return;
            }
            r.building[idx] = code;
            r.buildingFlags[idx] = 0;
            r.roofExtra[idx] = 0;
            bbox[0] = Math.min(bbox[0], x);
            bbox[1] = Math.min(bbox[1], z);
            bbox[2] = Math.max(bbox[2], x);
            bbox[3] = Math.max(bbox[3], z);
            count[0]++;
        });
        if (count[0] == 0) {
            // Footprint too small to cover a cell centre: stamp its centroid so tiny sheds still exist.
            int idx = r.index((int) Math.floor(cx), (int) Math.floor(cz));
            if (idx >= 0 && r.building[idx] == 0) {
                r.building[idx] = code;
                bbox[0] = bbox[2] = (int) Math.floor(cx);
                bbox[1] = bbox[3] = (int) Math.floor(cz);
                count[0] = 1;
            } else {
                return;
            }
        }

        // Per-column flags and roof profile.
        List<double[][]> edges = new ArrayList<>();
        for (List<double[]> ring : rings) {
            for (int i = 0; i + 1 < ring.size(); i++) edges.add(new double[][]{ring.get(i), ring.get(i + 1)});
            if (ring.size() > 1 && !(ring.get(0)[0] == ring.get(ring.size() - 1)[0] && ring.get(0)[1] == ring.get(ring.size() - 1)[1])) {
                edges.add(new double[][]{ring.get(ring.size() - 1), ring.get(0)});
            }
        }
        long hash = Materials.mix(area.id());
        boolean skillionFlip = (hash & 4) != 0;
        int doorIdx = -1;
        int doorScore = Integer.MAX_VALUE;
        for (int z = bbox[1]; z <= bbox[3]; z++) {
            for (int x = bbox[0]; x <= bbox[2]; x++) {
                int idx = r.index(x, z);
                if (idx < 0 || r.building[idx] != code) continue;
                int flags = 0;
                boolean west = isSame(r, x - 1, z, code), east = isSame(r, x + 1, z, code);
                boolean north = isSame(r, x, z - 1, code), south = isSame(r, x, z + 1, code);
                boolean edge = !(west && east && north && south);
                if (edge) flags |= RegionRaster.FLAG_EDGE;
                if (edge && (!west || !east) && (!north || !south)) flags |= RegionRaster.FLAG_CORNER;
                if (roofOnly) flags |= RegionRaster.FLAG_ROOF_ONLY;

                double px = x + 0.5 - cx, pz = z + 0.5 - cz;
                double u = px * ax + pz * az;
                double v = -px * az + pz * ax;
                double dEdge = Double.MAX_VALUE;
                if (shape != RoofShape.FLAT && shape != RoofShape.GABLED && shape != RoofShape.ROUND && shape != RoofShape.SKILLION) {
                    for (double[][] e : edges) dEdge = Math.min(dEdge, pointSegmentDistance(x + 0.5, z + 0.5, e[0], e[1]));
                }
                double extra = roofProfile(shape, u, v, halfA, halfB, dEdge, roofHeight, skillionFlip);
                r.roofExtra[idx] = (byte) Math.max(0, Math.min(255, (int) Math.round(extra)));
                if ((shape == RoofShape.GABLED || shape == RoofShape.GAMBREL || shape == RoofShape.SALTBOX)
                        && edge && Math.abs(u) > halfA - 1.6) {
                    flags |= RegionRaster.FLAG_GABLE_END;
                }
                if (minarets && DomeGenerator.isMinaretColumn(u, v, halfA, halfB, 1.6)) {
                    flags |= RegionRaster.FLAG_MINARET;
                }
                r.buildingFlags[idx] = (byte) flags;

                if (edge && cfg.buildingDoors && !part && !roofOnly && minHeight == 0) {
                    int score = doorScore(r, x, z, code, baseY);
                    if (score != Integer.MAX_VALUE && (flags & RegionRaster.FLAG_CORNER) != 0) score += 6; // prefer a wall, not a quoin
                    if (score < doorScore) {
                        doorScore = score;
                        doorIdx = idx;
                    }
                }
            }
        }
        if (doorIdx >= 0 && doorScore < Integer.MAX_VALUE) {
            r.buildingFlags[doorIdx] |= RegionRaster.FLAG_DOOR;
        }

        // Real roof colour from the orthophoto when OSM has no roof tags.
        if (imagery != null && cfg.imageryRoofColours && !roofOnly
                && Materials.firstTag(tags, "roof:colour", "roof:color", "roof:material") == null
                && !"greenhouse".equals(type) && bf.wall != bf.roof) {
            int rgb = roofColourFromImagery(r, code, bbox);
            if (rgb >= 0) {
                GroundClass look = GroundClass.classify(rgb);
                if (look == GroundClass.GRASS || look == GroundClass.TREE_CANOPY) {
                    // Vegetation over a footprint: a real green roof on a large
                    // flat building, or (far more often on small ones) the
                    // footprint and the photo are a few metres apart.
                    if (count[0] >= 400 && shape == RoofShape.FLAT) bf.roof = Blocks.MOSS_BLOCK.defaultBlockState();
                } else if (look != GroundClass.WATER) {
                    bf.roof = BlockPalette.nearestRoof(correctRoofColour(rgb, streetColour(r)));
                }
            }
        }
    }

    private static boolean isSame(RegionRaster r, int x, int z, short code) {
        int idx = r.index(x, z);
        return idx >= 0 && r.building[idx] == code;
    }

    /** Lower is better: distance to the nearest road/path cell in the 4 outward directions, up to 4 blocks. */
    /**
     * How good a spot for the building's door this edge cell is (lower is better). The door must open onto
     * land that is not another building or water, and a villager must be able to walk out of it: the walls
     * start at the lowest corner of the footprint, so on a slope most edges face into the hillside and the
     * ground outside has to be within a block or so of the base (the painter cuts a three-cell step in front
     * of the door, so up to three blocks of difference are walkable, at a price). A road nearby is a bonus.
     */
    private int doorScore(RegionRaster r, int x, int z, short code, int baseY) {
        int best = Integer.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            int nidx = r.index(nx, nz);
            if (nidx < 0 || r.building[nidx] != 0) continue; // not an outward direction, or straight into a neighbour
            if (r.water[nidx] != 0 || (r.hasCoastline && r.isSea(nidx))) continue;
            double e = elevationAt(nx, nz);
            int diff = Double.isNaN(e) ? 0 : Math.abs((int) Math.round(e) - baseY);
            int terrain = diff <= 1 ? 0 : diff <= 3 ? (diff - 1) * 12 : 100 + diff * 10;
            int score = 60 + terrain; // no road in sight: still a door, on the most level side
            for (int step = 1; step <= 4; step++) {
                int sidx = r.index(x + d[0] * step, z + d[1] * step);
                if (sidx < 0) break;
                if (r.building[sidx] != 0) break;
                if (r.road[sidx] != 0) {
                    RoadFeature rf = r.roadAt(sidx);
                    score = step * 10 + (rf != null && rf.kind == RoadFeature.Kind.PATH ? 0 : 3) + terrain;
                    break;
                }
            }
            best = Math.min(best, score);
        }
        return best;
    }

    private static int defaultLevels(String type, double halfA, double halfB, long id) {
        long h = Materials.mix(id ^ 0x51ED270B1L);
        int r = (int) (h % 100);
        return switch (type) {
            case "house", "detached", "semidetached_house", "bungalow", "cabin", "hut", "farm", "villa" -> r < 25 ? 1 : r < 85 ? 2 : 3;
            case "terrace", "residential" -> r < 50 ? 2 : r < 85 ? 3 : 4;
            case "apartments", "dormitory" -> r < 30 ? 3 : r < 65 ? 4 : r < 88 ? 5 : 7;
            case "commercial", "office", "hotel" -> r < 40 ? 2 : r < 75 ? 3 : r < 92 ? 5 : 8;
            case "retail", "supermarket", "kiosk", "mall" -> r < 70 ? 1 : 2;
            case "industrial", "warehouse", "factory", "hangar", "manufacture", "depot" -> 2;
            case "garage", "garages", "shed", "carport", "roof", "shelter", "hut_small", "boathouse", "greenhouse", "container" -> 1;
            case "church", "cathedral", "chapel", "mosque", "temple", "synagogue" -> 3;
            case "school", "university", "college", "kindergarten", "public", "civic", "government", "hospital" -> r < 50 ? 2 : 3;
            case "barn", "farm_auxiliary", "stable", "cowshed" -> 2;
            case "tower", "water_tower" -> 8;
            case "silo", "storage_tank" -> 4;
            case "parking", "multi-storey" -> 3;
            case "stadium", "sports_hall", "sports_centre" -> 4;
            case "castle", "fort" -> 4;
            case "museum", "palace" -> halfA * halfB * 4 > 800 ? 3 : 2;
            default -> {
                double area = halfA * halfB * 4;
                yield area > 1500 ? 3 : area > 300 ? 2 : r < 30 ? 1 : 2;
            }
        };
    }

    private static double pointSegmentDistance(double px, double pz, double[] a, double[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-12 ? 0 : ((px - a[0]) * dx + (pz - a[1]) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = a[0] + t * dx, qz = a[1] + t * dz;
        return Math.hypot(px - qx, pz - qz);
    }

    /** Height of the roof above the wall top at a footprint cell. */
    static double roofProfile(RoofShape shape, double u, double v, double halfA, double halfB, double dEdge, int roofH, boolean flip) {
        if (roofH <= 0) return 0;
        double b = Math.max(0.5, halfB), a = Math.max(0.5, halfA);
        double fv = Math.min(1.0, Math.abs(v) / b);
        double fu = Math.min(1.0, Math.abs(u) / a);
        double fEdge = dEdge == Double.MAX_VALUE ? 1.0 : Math.min(1.0, dEdge / b);
        double rr = Math.hypot(u, v) / b;
        return switch (shape) {
            case FLAT -> 0;
            case GABLED, SALTBOX -> roofH * (1 - fv) + 0.49;
            case HIPPED -> roofH * fEdge + 0.49;
            case HALF_HIPPED -> Math.min(roofH * (1 - fv), roofH * Math.min(1.0, (dEdge == Double.MAX_VALUE ? 1.0 : (dEdge + b * 0.3) / b))) + 0.49;
            case PYRAMIDAL -> roofH * Math.min(1.0, (dEdge == Double.MAX_VALUE ? 1.0 : dEdge / Math.min(a, b))) + 0.49;
            case SKILLION -> roofH * ((flip ? -v : v) + b) / (2 * b);
            case GAMBREL -> fv < 0.5 ? roofH * (1 - 0.3 * fv / 0.5) : roofH * 0.7 * (1 - (fv - 0.5) / 0.5);
            case MANSARD -> fEdge < 0.3 ? roofH * (fEdge / 0.3) : roofH;
            case ROUND -> roofH * Math.sqrt(Math.max(0, 1 - fv * fv));
            case DOME -> rr >= 1 ? 0 : roofH * Math.sqrt(1 - rr * rr);
            case ONION -> rr >= 1 ? 0 : roofH * (rr < 0.12 ? 1.0 : Math.pow(1 - rr * rr, 0.6) * (1 - 0.15 * rr));
            case CONE -> rr >= 1 ? 0 : roofH * (1 - rr);
        };
    }

    // =====================================================================
    // Decoration: trees, lamps, fences, ...
    // =====================================================================

    /** Streets whose names go on junction signs (not motorways, service roads, tracks or paths). */
    private static final java.util.Set<String> SIGNED_STREETS = java.util.Set.of(
            "residential", "living_street", "tertiary", "secondary", "primary", "trunk", "unclassified", "pedestrian");

    /**
     * Street name signs: at every map node where streets of two or more names meet at ground level, each name gets
     * a post at a corner: along its own street, just past the crossing street's carriageway, on the right-hand
     * kerb (the first column off the carriageway, not a building or water). The sign stands along the street it
     * names, as real street blades do. One per name within 16 blocks, so dual carriageways and roundabouts do not
     * repeat it.
     */
    private void rasterizeStreetSigns(RegionRaster r, List<OsmWay> ways) {
        record At(String name, List<double[]> pts, int i) {
        }
        Map<Long, List<At>> nodes = new java.util.LinkedHashMap<>();
        for (OsmWay w : ways) {
            Map<String, String> t = w.tags();
            String hw = t.get("highway"), name = t.get("name");
            if (hw == null || name == null || name.isBlank() || !SIGNED_STREETS.contains(hw)) continue;
            if (OsmTags.has(t, "bridge") || isTunnelWay(t) || OsmTags.parseInt(t.get("layer"), 0) != 0) continue;
            List<double[]> pts = toBlockRing(w.points());
            if (pts.size() < 2) continue;
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                if (r.index((int) Math.floor(p[0]), (int) Math.floor(p[1])) < 0) continue;
                nodes.computeIfAbsent(nodeKey(p), k -> new ArrayList<>()).add(new At(name.trim(), pts, i));
            }
        }
        List<double[]> placedAt = new ArrayList<>();
        List<String> placedName = new ArrayList<>();
        for (List<At> at : nodes.values()) {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (At a : at) names.add(a.name());
            if (names.size() < 2) continue;
            java.util.Set<String> done = new java.util.HashSet<>();
            for (At a : at) {
                if (!done.add(a.name())) continue;
                double[] p = a.pts().get(a.i());
                double[] q = a.pts().get(a.i() + 1 < a.pts().size() ? a.i() + 1 : a.i() - 1);
                double dx = q[0] - p[0], dz = q[1] - p[1], len = Math.hypot(dx, dz);
                if (len < 0.5) continue;
                dx /= len;
                dz /= len;
                double nx = -dz, nz = dx; // right-hand side, walking away from the junction
                int[] spot = null;
                for (int step = 2; step <= 24 && step <= len + 8 && spot == null; step++) {
                    double cx = p[0] + dx * step, cz = p[1] + dz * step;
                    for (int s = 0; s <= 14; s++) {
                        int x = (int) Math.floor(cx + nx * s), z = (int) Math.floor(cz + nz * s);
                        int idx = r.index(x, z);
                        if (idx < 0 || r.building[idx] != 0 || r.water[idx] != 0) break;
                        RoadFeature rf = r.roadAt(idx);
                        if (rf != null && r.roadDistAt(idx) <= rf.halfWidth) {
                            if (!a.name().equals(rf.name == null ? null : rf.name.trim())) break; // still in the crossing street
                            continue;
                        }
                        if (s > 0) spot = new int[]{x, z, idx};
                        break;
                    }
                }
                if (spot == null || r.decor[spot[2]] != 0) continue;
                boolean near = false;
                for (int k = 0; k < placedAt.size() && !near; k++) {
                    near = placedName.get(k).equals(a.name()) && Math.hypot(placedAt.get(k)[0] - spot[0], placedAt.get(k)[1] - spot[1]) < 16;
                }
                if (near) continue;
                // The blade along the street: it faces across it. Rotation 0 faces south (+Z), 4 west, 8 north, 12 east.
                double deg = Math.toDegrees(Math.atan2(-nx, nz));
                int rot = Math.floorMod((int) Math.round(deg / 22.5), 16);
                r.decor[spot[2]] = DecorType.STREET_SIGN.code();
                r.decorData[spot[2]] = (byte) rot;
                r.labels.put(spot[2], a.name());
                placedAt.add(new double[]{spot[0], spot[1]});
                placedName.add(a.name());
            }
        }
    }

    private void rasterizeDecor(RegionRaster r, OsmData data) {
        // Lines first (fences, hedges, tree rows), then points on top.
        for (OsmWay way : data.ways()) {
            Map<String, String> tags = way.tags();
            DecorType type = null;
            byte dataByte = 0;
            String barrier = tags.get("barrier");
            if (barrier != null && cfg.generateStreetFurniture) {
                switch (barrier) {
                    case "fence" -> {
                        type = DecorType.FENCE;
                        dataByte = fenceMaterial(tags);
                    }
                    case "wall" -> {
                        type = DecorType.WALL;
                        dataByte = wallMaterial(tags);
                    }
                    case "hedge" -> type = DecorType.HEDGE;
                    case "retaining_wall" -> type = DecorType.RETAINING_WALL;
                    case "city_wall" -> type = DecorType.CITY_WALL;
                    case "guard_rail", "handrail", "cable_barrier" -> type = DecorType.GUARD_RAIL;
                    default -> { }
                }
            } else if ("tree_row".equals(tags.get("natural")) && cfg.generateTrees) {
                type = DecorType.TREE_ROW;
                dataByte = treeSpecies(tags);
            }
            if (type == null) continue;
            List<double[]> pts = toBlockRing(way.points());
            if (type == DecorType.TREE_ROW) {
                final byte species = dataByte;
                final int[] counter = {0};
                walkThinLine4(r, pts, (idx, x, z) -> {
                    if ((counter[0]++ % 6) == 0 && r.decor[idx] == 0 && r.building[idx] == 0) {
                        r.decor[idx] = DecorType.TREE.code();
                        r.decorData[idx] = species;
                    }
                });
            } else {
                final DecorType ft = type;
                final byte fd = dataByte;
                walkThinLine4(r, pts, (idx, x, z) -> {
                    if (r.building[idx] == 0 && (r.decor[idx] == 0 || r.decor[idx] == DecorType.TREE.code())) {
                        r.decor[idx] = ft.code();
                        r.decorData[idx] = fd;
                    }
                });
            }
        }

        for (OsmNode node : data.nodes()) {
            Map<String, String> tags = node.tags();
            // A business mapped as a point inside a building belongs to that building.
            if (isUse(tags)) {
                double[] b = mapper.toBlockExact(node.pos().lat(), node.pos().lon());
                int bi = r.index((int) Math.floor(b[0]), (int) Math.floor(b[1]));
                if (bi >= 0) {
                    BuildingFeature host = r.buildingAt(bi);
                    if (host != null && !host.part) host.pois.add(tags);
                }
            }
            DecorType type = null;
            byte dataByte = 0;
            if ("tree".equals(tags.get("natural"))) {
                if (!cfg.generateTrees) continue;
                type = DecorType.TREE;
                dataByte = treeSpecies(tags);
                double h = OsmTags.parseLength(tags.get("height"), Double.NaN);
                if (!Double.isNaN(h) && h > 0) {
                    int hb = Math.max(1, Math.min(15, (int) Math.round(h / 2.0)));
                    dataByte = (byte) ((dataByte & 0x0F) | (hb << 4));
                }
            } else if ("shrub".equals(tags.get("natural"))) {
                type = DecorType.BUSH;
            } else if (cfg.generateStreetFurniture) {
                String highway = tags.get("highway");
                String amenity = tags.get("amenity");
                String power = tags.get("power");
                String manMade = tags.get("man_made");
                if ("street_lamp".equals(highway)) type = DecorType.LAMP;
                else if ("traffic_signals".equals(highway)) type = DecorType.TRAFFIC_SIGNAL;
                else if ("crossing".equals(highway)) type = DecorType.CROSSING;
                else if ("bus_stop".equals(highway)) type = DecorType.BUS_STOP;
                else if ("bench".equals(amenity)) type = DecorType.BENCH;
                else if ("fountain".equals(amenity)) type = DecorType.FOUNTAIN;
                else if ("waste_basket".equals(amenity)) type = DecorType.WASTE_BASKET;
                else if ("shelter".equals(amenity)) type = DecorType.SHELTER;
                else if ("tower".equals(power)) type = DecorType.POWER_TOWER;
                else if ("pole".equals(power)) type = DecorType.POWER_POLE;
                else if ("flagpole".equals(manMade)) type = DecorType.FLAGPOLE;
                else if ("mast".equals(manMade) || "tower".equals(manMade) || "communications_tower".equals(manMade)) type = DecorType.MAST;
                else if ("lighthouse".equals(manMade)) type = DecorType.LIGHTHOUSE;
                else if ("bollard".equals(tags.get("barrier"))) type = DecorType.BOLLARD;
                else if ("wayside_cross".equals(tags.get("historic")) || "grave".equals(tags.get("cemetery"))) type = DecorType.CROSS;
            }
            String label = null;
            if (type == null && cfg.transitLines && isRailStop(tags)) {
                type = DecorType.TRAIN_STOP;
                label = tags.get("name");
            }
            if (type == null) continue;
            double[] b = mapper.toBlockExact(node.pos().lat(), node.pos().lon());
            int idx = r.index((int) Math.floor(b[0]), (int) Math.floor(b[1]));
            if (idx < 0) continue;
            if (r.building[idx] != 0 && type != DecorType.MAST && type != DecorType.LIGHTHOUSE && type != DecorType.TRAIN_STOP) continue;
            r.decor[idx] = type.code();
            r.decorData[idx] = dataByte;
            if (label != null && !label.isBlank()) r.labels.put(idx, label);
        }
    }

    /** Tags that say what a place is used for (shop, amenity, ...), as opposed to street furniture. */
    private static boolean isUse(Map<String, String> tags) {
        for (String k : new String[]{"shop", "amenity", "tourism", "office", "craft", "healthcare", "leisure", "club"}) {
            String v = tags.get(k);
            if (v != null && !v.isEmpty()) return true;
        }
        return false;
    }

    /** A railway, tram or light-rail stop (not a bus stop): a minecart waits there. */
    private static boolean isRailStop(Map<String, String> tags) {
        String railway = tags.get("railway");
        if (railway != null) {
            switch (railway) {
                case "station", "halt", "tram_stop", "stop": return true;
                default: break;
            }
        }
        String pt = tags.get("public_transport");
        if ("stop_position".equals(pt) || "station".equals(pt)) {
            return "yes".equals(tags.get("train")) || "yes".equals(tags.get("tram")) || "yes".equals(tags.get("light_rail"))
                    || "yes".equals(tags.get("subway")) || "yes".equals(tags.get("monorail")) || "yes".equals(tags.get("funicular"));
        }
        return false;
    }

    private static byte fenceMaterial(Map<String, String> tags) {
        String m = tags.get("material");
        String ft = tags.get("fence_type");
        if ("chain_link".equals(ft) || "metal".equals(m) || "steel".equals(m) || "railing".equals(ft) || "metal_bars".equals(ft) || "wire".equals(ft) || "barbed_wire".equals(ft)) return 1;
        if ("concrete".equals(m) || "stone".equals(m)) return 2;
        if ("wood".equals(m) || "wood".equals(ft) || "split_rail".equals(ft) || "pole".equals(ft)) return 0;
        return 1;
    }

    private static byte wallMaterial(Map<String, String> tags) {
        String m = tags.get("material");
        String wt = tags.get("wall");
        if ("brick".equals(m) || "brick".equals(wt)) return 1;
        if ("concrete".equals(m) || "concrete".equals(wt) || "noise_barrier".equals(wt)) return 2;
        if ("dry_stone".equals(wt) || "stone".equals(m)) return 0;
        return 0;
    }

    /** Low nibble: 0 auto, 1 oak, 2 birch, 3 spruce, 4 pine, 5 jungle, 6 acacia, 7 cherry, 8 dark oak, 9 palm, 10 poplar. */
    private static byte treeSpecies(Map<String, String> tags) {
        String genus = tags.get("genus");
        String species = tags.get("species");
        String leaf = tags.get("leaf_type");
        String g = genus != null ? genus.toLowerCase(Locale.ROOT) : species != null ? species.toLowerCase(Locale.ROOT) : "";
        if (g.startsWith("picea") || g.startsWith("abies") || g.startsWith("pseudotsuga") || g.startsWith("larix") || g.startsWith("thuja") || g.startsWith("cupressus") || g.startsWith("juniperus") || g.startsWith("taxus")) return 3;
        if (g.startsWith("pinus") || g.startsWith("cedrus") || g.startsWith("sequoia") || g.startsWith("araucaria")) return 4;
        if (g.startsWith("populus")) return 10;
        if (g.startsWith("betula") || g.startsWith("salix") || g.startsWith("alnus")) return 2;
        if (g.startsWith("prunus") || g.startsWith("malus") || g.startsWith("magnolia")) return 7;
        if (g.startsWith("quercus") || g.startsWith("fagus") || g.startsWith("acer") || g.startsWith("tilia") || g.startsWith("ulmus") || g.startsWith("fraxinus") || g.startsWith("aesculus") || g.startsWith("platanus") || g.startsWith("carpinus") || g.startsWith("juglans")) return 1;
        if (g.startsWith("phoenix") || g.startsWith("cocos") || g.startsWith("washingtonia") || g.startsWith("arecaceae") || g.startsWith("palm")) return 9;
        if (g.startsWith("eucalyptus") || g.startsWith("ficus")) return 5;
        if (g.startsWith("acacia")) return 6;
        if ("needleleaved".equals(leaf)) return 3;
        if ("broadleaved".equals(leaf)) return 1;
        return 0;
    }

    private static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        s.sort(Double::compareTo);
        int mid = s.size() / 2;
        return s.size() % 2 == 0 ? (s.get(mid - 1) + s.get(mid)) / 2.0 : s.get(mid);
    }
}
