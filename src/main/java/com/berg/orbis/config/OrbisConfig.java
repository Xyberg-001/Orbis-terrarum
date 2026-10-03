package com.berg.orbis.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * All user-tunable settings, persisted as config/orbisterrarum/orbisterrarum.json.
 * Every field has a sensible default so the file is optional; it is written
 * out on first start so players can discover the knobs.
 *
 * minY / worldHeight mirror data/minecraft/dimension_type/overworld.json and
 * are forced to those values on load; they exist so the generator can know
 * its limits without a registry lookup.
 */
public class OrbisConfig {

    /** Bumped when defaults change in a way that an old file should pick up (network settings, world height). */
    public static final int CURRENT_CONFIG_VERSION = 17;
    public int configVersion = CURRENT_CONFIG_VERSION;

    /** Fixed by data/minecraft/dimension_type/overworld.json: the engine maximum of Y -2032..2031. */
    public static final int DIMENSION_MIN_Y = -2032;
    public static final int DIMENSION_HEIGHT = 4064;
    public static final int DEFAULT_SEA_LEVEL_Y = -1700;

    // ---- projection -------------------------------------------------------
    /** Real-world latitude that becomes Minecraft block (0, y, 0). */
    public double originLat = 60.39299;
    /** Real-world longitude that becomes Minecraft block (0, y, 0). */
    public double originLon = 5.32415;
    /**
     * Spawn somewhere other than the origin: players of a new world appear at spawnLat/spawnLon (applied once, on
     * the world's first start; see worldgen/WorldSpawn). Off: Minecraft's own spawn near block 0, 0.
     */
    public boolean customSpawn = false;
    public double spawnLat = 60.39299;
    public double spawnLon = 5.32415;
    /** Players appear on exactly the spawn block (game rule respawn_radius 0) instead of anywhere within 10 blocks. */
    public boolean exactSpawn = true;
    /** Real-world metres represented by one block. 1.0 = true 1:1. */
    public double metersPerBlock = 1.0;
    /**
     * "equirectangular" (default; exact at city scale) or "transverse_mercator"
     * (conformal, keeps a whole country's shape and scale right). Fixed once a world exists.
     */
    public String projection = "equirectangular";
    /**
     * Block Y that represents real mean sea level. The dimension spans Y
     * -2032..2031 (the engine maximum); -1700 leaves 3 731 blocks for
     * mountains and 332 for the sea floor. Changing it needs a new world.
     */
    public int seaLevelY = DEFAULT_SEA_LEVEL_Y;
    public int minY = DIMENSION_MIN_Y;
    /**
     * Height of new worlds in blocks (a multiple of 16, 1024..4064), or 0 to fit it to the terrain around the
     * origin when a world is created: a city 1 000 m high then gets a world ~1 800 tall instead of 4 064, which
     * makes every chunk about twice as cheap to generate, light, save and keep in memory. The floor and the sea
     * level never move. Fixed once a world exists (see worldgen/WorldHeight).
     */
    public int worldHeight = 0;

    // ---- vertical mapping (how real metres become block Y) ------------------
    /**
     * "relative" (default): 1:1 up to reliefKneeMeters; above it the terrain is
     * lowered by how far the regional (~25 km) average exceeds the knee, so
     * high plateaus sink but peaks keep their full local relief, and the top
     * of the range is squeezed smoothly instead of cut flat. "compress": only
     * the smooth squeeze near the ceiling. "clamp": plain 1:1, cut off at the
     * dimension limit (Everest becomes a 2031-high mesa).
     */
    public String verticalMode = "relative";
    /** Elevation (m) below which the world is exactly 1:1 in relative mode. */
    public double reliefKneeMeters = 1500;
    /** Radius (km) of the regional average used by relative mode. Larger = gentler tilt, more squeeze at the top. */
    public double reliefSmoothingKm = 25;
    /** Height (blocks) of the smooth squeeze zone below the dimension ceiling. */
    public int softCeilingBlocks = 900;

    // ---- elevation ----------------------------------------------------------
    /** Zoom of the terrain tiles: 15 is ~1.2 m per pixel at 60 N (2.4 m at the equator); Mapterhorn serves up to 16. */
    public int demZoom = 15;

    /**
     * Terrain tile zoom that suits a scale: 15 (about 1.2 m per pixel at 60 N) up to 1:2, one less for each
     * doubling beyond (1:4 -> 14, 1:8 -> 13, 1:16 -> 12, 1:32 -> 11 as the country-map preset, 1:64 -> 10). Finer
     * tiles than that only cost downloads: at 1:32 zoom 15 fetched sixteen times the tiles zoom 11 needs.
     */
    /**
     * The coarsest scale the lidar sources are worth their downloads at (metres per block). Beyond it a building
     * is a block or two across and 1 m terrain is averaged away, so the lidar terrain, the surface model's building
     * heights and roofs only cost slow requests (Kartverket, often through a VPN).
     */
    public static final double LIDAR_MAX_METERS_PER_BLOCK = 4.0;

    public static boolean lidarUseful(double metersPerBlock) {
        return metersPerBlock <= LIDAR_MAX_METERS_PER_BLOCK;
    }

    public static int demZoomFor(double metersPerBlock) {
        if (metersPerBlock <= 2.0) return 15;
        int z = 15 - (int) Math.ceil(Math.log(metersPerBlock / 2.0) / Math.log(2) - 1e-9);
        return Math.max(10, Math.min(15, z));
    }
    /** Memory for decoded terrain tiles, in 256x256-tile units (a 512-pixel tile counts four). */
    public int demTileCacheSize = 384;
    /**
     * Terrarium-encoded terrain tiles. Default: Mapterhorn (512-pixel lossless WebP), which merges the national
     * lidar terrain models (Norway, Switzerland, Austria, Germany, Japan, USA, ...) over Copernicus GLO-30.
     */
    public String demTileUrl = com.berg.orbis.dem.DemTileProvider.MAPTERHORN_URL;
    /** Real sea floor from Open Waters Seascape (GEBCO + regional surveys) wherever the land data is at sea level. */
    public boolean useBathymetry = true;
    public String bathymetryTileUrl = com.berg.orbis.dem.DemTileProvider.SEASCAPE_URL;
    /** Deepest sea floor drawn, in metres below sea level; the dimension has 332 blocks under sea level at 1:1. */
    public double maxSeaDepthMeters = 300;
    /** Building heights from the GlobalBuildingAtlas (config/orbisterrarum/heights, filled by tools/gba_heights.py). */
    public boolean atlasBuildingHeights = true;
    /** Fill the land cover where OpenStreetMap has no polygon from ESA WorldCover (10 m, global, fetched on demand). */
    public boolean worldCoverLandCover = true;
    public String worldCoverUrl = com.berg.orbis.landcover.WorldCoverProvider.DEFAULT_URL;
    /** Elevation readings this far (m) from their neighbours' median are treated as bad data. */
    public double elevationOutlierMeters = 25.0;

    /** A web service returning float32 GeoTIFF for a lat/lon box ({west},{south},{east},{north} placeholders). */
    public static class ElevationSource {
        public String name = "";
        public String urlTemplate = "";
        /** Slippy-map zoom of the 256 px request grid: 15 ~ 4.8 m/px at the equator, 16 ~ 2.4 m, 17 ~ 1.2 m (halve for 60 degrees latitude). */
        public int zoom = 15;
        public double resolutionMeters = 10;
        public double south = -90, west = -180, north = 90, east = 180;
        /** Value the service uses for "no data", or NaN if it already returns NaN. */
        public double noData = Double.NaN;
        public boolean enabled = true;
        /** Multiplies every value: 0.3048 for a service that answers in feet. */
        public double valueScale = 1;
        /** The values are heights above the ground (a normalised surface model): the terrain is added to them. */
        public boolean aboveGround = false;
        /** Only used with the Kartverket lidar switch on (a service that needs a VPN outside its country). */
        public boolean needsSwitch = false;
        /**
         * Cloud-optimised GeoTIFFs in a national grid instead of a web service: a STAC item search ({west}, {south},
         * {east}, {north}), the asset to read (its key, or how its file name ends) and the grid (see NationalGrids).
         */
        public String stacSearch = "";
        public String stacAsset = "";
        public String crs = "";

