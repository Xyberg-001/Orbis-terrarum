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
    public static final int CURRENT_CONFIG_VERSION = 7;
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

    private static final String USGS_3DEP =
            "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage"
                    + "?bbox={west},{south},{east},{north}&bboxSR=4326&imageSR=4326&size=256,256&format=tiff&pixelType=F32"
                    + "&interpolation=RSP_BilinearInterpolation&f=image";
    private static final String KARTVERKET_DTM =
            "https://wcs.geonorge.no/skwms1/wcs.hoyde-dtm-nhm-25833?SERVICE=WCS&VERSION=1.0.0&REQUEST=GetCoverage"
                    + "&COVERAGE=nhm_dtm_topo_25833&CRS=EPSG:4326&RESPONSE_CRS=EPSG:4326&BBOX={west},{south},{east},{north}"
                    + "&WIDTH=256&HEIGHT=256&FORMAT=GeoTIFF";
    private static final String KARTVERKET_DOM =
            "https://wcs.geonorge.no/skwms1/wcs.hoyde-dom-nhm-25833?SERVICE=WCS&VERSION=1.0.0&REQUEST=GetCoverage"
                    + "&COVERAGE=nhm_dom_topo_25833&CRS=EPSG:4326&RESPONSE_CRS=EPSG:4326&BBOX={west},{south},{east},{north}"
                    + "&WIDTH=256&HEIGHT=256&FORMAT=GeoTIFF";

    /**
     * Also query the GeoTIFF web services below where they have coverage. Off by default since Mapterhorn already
     * carries the same national lidar terrain; turn on for a service that is finer than what Mapterhorn has.
     */
    public boolean useHighResElevation = false;
    /** Terrain (bare earth) sources, tried in order before the global tiles. */
    public List<ElevationSource> elevationSources = defaultElevationSources();
    /** Surface (tree/building tops) sources; where one covers a building its real height = surface - terrain. */
    public List<ElevationSource> surfaceModelSources = defaultSurfaceModelSources();
    public boolean buildingHeightsFromSurfaceModel = true;

    private static List<ElevationSource> defaultElevationSources() {
        List<ElevationSource> l = new ArrayList<>();
        l.add(new ElevationSource("usgs-3dep-conus", USGS_3DEP, 15, 10, 24.5, -125.0, 49.5, -66.9));
        l.add(new ElevationSource("usgs-3dep-alaska", USGS_3DEP, 15, 10, 51.0, -179.0, 71.5, -129.0));
        l.add(new ElevationSource("usgs-3dep-hawaii", USGS_3DEP, 15, 10, 18.9, -160.3, 22.3, -154.8));
        l.add(new ElevationSource("kartverket-dtm-1m", KARTVERKET_DTM, 16, 1, 57.9, 4.3, 71.3, 31.3));
        return l;
    }

    private static List<ElevationSource> defaultSurfaceModelSources() {
        List<ElevationSource> l = new ArrayList<>();
        l.add(new ElevationSource("kartverket-dom-1m", KARTVERKET_DOM, 16, 1, 57.9, 4.3, 71.3, 31.3));
        return l;
    }

    // ---- aerial imagery -----------------------------------------------------
    /** An XYZ / WMTS tile layer ({z},{x},{y}, {-y} for TMS, {quadkey} for Bing-style). */
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
        l.add(new ImagerySource("norge-i-bilder",
                "https://opencache.statkart.no/gatekeeper/gk/gk.open_nib_web_mercator_wmts_v2?layer=Nibcache_web_mercator_v2&style=default"
                        + "&tilematrixset=default028mm&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image%2Fpng&TileMatrix={z}&TileCol={x}&TileRow={y}",
                57.5, 4.0, 71.5, 31.5, "Orthophotos: Kartverket / Norge i bilder"));
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
    /** Ground classes from a segmentation model's tiles (config/orbisterrarum/ground-classes/, tools/classify_ground.py) instead of colour rules. */
    public boolean groundClassesFromModel = true;
    public boolean generateWater = true;
    public boolean generateLandCover = true;
    public boolean generateTrees = true;
    public boolean generateStreetFurniture = true;
    public boolean generateSchematics = true;
    public boolean generateBedrock = true;
    /** Coal, iron, copper, gold, redstone, lapis, diamond and emerald veins placed by depth below the real surface. */
    public boolean generateOres = true;
    /** Animals at chunk generation (vanilla rules: grass, daylight), so the world is not empty of life. */
    public boolean spawnAnimals = true;
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
        if (imageryZoom < 14) imageryZoom = 14;
        if (imageryZoom > 20) imageryZoom = 20;
        if (roadCenterLineColour == null) roadCenterLineColour = "white";
        if (climateOverride == null) climateOverride = "";
        if (projection == null || projection.isBlank()) projection = "equirectangular";
        if (residentsPerChunk < 0) residentsPerChunk = 0;
        if (residentsPerChunk > 1.5) residentsPerChunk = 1.5; // villager AI is the most expensive thing on a server; 4 per chunk stalled it for minutes
    }

    public int maxY() {
        return minY + (worldHeight > 0 ? worldHeight : DIMENSION_HEIGHT) - 1;
    }
}
