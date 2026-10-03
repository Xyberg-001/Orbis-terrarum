package com.berg.orbis.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.Codec;
import net.minecraft.util.ExtraCodecs;

import java.io.IOException;

/**
 * The settings that describe one world: where on Earth it is, how metres map
 * to blocks, and which features are generated. They are chosen in the
 * world-creation screen, stored inside the world's generator (level.dat) so
 * the world always regenerates the same way, and default to the values in
 * the config file for worlds created without the screen (dedicated servers).
 *
 * Everything else in {@link OrbisConfig} (data sources, caches, network)
 * belongs to the installation, not the world.
 */
public final class WorldSettings {

    /**
     * NBT has no boolean type: a stored {@code true} comes back from level.dat
     * as the byte 1, so booleans must also accept numbers (and "true"/"1").
     */
    private static final TypeAdapter<Boolean> LENIENT_BOOLEAN = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Boolean value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value);
        }

        @Override
        public Boolean read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            return switch (t) {
                case NULL -> {
                    in.nextNull();
                    yield false;
                }
                case NUMBER -> in.nextDouble() != 0;
                case STRING -> {
                    String s = in.nextString().trim();
                    yield s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
                }
                default -> in.nextBoolean();
            };
        }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(boolean.class, LENIENT_BOOLEAN)
            .registerTypeAdapter(Boolean.class, LENIENT_BOOLEAN)
            .create();

    /** Stored as a JSON object inside the generator; new fields get defaults, unknown ones are ignored. */
    public static final Codec<WorldSettings> CODEC = ExtraCodecs.JSON.xmap(WorldSettings::fromJson, WorldSettings::toJson);

    // ---- location & scale ----
    public double originLat = 60.39299;
    public double originLon = 5.32415;
    public double metersPerBlock = 1.0;
    public boolean customSpawn = false;
    public double spawnLat = 60.39299;
    public double spawnLon = 5.32415;
    public boolean exactSpawn = true;
    public int seaLevelY = OrbisConfig.DEFAULT_SEA_LEVEL_Y;
    /** Height of the world in blocks; 0 = fit to the terrain around the origin when the world is created (resolved and stored then). */
    public int worldHeight = 0;
    /**
     * This world's squeeze range below the ceiling (see VerticalMapping), set when a fitted world is created; 0 = the
     * installation's softCeilingBlocks, which is what every world made before 1.1 used.
     */
    public int softCeilingBlocks = 0;
    public String verticalMode = "relative";
    public double reliefKneeMeters = 1500;
    public double reliefSmoothingKm = 25;
    public String projection = "equirectangular";
    public int demZoom = 15;

    // ---- features ----
    public boolean generateBuildings = true;
    public boolean generateRoads = true;
    public boolean majorRoadsOnly = false;
    public boolean settlementBuildings = true;
    public boolean villagerResidents = true;
    public double residentsPerChunk = 0.3;
    public boolean residentJobs = false;
    public boolean buildingSigns = true;
    public boolean streetSigns = true;
    public boolean furnishInteriors = true;
    public boolean streetLife = true;
    public boolean transitLines = true;
    public boolean landmarkAdvancements = true;
    public boolean tunnelLoot = true;
    public boolean nvdbRoadWidths = true;
    public boolean externalPlaces = true;
    public boolean treesFromCanopy = true;
    public boolean roofsFromSurfaceModel = true;
    public boolean generateWater = true;
    public boolean generateLandCover = true;
    public boolean generateTrees = true;
    public boolean autumnColours = false;
    public boolean generateStreetFurniture = true;
    public boolean generateSchematics = true;
    public boolean generateBedrock = true;
    public boolean generateOres = true;
    public boolean spawnAnimals = true;
    public boolean shopAwnings = true;
    public boolean roadRamps = true;
    public boolean roadGrading = true;
    public boolean bedrockTypes = true;
    public boolean realWaterDepths = true;
    public boolean realDaylight = true;
    public boolean realWeather = true;
    public boolean realSeasons = true;
    public boolean realSnow = true;
    public boolean villagerClockHours = true;
    public boolean pregenOnCreate = false;
    public boolean pregenHardLimit = false;
    public boolean pregenSkipOpenSea = true;
    public boolean pregenSelectionSkipsSea = false;
    public String pregenArea = "";
    public java.util.List<OrbisConfig.PregenShape> pregenShapes = new java.util.ArrayList<>();
    public boolean vanillaStructures = true;
    public boolean vanillaCaves = true;
    public boolean interiorLights = true;
    public boolean streetLights = true;

    // ---- style ----
    public boolean hollowBuildings = true;
    public boolean buildingWindows = true;
    public boolean buildingDoors = true;
    public String roadCenterLineColour = "white";
    public boolean roadSidewalks = true;
    public boolean seaFromElevation = true;
    public double treeDensityForest = 0.045;
    public String climateOverride = "";
    public boolean snowAtHighElevation = true;
    public double snowTemperatureC = 0.5;
    public double temperatureOffsetC = 0.0;
    public int metersPerStorey = 3;

    // ---- data ----
    public boolean useAerialImagery = true;
    public boolean useHighResElevation = false;
    public boolean lidarSurfaceModel = false;
    public boolean worldCoverLandCover = true;
    public boolean atlasBuildingHeights = true;
    public boolean useBathymetry = true;

    public static WorldSettings fromConfig(OrbisConfig c) {
        WorldSettings s = new WorldSettings();
        s.originLat = c.originLat;
        s.originLon = c.originLon;
        s.metersPerBlock = c.metersPerBlock;
        s.customSpawn = c.customSpawn;
        s.spawnLat = c.spawnLat;
        s.spawnLon = c.spawnLon;
        s.exactSpawn = c.exactSpawn;
        s.seaLevelY = c.seaLevelY;
        s.worldHeight = c.worldHeight;
        s.verticalMode = c.verticalMode;
        s.reliefKneeMeters = c.reliefKneeMeters;
        s.reliefSmoothingKm = c.reliefSmoothingKm;
        s.projection = c.projection;
        s.demZoom = c.demZoom;
        s.generateBuildings = c.generateBuildings;
        s.generateRoads = c.generateRoads;
        s.majorRoadsOnly = c.majorRoadsOnly;
        s.settlementBuildings = c.settlementBuildings;
        s.villagerResidents = c.villagerResidents;
        s.residentsPerChunk = c.residentsPerChunk;
        s.residentJobs = c.residentJobs;
        s.buildingSigns = c.buildingSigns;
        s.streetSigns = c.streetSigns;
        s.furnishInteriors = c.furnishInteriors;
        s.streetLife = c.streetLife;
        s.transitLines = c.transitLines;
        s.landmarkAdvancements = c.landmarkAdvancements;
        s.tunnelLoot = c.tunnelLoot;
        s.nvdbRoadWidths = c.nvdbRoadWidths;
        s.externalPlaces = c.externalPlaces;
        s.treesFromCanopy = c.treesFromCanopy;
        s.roofsFromSurfaceModel = c.roofsFromSurfaceModel;
        s.generateWater = c.generateWater;
        s.generateLandCover = c.generateLandCover;
        s.generateTrees = c.generateTrees;
        s.autumnColours = c.autumnColours;
        s.generateStreetFurniture = c.generateStreetFurniture;
        s.generateSchematics = c.generateSchematics;
        s.generateBedrock = c.generateBedrock;
        s.generateOres = c.generateOres;
        s.spawnAnimals = c.spawnAnimals;
        s.shopAwnings = c.shopAwnings;
        s.roadRamps = c.roadRamps;
        s.roadGrading = c.roadGrading;
        s.bedrockTypes = c.bedrockTypes;
        s.realWaterDepths = c.realWaterDepths;
        s.realDaylight = c.realDaylight;
        s.realWeather = c.realWeather;
        s.realSeasons = c.realSeasons;
        s.realSnow = c.realSnow;
        s.villagerClockHours = c.villagerClockHours;
        s.pregenOnCreate = c.pregenOnCreate;
        s.pregenHardLimit = c.pregenHardLimit;
        s.pregenSkipOpenSea = c.pregenSkipOpenSea;
        s.pregenSelectionSkipsSea = c.pregenSelectionSkipsSea;
        s.pregenArea = c.pregenArea == null ? "" : c.pregenArea;
        s.pregenShapes = c.pregenShapes == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(c.pregenShapes);
        s.vanillaStructures = c.vanillaStructures;
        s.vanillaCaves = c.vanillaCaves;
        s.interiorLights = c.interiorLights;
        s.streetLights = c.streetLights;
        s.hollowBuildings = c.hollowBuildings;
        s.buildingWindows = c.buildingWindows;
        s.buildingDoors = c.buildingDoors;
        s.roadCenterLineColour = c.roadCenterLineColour;
        s.roadSidewalks = c.roadSidewalks;
        s.seaFromElevation = c.seaFromElevation;
        s.treeDensityForest = c.treeDensityForest;
        s.climateOverride = c.climateOverride;
        s.snowAtHighElevation = c.snowAtHighElevation;
        s.snowTemperatureC = c.snowTemperatureC;
        s.temperatureOffsetC = c.temperatureOffsetC;
        s.metersPerStorey = c.metersPerStorey;
        s.useAerialImagery = c.useAerialImagery;
        s.useHighResElevation = c.useHighResElevation;
        s.lidarSurfaceModel = c.lidarSurfaceModel;
        s.worldCoverLandCover = c.worldCoverLandCover;
        s.atlasBuildingHeights = c.atlasBuildingHeights;
        s.useBathymetry = c.useBathymetry;
        return s;
    }

    /** Writes these settings into a config object (the installation defaults, or a working copy for a world). */
    public void applyTo(OrbisConfig c) {
        c.originLat = originLat;
        c.originLon = originLon;
        c.metersPerBlock = metersPerBlock;
        c.customSpawn = customSpawn;
        c.spawnLat = spawnLat;
        c.spawnLon = spawnLon;
        c.exactSpawn = exactSpawn;
        c.seaLevelY = seaLevelY;
        c.worldHeight = worldHeight > 0 ? worldHeight : OrbisConfig.DIMENSION_HEIGHT;
        if (softCeilingBlocks > 0) c.softCeilingBlocks = softCeilingBlocks;
        c.verticalMode = verticalMode;
        c.reliefKneeMeters = reliefKneeMeters;
        c.reliefSmoothingKm = reliefSmoothingKm;
        c.projection = projection;
        c.demZoom = demZoom;
        c.generateBuildings = generateBuildings;
        c.generateRoads = generateRoads;
        c.majorRoadsOnly = majorRoadsOnly;
        c.settlementBuildings = settlementBuildings;
        c.villagerResidents = villagerResidents;
        c.residentsPerChunk = residentsPerChunk;
        c.residentJobs = residentJobs;
        c.buildingSigns = buildingSigns;
        c.streetSigns = streetSigns;
        c.furnishInteriors = furnishInteriors;
        c.streetLife = streetLife;
        c.transitLines = transitLines;
        c.landmarkAdvancements = landmarkAdvancements;
        c.tunnelLoot = tunnelLoot;
        c.nvdbRoadWidths = nvdbRoadWidths;
        c.externalPlaces = externalPlaces;
        c.treesFromCanopy = treesFromCanopy;
        c.roofsFromSurfaceModel = roofsFromSurfaceModel;
        c.generateWater = generateWater;
        c.generateLandCover = generateLandCover;
        c.generateTrees = generateTrees;
        c.autumnColours = autumnColours;
        c.generateStreetFurniture = generateStreetFurniture;
        c.generateSchematics = generateSchematics;
        c.generateBedrock = generateBedrock;
        c.generateOres = generateOres;
        c.spawnAnimals = spawnAnimals;
        c.shopAwnings = shopAwnings;
        c.roadRamps = roadRamps;
        c.roadGrading = roadGrading;
        c.bedrockTypes = bedrockTypes;
        c.realWaterDepths = realWaterDepths;
        c.realDaylight = realDaylight;
        c.realWeather = realWeather;
        c.realSeasons = realSeasons;
        c.realSnow = realSnow;
        c.villagerClockHours = villagerClockHours;
        c.pregenOnCreate = pregenOnCreate;
        c.pregenHardLimit = pregenHardLimit;
        c.pregenSkipOpenSea = pregenSkipOpenSea;
        c.pregenSelectionSkipsSea = pregenSelectionSkipsSea;
        c.pregenArea = pregenArea == null ? "" : pregenArea;
        c.pregenShapes = pregenShapes == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(pregenShapes);
        c.vanillaStructures = vanillaStructures;
        c.vanillaCaves = vanillaCaves;
        c.interiorLights = interiorLights;
        c.streetLights = streetLights;
        c.hollowBuildings = hollowBuildings;
        c.buildingWindows = buildingWindows;
        c.buildingDoors = buildingDoors;
        c.roadCenterLineColour = roadCenterLineColour;
        c.roadSidewalks = roadSidewalks;
        c.seaFromElevation = seaFromElevation;
        c.treeDensityForest = treeDensityForest;
        c.climateOverride = climateOverride;
        c.snowAtHighElevation = snowAtHighElevation;
        c.snowTemperatureC = snowTemperatureC;
        c.temperatureOffsetC = temperatureOffsetC;
        c.metersPerStorey = metersPerStorey;
        c.useAerialImagery = useAerialImagery;
        c.useHighResElevation = useHighResElevation;
        c.lidarSurfaceModel = lidarSurfaceModel;
        c.worldCoverLandCover = worldCoverLandCover;
        c.atlasBuildingHeights = atlasBuildingHeights;
        c.useBathymetry = useBathymetry;
    }

    /** The installation config with this world's settings applied (a copy; the base is untouched). */
    public OrbisConfig effective(OrbisConfig base) {
        OrbisConfig c = base.copy();
        applyTo(c);
        c.sanitizeValues();
        return c;
    }

    public WorldSettings copy() {
        return fromJson(toJson());
    }

    public JsonElement toJson() {
        return GSON.toJsonTree(this);
    }

    public static WorldSettings fromJson(JsonElement json) {
        WorldSettings s = GSON.fromJson(json, WorldSettings.class);
        if (s == null) s = new WorldSettings();
        if (s.verticalMode == null) s.verticalMode = "relative";
        if (s.projection == null) s.projection = "equirectangular";
        if (s.demZoom < 8 || s.demZoom > 16) s.demZoom = 15;
        if (s.roadCenterLineColour == null) s.roadCenterLineColour = "white";
        if (s.climateOverride == null) s.climateOverride = "";
        return s;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT, "%.5f, %.5f at 1 block = %s m, sea level Y=%d, %s, %s",
                originLat, originLon, metersPerBlock, seaLevelY, verticalMode, projection);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorldSettings w && toJson().equals(w.toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