        ElevationSource cog(String stacSearch, String stacAsset, String crs) {
            this.stacSearch = stacSearch;
            this.stacAsset = stacAsset;
            this.crs = crs;
            return this;
        }

        ElevationSource with(double valueScale, boolean aboveGround, boolean needsSwitch) {
            this.valueScale = valueScale;
            this.aboveGround = aboveGround;
            this.needsSwitch = needsSwitch;
            return this;
        }

        public ElevationSource() {}

        public ElevationSource(String name, String urlTemplate, int zoom, double resolutionMeters, double south, double west, double north, double east) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.zoom = zoom;
            this.resolutionMeters = resolutionMeters;
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }
    }

    private static final String KARTVERKET_DOM =
            "https://wcs.geonorge.no/skwms1/wcs.hoyde-dom-nhm-25833?SERVICE=WCS&VERSION=1.0.0&REQUEST=GetCoverage"
                    + "&COVERAGE=nhm_dom_topo_25833&CRS=EPSG:4326&RESPONSE_CRS=EPSG:4326&BBOX={west},{south},{east},{north}"
                    + "&WIDTH=256&HEIGHT=256&FORMAT=GeoTIFF";

    /**
     * Also query the GeoTIFF web services below where they have coverage. Off by default since Mapterhorn already
     * carries the same national lidar terrain; turn on for a service that is finer than what Mapterhorn has.
     */
    public boolean useHighResElevation = false;
    /**
     * The lidar surface model (Kartverket's DOM, Norway) for building heights and roof shapes, on its own: without
     * the terrain services of {@link #useHighResElevation} (Mapterhorn already has that terrain). Off by default: the
     * service can only be reached from some networks (a VPN to Norway works) and is slow and unreliable; tiles are
     * kept in dsm-cache, so it is only needed while generating new areas.
     */
    public boolean lidarSurfaceModel = false;
    /** Terrain (bare earth) sources, tried in order before the global tiles. */
    public List<ElevationSource> elevationSources = defaultElevationSources();
    /**
     * A building database that knows each building's height or floors (WFS 2.0 GeoJSON or a Socrata JSON API):
     * {south} {west} {north} {east} for the box, {count} and {start} for its pages; the field names to read.
     */
    public static class BuildingSource {
        public String name = "";
        public String urlTemplate = "";
        public double south = -90, west = -180, north = 90, east = 180;
        /** Height field, the field it is measured from (absolute heights: the ground), feet to metres. */
        public String heightField = "", baseField = "", floorsField = "", roofField = "", geometryField = "";
        public double valueScale = 1;
        /** The height is to the gutter (the roof stands on top), not to the top of the roof. */
        public boolean eaveHeight = false;
        public int pageSize = 5000;
        public boolean enabled = true;

        public BuildingSource() {}

        BuildingSource(String name, String urlTemplate, double south, double west, double north, double east, String heightField, String baseField,
                       String floorsField) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
            this.heightField = heightField;
            this.baseField = baseField;
            this.floorsField = floorsField;
        }
    }

    /**
     * A road database outside Norway (widths, lanes) whose lines the NVDB matcher pairs with OSM roads: {south}
     * {west} {north} {east}, {count} {start} for its pages, {state} for a service split by state (statePrefix: the
     * outlines that name the states, "US-"); field names, scales (cm, feet), a field grouping a road's records.
     */
    public static class RoadSource {
        public String name = "";
        public String urlTemplate = "";
        public double south = -90, west = -180, north = 90, east = 180;
        public String widthField = "", lanesField = "", laneWidthField = "", groupField = "", statePrefix = "";
        public double widthScale = 1, laneWidthScale = 1;
        /** Width and lanes count both directions (half of them for one carriageway OSM draws as a one-way road). */
        public boolean bothWays = false;
        /** A record whose oneWayField has this value is a one-way road: its count is its own (HPMS facility_type 1). */
        public String oneWayField = "", oneWayValue = "";
        public int pageSize = 2000;
        public boolean enabled = true;

        public RoadSource() {}

        RoadSource(String name, String urlTemplate, double south, double west, double north, double east) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }
    }

    /** Road databases outside Norway (NVDB has its own switch: nvdbRoadWidths covers them all); see osm/RoadDatabases. */
    public List<RoadSource> roadSources = defaultRoadSources();

    /** Checked 1 Oct 2026. */
    private static List<RoadSource> defaultRoadSources() {
        List<RoadSource> l = new ArrayList<>();
        RoadSource fr = new RoadSource("fr-bdtopo-roads", "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=BDTOPO_V3:troncon_de_route" + WFS20 + "&PROPERTYNAME=largeur_de_chaussee,nombre_de_voies,geometrie", 41.3, -5.2, 51.1, 9.6);
        fr.widthField = "largeur_de_chaussee";
        fr.lanesField = "nombre_de_voies";
        l.add(fr);
        String digiroad = "https://avoinapi.vaylapilvi.fi/vaylatiedot/ogc/features/v1/collections/";
        RoadSource fiW = new RoadSource("fi-digiroad-width", digiroad + "digiroad:dr_leveys/items?f=json&bbox={west},{south},{east},{north}"
                + "&limit={count}&startIndex={start}", 59.7, 19.0, 70.1, 31.6);
        fiW.widthField = "arvo";
        fiW.widthScale = 0.01; // centimetres
        l.add(fiW);
        RoadSource fiL = new RoadSource("fi-digiroad-lanes", digiroad + "digiroad:dr_kaistojen_lukumaara/items?f=json&bbox={west},{south},{east},{north}"
                + "&limit={count}&startIndex={start}", 59.7, 19.0, 70.1, 31.6);
        fiL.lanesField = "arvo";
        fiL.groupField = "link_id"; // one record a direction: summed
        l.add(fiL);
        RoadSource us = new RoadSource("us-hpms", "https://geo.dot.gov/server/rest/services/Hosted/HPMS_FULL_{state}_2024/FeatureServer/0/query"
                + "?geometry={west},{south},{east},{north}&geometryType=esriGeometryEnvelope&inSR=4326&outSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&outFields=through_lanes,lane_width,facility_type&resultRecordCount={count}&resultOffset={start}&f=geojson", 18.9, -179.2, 71.5, -66.9);
        us.lanesField = "through_lanes";
        us.laneWidthField = "lane_width";
        us.laneWidthScale = 0.3048; // feet
        us.statePrefix = "US-";
        us.bothWays = true;
        us.oneWayField = "facility_type"; // 1: a one-way roadway (Manhattan's avenues), its lanes are its own
        us.oneWayValue = "1";
        l.add(us);
        RoadSource bc = new RoadSource("ca-bc-dra", "https://openmaps.gov.bc.ca/geo/pub/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=pub:WHSE_BASEMAPPING.DRA_DGTL_ROAD_ATLAS_MPAR_SP" + WFS20 + "&PROPERTYNAME=NUMBER_OF_LANES,GEOMETRY", 48.2, -139.1, 60.0, -114.0);
        bc.lanesField = "NUMBER_OF_LANES";
        l.add(bc);
        return l;
    }

    /**
     * A lake depth survey outside Norway: depth contours or sounded points (WFS 2.0 or ArcGIS GeoJSON, {south} {west}
     * {north} {east}, {count} {start} for pages), the depth field (its sign does not matter) and its unit (feet).
     */
    public static class LakeSurveySource {
        public String name = "";
        public String urlTemplate = "";
        public double south = -90, west = -180, north = 90, east = 180;
        public String depthField = "";
        public double depthScale = 1;
        public int pageSize = 2000;
        public boolean enabled = true;

        public LakeSurveySource() {}

        LakeSurveySource(String name, String urlTemplate, double south, double west, double north, double east, String depthField, double depthScale) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
            this.depthField = depthField;
            this.depthScale = depthScale;
        }
    }

    /** Lake depth surveys outside Norway (with realWaterDepths); see water/LakeSurveys. */
    public List<LakeSurveySource> lakeSurveySources = defaultLakeSurveySources();

    /** Checked 1 Oct 2026. */
    private static List<LakeSurveySource> defaultLakeSurveySources() {
        List<LakeSurveySource> l = new ArrayList<>();
        String syke = "https://paikkatiedot.ymparisto.fi/geoserver/inspire_el/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=";
        // Finland's SYKE: its certificate chains to HARICA's 2021 root, which OrbisHttp adds to Java's list.
        LakeSurveySource fiC = new LakeSurveySource("fi-syke-contours", syke + "inspire_el:EL.ContourLine" + WFS20 + "&PROPERTYNAME=syvyyskayra_m,geom",
                59.7, 19.0, 70.1, 31.6, "syvyyskayra_m", 1);
        LakeSurveySource fiD = new LakeSurveySource("fi-syke-deepest", syke + "inspire_el:EL.SpotElevation" + WFS20 + "&PROPERTYNAME=syvyys_m,geom",
                59.7, 19.0, 70.1, 31.6, "syvyys_m", 1);
        l.add(fiC);
        l.add(fiD);
        String arcgis = "/query?geometry={west},{south},{east},{north}&geometryType=esriGeometryEnvelope&inSR=4326&outSR=4326"
                + "&spatialRel=esriSpatialRelIntersects&resultRecordCount={count}&resultOffset={start}&f=geojson&outFields=";
        l.add(new LakeSurveySource("us-mn-dnr", "https://enterprise.gisdata.mn.gov/aghost/rest/services/us_mn_state_dnr/water_lake_bathymetry/MapServer/0"
                + arcgis + "abs_depth", 43.4, -97.3, 49.4, -89.4, "abs_depth", 0.3048)); // feet
        l.add(new LakeSurveySource("ca-on-mnrf", "https://ws.lioservices.lrc.gov.on.ca/arcgis2/rest/services/LIO_OPEN_DATA/LIO_Open01/MapServer/30"
                + arcgis + "DEPTH", 41.6, -95.2, 56.9, -74.3, "DEPTH", 1)); // negative metres
        return l;
    }

    /** National and city building databases (heights, floors), each used where it covers; see osm/BuildingDatabases. */
    public List<BuildingSource> buildingSources = defaultBuildingSources();

    private static final String WFS20 = "&BBOX={south},{west},{north},{east},urn:ogc:def:crs:EPSG::4326&SRSNAME=EPSG:4326"
            + "&OUTPUTFORMAT=application/json&COUNT={count}&STARTINDEX={start}";

    /** Checked 1 Oct 2026; each asked for its own fields only (Slovenia's cadastre sends 6 kB more a building otherwise). */
    private static List<BuildingSource> defaultBuildingSources() {
        List<BuildingSource> l = new ArrayList<>();
        BuildingSource fr = new BuildingSource("fr-bdtopo", "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=BDTOPO_V3:batiment" + WFS20 + "&PROPERTYNAME=hauteur,nombre_d_etages,geometrie",
                41.3, -5.2, 51.1, 9.6, "hauteur", "", "nombre_d_etages");
        fr.eaveHeight = true; // BD TOPO measures to the gutter
        l.add(fr);
        BuildingSource nl = new BuildingSource("nl-3dbag", "https://data.3dbag.nl/api/BAG3D/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=BAG3D:lod12" + WFS20 + "&PROPERTYNAME=b3_h_70p,b3_h_maaiveld,b3_dak_type,geom",
                50.7, 3.3, 53.6, 7.3, "b3_h_70p", "b3_h_maaiveld", "");
        nl.roofField = "b3_dak_type";
        l.add(nl);
        l.add(new BuildingSource("si-gurs-stavbe", "https://ipi.eprostor.gov.si/wfs-si-gurs-kn/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=SI.GURS.KN:STAVBE" + WFS20 + "&PROPERTYNAME=STEVILO_ETAZ,VISINA_H2,VISINA_H3,OBRIS_GEOM",
                45.4, 13.3, 46.9, 16.7, "VISINA_H2", "VISINA_H3", "STEVILO_ETAZ"));
        l.add(new BuildingSource("at-wien-bkm", "https://data.wien.gv.at/daten/geo?service=WFS&version=1.1.0&request=GetFeature"
                + "&typeName=ogdwien:FMZKBKMOGD&srsName=EPSG:4326&outputFormat=json&maxFeatures={count}"
                + "&bbox={south},{west},{north},{east},urn:ogc:def:crs:EPSG::4326&propertyName=O_KOTE,SHAPE",
                48.11, 16.18, 48.33, 16.58, "O_KOTE", "", ""));
        BuildingSource ny = new BuildingSource("us-nyc-footprints", "https://data.cityofnewyork.us/resource/5zhs-2jue.json"
                + "?$where=within_box(the_geom,{north},{west},{south},{east})&$select=the_geom,height_roof&$limit={count}&$offset={start}",
                40.49, -74.26, 40.92, -73.70, "height_roof", "", "");
        ny.valueScale = 0.3048; // feet
        ny.geometryField = "the_geom";
        l.add(ny);
        // Germany's LoD2 buildings come as big files: imported with tools/lod2_heights.py into this source's tiles.
        l.add(new BuildingSource("de-lod2", "", 47.2, 5.8, 55.1, 15.1, "", "", ""));
        return l;
    }

    /** Surface (tree/building tops) sources; where one covers a building its real height = surface - terrain. */
    public List<ElevationSource> surfaceModelSources = defaultSurfaceModelSources();
    public boolean buildingHeightsFromSurfaceModel = true;

    /**
     * None by default: Kartverket's and USGS's lidar terrain services were shipped here until 1 Oct 2026, but
     * Mapterhorn carries the same terrain (and answers faster). A service can still be added in the config file.
     */
    private static List<ElevationSource> defaultElevationSources() {
        return new ArrayList<>();
    }

    private static final String WCS20_4326 = "&subset=Lat({south},{north})&subset=Long({west},{east})"
            + "&subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326&outputCrs=http://www.opengis.net/def/crs/EPSG/0/4326"
            + "&format=image/tiff&scaleSize=Long(256),Lat(256)";
    private static final String IMAGE_SERVER_4326 = "/exportImage?bbox={west},{south},{east},{north}&bboxSR=4326&imageSR=4326"
            + "&size=256,256&format=tiff&pixelType=F32&interpolation=RSP_BilinearInterpolation&f=image";

    /**
     * Lidar surface models (building and tree tops) that answer for a lat/lon box themselves (WCS 2.0 with EPSG:4326
     * subsets, ArcGIS exportImage, WMS 1.3.0): checked 1 Oct 2026, 2 to 6 s a 256 px tile. Used by themselves where
     * they cover (inside their country's outline, see DataSources), except Kartverket's, which needs a VPN outside
     * Norway and its own switch. Some give heights above the ground (aboveGround), two answer in feet. Left out: England's and Flanders' (they refuse these requests), New Zealand's
     * (LERC-compressed files, which nothing in Java reads).
     */
    private static List<ElevationSource> defaultSurfaceModelSources() {
        List<ElevationSource> l = new ArrayList<>();
        l.add(new ElevationSource("kartverket-dom-1m", KARTVERKET_DOM, 16, 1, 57.9, 4.3, 71.3, 31.3).with(1, false, true));
        l.add(new ElevationSource("nl-ahn-dsm", "https://service.pdok.nl/rws/ahn/wcs/v1_0?service=WCS&version=2.0.1&request=GetCoverage"
                + "&CoverageId=dsm_05m&geotiff:predictor=None" + WCS20_4326, 17, 0.5, 50.7, 3.3, 53.6, 7.3));
        l.add(new ElevationSource("fr-ign-mns", "https://data.geopf.fr/wms-r/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
                + "&LAYERS=ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES.MNS&STYLES=normal&CRS=EPSG:4326&BBOX={south},{west},{north},{east}"
                + "&WIDTH=256&HEIGHT=256&FORMAT=image/geotiff", 17, 1, 41.3, -5.2, 51.1, 9.6));
        l.add(new ElevationSource("es-ign-mdsn", "https://wcs-mds.idee.es/mds?service=WCS&version=2.0.1&request=GetCoverage"
                + "&CoverageId=mdsn_e025" + WCS20_4326, 16, 2.5, 27.6, -18.2, 43.8, 4.4).with(1, true, false));
        l.add(new ElevationSource("de-nw-ndom", "https://www.wcs.nrw.de/geobasis/wcs_nw_ndom?service=WCS&version=2.0.1&request=GetCoverage"
                + "&CoverageId=nw_ndom" + WCS20_4326, 16, 1, 50.3, 5.8, 52.6, 9.5).with(1, true, false));
        l.add(new ElevationSource("ee-maaamet-ndsm", "https://teenus.maaamet.ee/ows/wcs-dsm?service=WCS&version=2.0.1&request=GetCoverage"
                + "&CoverageId=ndsm-5" + WCS20_4326, 16, 5, 57.5, 21.7, 59.8, 28.3).with(1, true, false));
        l.add(new ElevationSource("cz-cuzk-dmp1g", "https://ags.cuzk.cz/arcgis2/rest/services/dmp1g/ImageServer" + IMAGE_SERVER_4326,
                16, 2, 48.5, 12.0, 51.1, 18.9));
        l.add(new ElevationSource("us-or-dogami-dsm", "https://gis.dogami.oregon.gov/arcgis/rest/services/lidar/DIGITAL_SURFACE_MODEL_MOSAIC/ImageServer"
                + IMAGE_SERVER_4326, 16, 1, 41.9, -124.7, 46.3, -116.4).with(0.3048, false, false));
        l.add(new ElevationSource("us-ky-dsm", "https://kyraster.ky.gov/arcgis/rest/services/ElevationServices/Ky_DSM_First_Return_2FT_Phase2/ImageServer"
                + IMAGE_SERVER_4326, 16, 0.6, 36.4, -89.6, 39.2, -81.9).with(0.3048, false, false));
        l.add(new ElevationSource("us-dc-ndsm", "https://imagery.dcgis.dc.gov/dcgis/rest/services/Lidar/nDSM_2024/ImageServer" + IMAGE_SERVER_4326,
                16, 1, 38.79, -77.12, 39.0, -76.9).with(1, true, false));
        l.add(new ElevationSource("ca-nb-dsm", "https://gis-erd-der.gnb.ca/server/rest/services/LidarProducts/DSM/ImageServer" + IMAGE_SERVER_4326,
                16, 1, 44.5, -69.1, 48.1, -63.7));
        // Cloud-optimised GeoTIFFs in national grids (read block by block, see CogDemSource).
        l.add(new ElevationSource("ch-swisssurface3d", "", 17, 0.5, 45.8, 5.9, 47.9, 10.6).cog(
                "https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swisssurface3d-raster/items?bbox={west},{south},{east},{north}&limit=100",
                "_0.5_2056_5728.tif", "EPSG:2056"));
        l.add(new ElevationSource("ca-hrdem-dsm", "", 16, 1, 41.6, -141.1, 83.2, -52.6).cog(
                "https://datacube.services.geo.ca/stac/api/collections/hrdem-mosaic-1m/items?bbox={west},{south},{east},{north}&limit=20",
                "dsm", "EPSG:3979"));
        return l;
    }

    // ---- aerial imagery -----------------------------------------------------
    /**
     * An XYZ / WMTS tile layer ({z},{x},{y}, {-y} for TMS, {quadkey} for Bing-style), or a WMS / ArcGIS export asked
     * for one tile at a time ({bbox}: the tile's corners in EPSG:3857, minx,miny,maxx,maxy).
     */
    public static class ImagerySource {
        public String name = "";
        public String urlTemplate = "";
        public double south = -90, west = -180, north = 90, east = 180;
        public String attribution = "";
        public boolean enabled = true;

        public ImagerySource() {}

        public ImagerySource(String name, String urlTemplate, double south, double west, double north, double east, String attribution) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
            this.attribution = attribution;
        }
    }

    /**
     * Where downloads and imported data are kept (terrain, imagery, map data, extracts, lidar, caches of every kind):
     * an absolute path, a path starting with ~ (the home folder) or one relative to the game folder. Empty: the mod's
     * config folder, as before. The config file, landmarks, schematics and map marks always stay in the config folder.
     */
    public String dataFolder = "";

    /** Size of the markers on the world map (player arrows, spawn, pins and their labels): 0.5 to 3. */
    public double mapMarkerScale = 1.0;

    // ---- the real sky and seasons: defaults for new worlds, and an off switch for every world this game or server runs ----
    /** The sun rises and sets when it really does at the world's origin, and the moon shows its real phase. */
    public boolean realDaylight = true;
    /** Clear, rain or thunder as the Norwegian Meteorological Institute forecasts it where the players are. */
    public boolean realWeather = true;
    /** Grass and leaves take the colours of the real season, and cold places get snow in winter (applied when a world opens). */
    public boolean realSeasons = true;
    /** Today's real snow depth (Open-Meteo): deep in the mountains, none in green valleys; it settles and melts as the real snow does. */
    public boolean realSnow = true;
    /** With real daylight: villagers keep the town's real clock hours (work 08-16, bed at 22), not the sun's. */
    public boolean villagerClockHours = true;

    /** Pre-generate the chosen area by itself when the world opens (see worldgen/AutoPregen), until it is done. */
    public boolean pregenOnCreate = false;
    /** Hard limit: only the selection (and areas pre-generated later) generates; see worldgen/HardLimit. */
    public boolean pregenHardLimit = false;
    /** /orbis pregen (area, radius) leaves out chunks that are deep sea all over (they generate when someone sails there). */
    public boolean pregenSkipOpenSea = true;
    /** The world generator map's Skip open sea button for the area drawn there (off: sea and all). */
    public boolean pregenSelectionSkipsSea = false;
    /** The area: a place name, a radius in km round the location, or empty for the city or municipality there. */
    public String pregenArea = "";
    /** A selection drawn in the world preview (lat/lon outlines, added or cut out, in order); used before the area. */
    public java.util.List<PregenShape> pregenShapes = new java.util.ArrayList<>();

    /** One shape of a selection drawn in the world preview. */
    public static final class PregenShape {
        public boolean subtract;
        public double[][] latLon;

        public PregenShape() {
        }

        public PregenShape(boolean subtract, double[][] latLon) {
            this.subtract = subtract;
            this.latLon = latLon;
        }
    }

    /**
     * Lake beds with their real shape: NVE's surveys for Norway's sounded lakes, a bowl down to the lake's greatest
     * depth (NVE, else GLOBathy where imported, else estimated) for the rest, and river channels as deep as their width.
     */
    public boolean realWaterDepths = true;
    /** NVE's lake database map service (depth contours of Norway's surveyed lakes). */
    public String nveLakeServiceUrl = "https://kart.nve.no/enterprise/rest/services/Innsjodatabase2/MapServer";

    /** The stone underground and on bare rock is the real rock type (granite, gabbro, marble, schist...) from geological maps. */
    public boolean bedrockTypes = true;
    /** The Geological Survey of Norway's bedrock map service (WMS); blank: none. */
    public String bedrockServiceUrl = "https://geo.ngu.no/mapserver/BerggrunnWMS3";
    /** Macrostrat's world geological map (vector tiles, {z}/{x}/{y}), everywhere the maps before it have nothing; blank: none. */
    public String worldGeologyUrl = "https://tiles.macrostrat.org/carto/{z}/{x}/{y}";

    /** Sample real colours from orthophotos: roof colours for untagged buildings, ground cover where OSM has nothing. */
    public boolean useAerialImagery = true;
    /** 18 ~ 0.6 m/px at the equator (0.3 m at 60 degrees); 19 is sharper but four times the tiles. */
    public int imageryZoom = 18;
    public int imageryTileCacheSize = 512;
    public boolean imageryRoofColours = true;
    public boolean imageryGroundClassification = true;
    /** Place trees where the imagery shows canopy, even outside OSM forest polygons. */
    public boolean imageryTreeCover = true;
    public List<ImagerySource> imagerySources = defaultImagerySources();

    private static List<ImagerySource> defaultImagerySources() {
        List<ImagerySource> l = new ArrayList<>();
        // National orthophotos (open licences, attribution as given; checked 1 Oct 2026). Each answers only inside
        // its box; outside its country's border it draws white or nothing, and those pixels take the next source.
        // Left out: New York State's (7 s a tile; NAIP covers it).
        l.add(new ImagerySource("ch-swissimage", "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/default/current/3857/{z}/{x}/{y}.jpeg",
                45.81, 5.95, 47.81, 10.50, "Imagery Switzerland: © swisstopo (SWISSIMAGE)"));
        l.add(new ImagerySource("nl-pdok", "https://service.pdok.nl/hwh/luchtfotorgb/wmts/v1_0/Actueel_orthoHR/EPSG:3857/{z}/{x}/{y}.jpeg",
                50.75, 3.35, 53.56, 7.23, "Imagery Netherlands: Beeldmateriaal Nederland (PDOK), CC BY 4.0"));
        l.add(new ImagerySource("be-vl-ortho", "https://geo.api.vlaanderen.be/omw/wms?LAYERS=OMWRGB25VL&SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&STYLES=&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                50.67, 2.54, 51.51, 5.92, "Imagery Flanders: © Digitaal Vlaanderen"));
        l.add(new ImagerySource("be-wal-ortho", "https://geoservices.wallonie.be/arcgis/rest/services/IMAGERIE/ORTHO_LAST/MapServer/export?bbox={bbox}&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&f=image",
                49.49, 2.84, 50.82, 6.41, "Imagery Wallonia: © SPW"));
        l.add(new ImagerySource("lu-ortho", "https://wmts1.geoportail.lu/opendata/wmts/ortho_latest/GLOBAL_WEBMERCATOR_4_V3/{z}/{x}/{y}.jpeg",
                49.44, 5.73, 50.19, 6.53, "Imagery Luxembourg: © ACT (CC0)"));
        l.add(new ImagerySource("fr-ign", "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg",
                41.33, -5.15, 51.09, 9.57, "Imagery France: © IGN (BD ORTHO), Licence Ouverte"));
        String pnoa = "https://www.ign.es/wmts/pnoa-ma?layer=OI.OrthoimageCoverage&style=default&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg&TileMatrix={z}&TileCol={x}&TileRow={y}";
        l.add(new ImagerySource("es-pnoa", pnoa, 35.94, -9.30, 43.80, 4.33, "Imagery Spain: PNOA © IGN España (scne.es), CC BY 4.0"));
        l.add(new ImagerySource("es-pnoa-canarias", pnoa, 27.60, -18.20, 29.45, -13.30, "Imagery Spain: PNOA © IGN España (scne.es), CC BY 4.0"));
        l.add(new ImagerySource("de-by-dop", "https://geoservices.bayern.de/od/wms/dop/v1/dop20?LAYERS=by_dop20c&SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&STYLES=&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                47.27, 8.97, 50.57, 13.84, "Imagery Bavaria: © Bayerische Vermessungsverwaltung, CC BY 4.0"));
        l.add(new ImagerySource("de-nw-dop", "https://www.wms.nrw.de/geobasis/wms_nw_dop?LAYERS=nw_dop_rgb&SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&STYLES=&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                50.32, 5.86, 52.53, 9.46, "Imagery North Rhine-Westphalia: © Geobasis NRW, dl-de/zero-2.0"));
        l.add(new ImagerySource("de-be-truedop", "https://gdi.berlin.de/services/wms/truedop_2024?LAYERS=truedop_2024&SERVICE=WMS&REQUEST=GetMap"
                + "&VERSION=1.1.1&STYLES=&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                52.33, 13.08, 52.68, 13.77, "Imagery Berlin: © Geoportal Berlin / TrueDOP 2024"));
        l.add(new ImagerySource("at-basemap", "https://maps.wien.gv.at/basemap/bmaporthofoto30cm/normal/google3857/{z}/{y}/{x}.jpeg",
                46.37, 9.53, 49.02, 17.16, "Imagery Austria: basemap.at, CC BY 4.0"));
        l.add(new ImagerySource("cz-cuzk", "https://ags.cuzk.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}",
                48.55, 12.09, 51.06, 18.86, "Imagery Czechia: © ČÚZK, CC BY 4.0"));
        l.add(new ImagerySource("si-gurs", "https://ipi.eprostor.gov.si/wms-si-gurs-dts/wms?LAYERS=SI.GURS.ZPDZ:DOF025&SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&STYLES=&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                45.42, 13.37, 46.88, 16.61, "Imagery Slovenia: © GURS, CC BY 4.0"));
        l.add(new ImagerySource("ee-maaamet", "https://tiles.maaamet.ee/tm/tms/1.0.0/foto@GMC/{z}/{x}/{-y}.jpg",
                57.50, 21.70, 59.82, 28.21, "Imagery Estonia: © Maa- ja Ruumiamet, CC BY 4.0"));
        l.add(new ImagerySource("jp-gsi", "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg",
                24.00, 122.90, 45.60, 146.00, "Imagery Japan: GSI tiles (地理院タイル)"));
        l.add(new ImagerySource("tw-nlsc", "https://wmts.nlsc.gov.tw/wmts/PHOTO2/default/GoogleMapsCompatible/{z}/{y}/{x}",
                21.85, 119.30, 25.35, 122.10, "Imagery Taiwan: © NLSC, Open Government Data License"));
        l.add(new ImagerySource("hk-landsd", "https://mapapi.geodata.gov.hk/gs/api/v1.0.0/xyz/imagery/WGS84/{z}/{x}/{y}.png",
                22.15, 113.82, 22.57, 114.45, "Imagery Hong Kong: © Lands Department, HKSAR"));
        l.add(new ImagerySource("au-nsw", "https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Imagery/MapServer/tile/{z}/{y}/{x}",
                -37.60, 140.95, -28.15, 153.65, "Imagery New South Wales: © Spatial Services NSW, CC BY 4.0"));
        l.add(new ImagerySource("au-vic", "https://base.maps.vic.gov.au/service?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=AERIAL_WM_256&STYLE=default&FORMAT=image/png&TILEMATRIXSET=EPSG:3857:256&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
                -39.20, 140.95, -33.98, 149.98, "Imagery Victoria: © Vicmap, CC BY 4.0"));
        l.add(new ImagerySource("ca-on-geo", "https://ws.geoservices.lrc.gov.on.ca/arcgis5/rest/services/AerialImagery/GEO_Imagery_Data_Service_2023to2027/ImageServer/exportImage?bbox={bbox}&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&f=image",
                41.67, -95.16, 56.86, -74.32, "Imagery Ontario: © King's Printer for Ontario, OGL-Ontario"));
        l.add(new ImagerySource("us-ma-massgis", "https://tiles.arcgis.com/tiles/hGdibHYSPO59RG1h/arcgis/rest/services/orthos2023/MapServer/tile/{z}/{y}/{x}",
                41.23, -73.51, 42.89, -69.92, "Imagery Massachusetts: MassGIS 2023"));
        // (Norge i bilder's open tile cache, opencache.statkart.no, closed in 2025-2026: its successor needs a token
        // given only to Norwegian public bodies. Norway uses Esri's imagery like everywhere else.)
        l.add(new ImagerySource("usgs-naip",
                "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPPlus/ImageServer/tile/{z}/{y}/{x}",
                24.5, -125.0, 49.5, -66.9, "Imagery: USDA NAIP via USGS"));
        l.add(new ImagerySource("esri-world-imagery",
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                -85, -180, 85, 180, "Imagery: Esri, Maxar, Earthstar Geographics, and the GIS User Community"));
        return l;
    }

    // ---- OpenStreetMap ------------------------------------------------------
    /**
     * Overpass endpoints, tried in order; on HTTP 429 / 5xx / timeout the next
     * one is used. The public overpass-api.de instance rate-limits per IP
     * very aggressively (and temporarily blocks after repeated 429s), so the
     * more permissive mirrors come first.
     */
    public List<String> overpassUrls = new ArrayList<>(List.of(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter"));
    public int overpassTimeoutSeconds = 120;
    /** Global cap on parallel downloads. Each endpoint also has its own cap (overpass-api.de: 1, kumi: 4, others: 2). */
    public int overpassConcurrentRequests = 4;
    public int overpassRetries = 4;
    /** With waitForOsm, how long a chunk may wait for its region before generating terrain-only. */
    public int osmMaxWaitMinutes = 15;
    /** Download the regions around the origin while you are still in the main menu, so the first world creates instantly. */
    public boolean prefetchSpawnAtStartup = true;
    /**
     * At Create, when no map data on disk covers the new world's area (finer than 8 m per block), offer Geofabrik's
     * file for it: downloaded in the background, only the world's area imported (MapDataJob).
     */
    public boolean offerMapDownloads = true;
    /** Keep the downloaded Geofabrik files after the area is imported (for more worlds in the same country). */
    public boolean keepDownloadedMapFiles = false;
    /**
     * Orbis worlds save chunks without forcing each one to the disk first (Minecraft's "syncChunkWrites" off for
     * them only; vanilla worlds keep the game's own setting). Real-world chunks are tall and generated by the
     * thousand, and each synchronous write made the sweep wait for the disk. The cost: a power cut or crash can
     * lose the last few seconds of chunks, which then generate again.
     */
    public boolean fastChunkWrites = true;
    /** Orbis builds the terrain ahead of a pre-generation on half the cores, outside Minecraft's chunk system (FastPregen). */
    public boolean fastPregen = true;
    /** Threads building terrain ahead of a pre-generation; 0 = half the processor's threads. */
    public int fastPregenThreads = 0;
    /** Orbis worlds: chunks are compressed for saving on Minecraft's background threads, not its one disk thread. */
    public boolean parallelChunkCompression = true;
    /** The world stands still (as with /tick freeze) while a pre-generation runs; see PregenTask.syncWorldPause. */
    public boolean pregenPauseWorld = true;
    /** Square region edge, in blocks, that is fetched/rasterised as one unit. Must be a multiple of 16. */
    public int regionSizeBlocks = 512;
    /** Extra border rasterised around each region so features straddling the edge join up. */
    public int regionMarginBlocks = 32;
    /** Regions kept decoded in memory. Each is ~3-4 MB. */
    public int regionCacheSize = 64;
    /** Rings of regions kept downloading around (and ahead of) each player. 2 = a 2.5 km square. */
    public int regionPrefetchRadius = 2;
    /** Block chunk generation until the region's OSM data has been fetched. If false, chunks whose data is not yet available are generated as terrain only. */
    public boolean waitForOsm = true;
    /**
     * Chunks farther than this many blocks from every player generate at once
     * from elevation alone (terrain and sea, no buildings or roads) instead of
     * waiting for OpenStreetMap data. Meant for distant-horizon mods such as
     * Voxy WorldGen that generate huge radii for LODs: those chunks are not
     * saved, so they come back in full detail when you actually get there.
     * 0 = off (every chunk waits for its data).
     */
    public int terrainOnlyBeyondBlocks = 0;

    // ---- feature toggles ----------------------------------------------------
    public boolean generateBuildings = true;
    public boolean generateRoads = true;
    /** Only motorways, trunk, primary and secondary roads, railways and runways (for coarse country maps). */
    public boolean majorRoadsOnly = false;
    /** Coarse worlds (>= 8 m per block): vanilla-scale houses, blocks and halls on the map's built-up areas. */
    public boolean settlementBuildings = true;
    /** Villager households (beds + unemployed villagers) in buildings; workstations only where the map names a business. */
    public boolean villagerResidents = true;
    /** Target number of residents per chunk, spread over the buildings of the chunk (0.5 = one villager every two chunks of city). */
    public double residentsPerChunk = 0.3;
    /**
     * false (default): residents are nitwits, who never take a job, and no workstations are placed, so the only traders
     * are those of vanilla villages. true: residents can work, and buildings the map names as a business get the
     * matching workstation (bakery -> smoker, library -> lectern, ...).
     */
    public boolean residentJobs = false;
    /** A sign with the real name (or the kind of shop) beside every building door. */
    public boolean buildingSigns = true;
    /** A post with the street's name at the corners of junctions between differently named streets (1:2 or finer). */
    public boolean streetSigns = true;
    /** Furniture by building type: bookshelves in libraries, barrels and fish at the fish market, beds in hotels, tables in cafés, loot chests in shops. */
    public boolean furnishInteriors = true;
    /** Cats in residential streets, herds on meadows, a wandering trader at marketplaces, boats in marinas, gravestones, playgrounds. */
    public boolean streetLife = true;
    /** Rideable railways: powered rails along every line, curves and slopes connected, a minecart waiting at each station. */
    public boolean transitLines = true;
    /** A world datapack with one advancement per real landmark near the origin (peaks, attractions, castles, churches). */
    public boolean landmarkAdvancements = true;
    /** Lit supply niches with loot chests every 128 m in road tunnels. */
    public boolean tunnelLoot = true;
    /** Surveyed road widths and lane counts from NVDB, the Norwegian road database, where OSM has none (Norway, 1:1 and 1:2). */
    public boolean nvdbRoadWidths = true;
    /** Points of interest imported from Overture Places (config/orbisterrarum/places/) merged with OSM's. */
    public boolean externalPlaces = true;
    /** Trees where a canopy height model shows them (lidar surface minus terrain, or canopy-cache tiles from the global canopy map). */
    public boolean treesFromCanopy = true;
    /** Roof shape and eave/ridge heights read off the lidar surface model. */
    public boolean roofsFromSurfaceModel = true;
    public boolean generateWater = true;
    public boolean generateLandCover = true;
    public boolean generateTrees = true;
    /** Autumn: broadleaf trees in yellow, orange and red, more leaf litter, red shrubs (needs Minecraft 26.3 or later). */
    public boolean autumnColours = false;
    public boolean generateStreetFurniture = true;
    public boolean generateSchematics = true;
    public boolean generateBedrock = true;
    /** Coal, iron, copper, gold, redstone, lapis, diamond and emerald veins placed by depth below the real surface. */
    public boolean generateOres = true;
    /** Animals at chunk generation (vanilla rules: grass, daylight), so the world is not empty of life. */
    public boolean spawnAnimals = true;
    /** Wool-slab awnings over the doors of shops, cafés and restaurants (Minecraft 26.3 and later). */
    public boolean shopAwnings = true;
    /** Half-slab ramps where paved roads climb, stair blocks on outdoor stairways (highway=steps). */
    public boolean roadRamps = true;
    /** Roads level across their width along a smoothed profile, with earth side slopes (not following every column). */
    public boolean roadGrading = true;
    /** Vanilla structures: underground ones sunk 100 blocks below the local ground, surface ones only on empty map. */
    public boolean vanillaStructures = true;
    /** Vanilla-style cave tunnels, dungeons, geodes, fossils, springs and glow lichen in the underground band. */
    public boolean vanillaCaves = true;
    /** A sea lantern in every sixth floor block of hollow buildings so nothing spawns indoors. */
    public boolean interiorLights = true;
    /** Street lamps every 24 m along lit / urban streets. */
    public boolean streetLights = true;

    // ---- rendering ----------------------------------------------------------
    /** Buildings get hollow interiors with a floor slab per storey (needed for windows to make sense). */
    public boolean hollowBuildings = true;
    public boolean buildingWindows = true;
    public boolean buildingDoors = true;
    /** "white", "yellow" or "none". Norway/most of Europe: white or yellow; USA: yellow. */
    public String roadCenterLineColour = "white";
    public boolean roadSidewalks = true;
    /** Columns below sea level with no OSM land information become ocean. */
    public boolean seaFromElevation = true;
    /** Approximate trees per column inside forest polygons. 0.045 ~ one tree per 4.7 x 4.7 m. */
    public double treeDensityForest = 0.045;
    public double treeDensityPark = 0.008;
    public double treeDensityScrub = 0.01;
    /** "" = latitude/elevation model, "arid" forces desert-type biomes, "humid" forces jungle/forest at low latitudes. */
    public String climateOverride = "";
    public boolean snowAtHighElevation = true;
    /** Estimated mean annual temperature (C) below which ground is snow-covered and water frozen. */
    public double snowTemperatureC = 0.5;
    /** Added to the latitude/elevation temperature estimate, e.g. +3 for Gulf-Stream coasts, -3 for continental interiors. */
    public double temperatureOffsetC = 0.0;
    public int maxBuildingHeightBlocks = 450;
    public int metersPerStorey = 3;
    public boolean debugLogging = false;

    // serializeSpecialFloatingPointValues: ElevationSource.noData defaults to NaN.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();

    public static OrbisConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                OrbisConfig cfg = GSON.fromJson(Files.readString(file), OrbisConfig.class);
                if (cfg == null) cfg = new OrbisConfig();
                cfg.sanitize();
                // Re-write so newly added fields show up in the file.
                cfg.save(file);
                return cfg;
            }
            OrbisConfig cfg = new OrbisConfig();
            cfg.save(file);
            return cfg;
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not read config " + file + ": " + e + " -- using defaults");
            OrbisConfig cfg = new OrbisConfig();
            cfg.sanitize();
            return cfg;
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }

    /** Deep copy (used for the "effective" config of a world and for editing screens). */
    public OrbisConfig copy() {
        OrbisConfig c = GSON.fromJson(GSON.toJson(this), OrbisConfig.class);
        c.sanitizeValues();
        return c;
    }

    /** Clamps every value into its valid range (no version upgrade). */
    public void sanitizeValues() {
        int v = configVersion;
        configVersion = CURRENT_CONFIG_VERSION;
        sanitize();
        configVersion = Math.max(v, CURRENT_CONFIG_VERSION);
    }

    private void sanitize() {
        if (configVersion < 4) {
            // Older files were written with the conservative single-request
            // settings that made exploration crawl; reset the network block.
            overpassUrls = new ArrayList<>(List.of("https://overpass.kumi.systems/api/interpreter",
                    "https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter"));
            overpassConcurrentRequests = 4;
            regionCacheSize = Math.max(regionCacheSize, 64);
            regionPrefetchRadius = Math.max(regionPrefetchRadius, 2);
        }
        if (configVersion < 5) {
            // The dimension grew from Y -256..767 to the engine maximum so
            // mountains no longer flatten; sea level moved down with it.
            seaLevelY = DEFAULT_SEA_LEVEL_Y;
            verticalMode = "relative";
            System.out.println("[orbis] Config upgraded to version " + CURRENT_CONFIG_VERSION
                    + ": world is now Y " + DIMENSION_MIN_Y + ".." + (DIMENSION_MIN_Y + DIMENSION_HEIGHT - 1)
                    + " with sea level at Y=" + seaLevelY + " (existing worlds must be recreated)");
        }
        if (configVersion < 6) {
            // Terrain moved from the 30 m AWS tiles to Mapterhorn (national lidar where it exists); the zoom
            // that made sense for 30 m data is too coarse for 1 m data, and the lidar web services became
            // redundant (and Kartverket's had been unreachable for a week).
            if (com.berg.orbis.dem.DemTileProvider.LEGACY_AWS_URL.equals(demTileUrl)) demTileUrl = com.berg.orbis.dem.DemTileProvider.MAPTERHORN_URL;
            if (demZoom == 13 && metersPerBlock <= 2.0) demZoom = 15;
            useHighResElevation = false;
            System.out.println("[orbis] Config upgraded to version " + CURRENT_CONFIG_VERSION
                    + ": terrain tiles now come from Mapterhorn at zoom " + demZoom + " (new worlds get the lidar terrain; existing worlds show seams between old and new chunks)");
        }
        if (configVersion < 7) worldHeight = 0; // was the fixed 4064 before; fitted per world from now on
        if (surfaceModelSources != null) {
            List<ElevationSource> known = defaultSurfaceModelSources();
            surfaceModelSources = new ArrayList<>(surfaceModelSources);
            if (configVersion < 12) {
                // The national surface models (web services, then cloud GeoTIFFs) came after these files were written.
                for (ElevationSource d : known) {
                    if (surfaceModelSources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) surfaceModelSources.add(d);
                }
            }
            // Kartverket's needs a VPN outside Norway: whatever an older file says, only with its switch on.
            for (ElevationSource s : surfaceModelSources) if (s != null && "kartverket-dom-1m".equals(s.name)) s.needsSwitch = true;
        }
        if (buildingSources == null) buildingSources = defaultBuildingSources();
        if (roadSources == null) roadSources = defaultRoadSources();
        if (lakeSurveySources == null) lakeSurveySources = defaultLakeSurveySources();
        if (configVersion < 15) {
            lakeSurveySources = new ArrayList<>(lakeSurveySources);
            for (LakeSurveySource d : defaultLakeSurveySources()) {
                if (lakeSurveySources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) lakeSurveySources.add(d);
            }
        }
        if (configVersion < 14) {
            roadSources = new ArrayList<>(roadSources);
            for (RoadSource d : defaultRoadSources()) {
                if (roadSources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) roadSources.add(d);
            }
        }
        if (configVersion < 16) {
            // The building databases came after these files were written (and imported LoD2 after them).
            buildingSources = new ArrayList<>(buildingSources);
            for (BuildingSource d : defaultBuildingSources()) {
                if (buildingSources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) buildingSources.add(d);
            }
        }
        if (configVersion < 17) {
            // The services whose certificates chain to roots Java lacked (HARICA 2021, Telekom 2023) are back: OrbisHttp
            // trusts those roots now. Berlin's photos and Spain's surface model join older files; Finland's lake
            // surveys, written switched off, are switched on.
            if (imagerySources != null) {
                imagerySources = new ArrayList<>(imagerySources);
                for (ImagerySource d : defaultImagerySources()) {
                    if (d.name.equals("de-be-truedop") && imagerySources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) imagerySources.add(0, d);
                }
            }
            if (surfaceModelSources != null) {
                for (ElevationSource d : defaultSurfaceModelSources()) {
                    if (d.name.equals("es-ign-mdsn") && surfaceModelSources.stream().noneMatch(s -> s != null && d.name.equals(s.name))) surfaceModelSources.add(d);
                }
            }
            if (lakeSurveySources != null) {
                for (LakeSurveySource s : lakeSurveySources) if (s != null && s.name.startsWith("fi-syke")) s.enabled = true;
            }
        }
        if (configVersion < 10 && elevationSources != null) {
            // The lidar terrain services went: Mapterhorn already carries Kartverket's and USGS's terrain.
            elevationSources = new ArrayList<>(elevationSources);
            elevationSources.removeIf(s -> s == null || s.name == null || s.name.startsWith("usgs-3dep") || s.name.equals("kartverket-dtm-1m"));
            useHighResElevation = false;
        }
        if (configVersion < 8 && imagerySources != null && !imagerySources.isEmpty()) {
            // The national orthophoto services came after these files were written: put the ones a file does not
            // list yet ahead of its own (they only answer inside their countries; Esri stays the fallback).
            List<ImagerySource> merged = new ArrayList<>();
            for (ImagerySource d : defaultImagerySources()) {
                if (imagerySources.stream().noneMatch(s -> s != null && d.name.equals(s.name))
                        && !d.name.equals("esri-world-imagery") && !d.name.equals("usgs-naip")) merged.add(d);
            }
            merged.addAll(imagerySources);
            imagerySources = merged;
        }
        configVersion = CURRENT_CONFIG_VERSION;
        minY = DIMENSION_MIN_Y; // the floor is fixed by the sea level and the sea floor beneath it
        if (worldHeight < 0) worldHeight = 0;
        if (worldHeight > 0) worldHeight = com.berg.orbis.worldgen.WorldHeight.snap(worldHeight);
        if (seaLevelY < minY + 64) seaLevelY = minY + 64;
        if (seaLevelY > maxY() - 512) seaLevelY = maxY() - 512;
        if (verticalMode == null) verticalMode = "relative";
        if (reliefKneeMeters < 0) reliefKneeMeters = 0;
        if (reliefSmoothingKm < 2) reliefSmoothingKm = 2;
        if (softCeilingBlocks < 50) softCeilingBlocks = 50;
        if (terrainOnlyBeyondBlocks < 0) terrainOnlyBeyondBlocks = 0;
        if (regionPrefetchRadius < 0) regionPrefetchRadius = 0;
        if (regionPrefetchRadius > 6) regionPrefetchRadius = 6;
        if (overpassConcurrentRequests > 8) overpassConcurrentRequests = 8;
        if (imageryTileCacheSize < 64) imageryTileCacheSize = 64;
        if (demTileCacheSize < 32) demTileCacheSize = 32;
        if (treeDensityForest < 0) treeDensityForest = 0;
        if (treeDensityForest > 0.3) treeDensityForest = 0.3;
        if (snowTemperatureC < -20) snowTemperatureC = -20;
        if (snowTemperatureC > 20) snowTemperatureC = 20;
        if (temperatureOffsetC < -30) temperatureOffsetC = -30;
        if (temperatureOffsetC > 30) temperatureOffsetC = 30;
        if (originLat > 85) originLat = 85;
        if (originLat < -85) originLat = -85;
        if (originLon > 180) originLon = 180;
        if (originLon < -180) originLon = -180;
        if (metersPerBlock > 64) metersPerBlock = 64;
        if (metersPerBlock < 0.25) metersPerBlock = 0.25;
        if (regionSizeBlocks < 64) regionSizeBlocks = 64;
        regionSizeBlocks = (regionSizeBlocks / 16) * 16;
        if (regionMarginBlocks < 8) regionMarginBlocks = 8;
        if (regionCacheSize < 4) regionCacheSize = 4;
        if (metersPerBlock <= 0) metersPerBlock = 1.0;
        if (demZoom < 8) demZoom = 8;
        if (demZoom > 16) demZoom = 16;
        if (demTileUrl == null || demTileUrl.isBlank()) demTileUrl = com.berg.orbis.dem.DemTileProvider.MAPTERHORN_URL;
        if (worldCoverUrl == null || worldCoverUrl.isBlank()) worldCoverUrl = com.berg.orbis.landcover.WorldCoverProvider.DEFAULT_URL;
        if (bathymetryTileUrl == null || bathymetryTileUrl.isBlank()) bathymetryTileUrl = com.berg.orbis.dem.DemTileProvider.SEASCAPE_URL;
        if (maxSeaDepthMeters < 10) maxSeaDepthMeters = 10;
        if (maxSeaDepthMeters > (seaLevelY - minY - 12) * metersPerBlock) maxSeaDepthMeters = (seaLevelY - minY - 12) * metersPerBlock;
        if (metersPerStorey < 2) metersPerStorey = 2;
        if (overpassConcurrentRequests < 1) overpassConcurrentRequests = 1;
        if (overpassUrls == null || overpassUrls.isEmpty()) {
            overpassUrls = new ArrayList<>(List.of("https://overpass.kumi.systems/api/interpreter",
                    "https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter"));
        }
        if (osmMaxWaitMinutes < 1) osmMaxWaitMinutes = 1;
        // A fourth public mirror as the last resort for days when the big three are all overloaded.
        String fr = "https://overpass.openstreetmap.fr/api/interpreter";
        if (overpassUrls.stream().noneMatch(u -> u.contains("overpass.openstreetmap.fr"))) overpassUrls.add(fr);
        if (elevationSources == null) elevationSources = defaultElevationSources();
        if (surfaceModelSources == null) surfaceModelSources = defaultSurfaceModelSources();
        if (imagerySources == null || imagerySources.isEmpty()) imagerySources = defaultImagerySources();
        // Configs written before the open Norge i bilder cache closed still list it: every tile in Norway waited on
        // its timeouts before falling back to Esri.
        imagerySources = new ArrayList<>(imagerySources);
        imagerySources.removeIf(s -> s != null && s.urlTemplate != null && s.urlTemplate.contains("opencache.statkart.no/gatekeeper/gk/gk.open_nib"));
        if (imagerySources.isEmpty()) imagerySources = defaultImagerySources();
        if (imageryZoom < 14) imageryZoom = 14;
        if (imageryZoom > 20) imageryZoom = 20;
        if (roadCenterLineColour == null) roadCenterLineColour = "white";
        if (climateOverride == null) climateOverride = "";
        if (projection == null || projection.isBlank()) projection = "equirectangular";
        if (dataFolder == null) dataFolder = "";
        if (!(mapMarkerScale >= 0.5)) mapMarkerScale = 1.0;
        if (mapMarkerScale > 3) mapMarkerScale = 3;
        if (residentsPerChunk < 0) residentsPerChunk = 0;
        if (residentsPerChunk > 1.5) residentsPerChunk = 1.5; // villager AI is the most expensive thing on a server; 4 per chunk stalled it for minutes
    }

    public int maxY() {
        return minY + (worldHeight > 0 ? worldHeight : DIMENSION_HEIGHT) - 1;
    }
}
