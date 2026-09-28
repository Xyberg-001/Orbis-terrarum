package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.net.Geocoder;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.NameableEnum;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The settings screens, built with YetAnotherConfigLib so they get proper
 * sliders, toggles, search, descriptions and reset buttons for free. Both
 * screens edit a working copy of the config and only commit on Done.
 */
public final class YaclScreens {

    private YaclScreens() {}

    // ------------------------------------------------------------------ screens

    /** New-world screen: location, scale, features, style. Hands the chosen settings to onSave. */
    public static Screen worldSettings(Screen parent, Consumer<WorldSettings> onSave) {
        OrbisConfig c = OrbisMod.config().copy();
        java.util.Map<String, Option<?>> reg = new java.util.HashMap<>();
        return YetAnotherConfigLib.createBuilder()
                .title(t("screen.world"))
                .category(locationCategory(c, reg))
                .category(scaleCategory(c, reg))
                .category(featuresCategory(c, reg))
                .category(styleCategory(c, reg))
                .category(dataCategory(c, false, reg))
                .save(() -> onSave.accept(WorldSettings.fromConfig(c)))
                .build()
                .generateScreen(parent);
    }

    /** Mod Menu screen: the same, plus data sources, network and performance, saved as the installation config. */
    public static Screen installationSettings(Screen parent) {
        OrbisConfig c = OrbisMod.config().copy();
        java.util.Map<String, Option<?>> reg = new java.util.HashMap<>();
        return YetAnotherConfigLib.createBuilder()
                .title(t("screen.config"))
                .category(locationCategory(c, reg))
                .category(scaleCategory(c, reg))
                .category(featuresCategory(c, reg))
                .category(styleCategory(c, reg))
                .category(dataCategory(c, true, reg))
                .category(performanceCategory(c))
                .save(() -> OrbisMod.replaceConfig(c))
                .build()
                .generateScreen(parent);
    }

    // ------------------------------------------------------------------ categories

    private static ConfigCategory locationCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        Option<Double> lat = Option.<Double>createBuilder()
                .name(t("opt.originLat")).description(d("opt.originLat"))
                .binding(60.39299, () -> c.originLat, v -> c.originLat = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(-85.0, 85.0))
                .build();
        Option<Double> lon = Option.<Double>createBuilder()
                .name(t("opt.originLon")).description(d("opt.originLon"))
                .binding(5.32415, () -> c.originLon, v -> c.originLon = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(-180.0, 180.0))
                .build();

        LocationPreset[] chosen = {LocationPreset.CUSTOM};
        Option<LocationPreset> preset = Option.<LocationPreset>createBuilder()
                .name(t("opt.preset")).description(d("opt.preset"))
                .binding(LocationPreset.CUSTOM, () -> chosen[0], v -> chosen[0] = v)
                .controller(o -> EnumControllerBuilder.create(o).enumClass(LocationPreset.class))
                .listener((opt, value) -> {
                    if (value != null && value != LocationPreset.CUSTOM) {
                        lat.requestSet(value.lat);
                        lon.requestSet(value.lon);
                    }
                })
                .build();

        String[] query = {""};
        Option<String> search = Option.<String>createBuilder()
                .name(t("opt.search")).description(d("opt.search"))
                .binding("", () -> query[0], v -> query[0] = v)
                .controller(StringControllerBuilder::create)
                .build();
        ButtonOption lookup = ButtonOption.createBuilder()
                .name(t("opt.lookup")).text(t("opt.lookup.button")).description(d("opt.lookup"))
                .action((screen, opt) -> {
                    String q = search.pendingValue();
                    Geocoder.lookup(q).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                        if (error != null || result == null) {
                            toast(t("toast.notfound"), Component.literal(q + (error != null && error.getMessage() != null ? " (" + error.getMessage() + ")" : "")));
                        } else {
                            lat.requestSet(result.lat());
                            lon.requestSet(result.lon());
                            toast(t("toast.found"), Component.literal(result.name()));
                        }
                    }));
                })
                .build();

        reg.put("originLat", lat);
        reg.put("originLon", lon);
        reg.put("search", search);

        // Area preview, at the location chosen above and the scale on the Scale tab (their pending values, so
        // there is no need to press Done first): opens AreaPreviewScreen, a map inside the game.
        String[] area = {""};
        Option<String> areaField = Option.<String>createBuilder()
                .name(t("opt.previewArea")).description(d("opt.previewArea"))
                .binding("", () -> area[0], v -> area[0] = v)
                .controller(StringControllerBuilder::create)
                .build();
        // Spawn point: where players of the new world appear, if not at the location above.
        Option<Boolean> customSpawn = bool("customSpawn", false, () -> c.customSpawn, v -> c.customSpawn = v);
        Option<Double> spawnLat = Option.<Double>createBuilder()
                .name(t("opt.spawnLat")).description(d("opt.spawnLat"))
                .binding(60.39299, () -> c.spawnLat, v -> c.spawnLat = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(-85.0, 85.0))
                .build();
        Option<Double> spawnLon = Option.<Double>createBuilder()
                .name(t("opt.spawnLon")).description(d("opt.spawnLon"))
                .binding(5.32415, () -> c.spawnLon, v -> c.spawnLon = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(-180.0, 180.0))
                .build();
        String[] spawnQuery = {""};
        Option<String> spawnSearch = Option.<String>createBuilder()
                .name(t("opt.spawnSearch")).description(d("opt.spawnSearch"))
                .binding("", () -> spawnQuery[0], v -> spawnQuery[0] = v)
                .controller(StringControllerBuilder::create)
                .build();
        ButtonOption spawnLookup = ButtonOption.createBuilder()
                .name(t("opt.spawnLookup")).text(t("opt.lookup.button")).description(d("opt.spawnLookup"))
                .action((screen, opt) -> {
                    String q = spawnSearch.pendingValue();
                    Geocoder.lookup(q).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                        if (error != null || result == null) {
                            toast(t("toast.notfound"), Component.literal(q + (error != null && error.getMessage() != null ? " (" + error.getMessage() + ")" : "")));
                        } else {
                            spawnLat.requestSet(result.lat());
                            spawnLon.requestSet(result.lon());
                            customSpawn.requestSet(true);
                            toast(t("toast.spawn"), Component.literal(result.name()));
                        }
                    }));
                })
                .build();
        Option<Boolean> exactSpawn = bool("exactSpawn", true, () -> c.exactSpawn, v -> c.exactSpawn = v);
        // While the spawn is not custom it follows the location, so switching it on starts from there.
        lat.addListener((opt, v) -> {
            if (!Boolean.TRUE.equals(customSpawn.pendingValue()) && v != null) spawnLat.requestSet(v);
        });
        lon.addListener((opt, v) -> {
            if (!Boolean.TRUE.equals(customSpawn.pendingValue()) && v != null) spawnLon.requestSet(v);
        });

        ButtonOption preview = ButtonOption.createBuilder()
                .name(t("opt.previewOpen")).text(t("opt.previewOpen.button")).description(d("opt.previewOpen"))
                .action((screen, opt) -> {
                    Option<?> scale = reg.get("metersPerBlock"), proj = reg.get("projection");
                    double mpb = scale != null && scale.pendingValue() instanceof Double v ? v : c.metersPerBlock;
                    String projection = proj != null ? String.valueOf(proj.pendingValue()) : c.projection;
                    String a = areaField.pendingValue() == null ? "" : areaField.pendingValue();
                    double[] spawn = Boolean.TRUE.equals(customSpawn.pendingValue()) ? new double[]{spawnLat.pendingValue(), spawnLon.pendingValue()} : null;
                    Minecraft.getInstance().setScreenAndShow(new AreaPreviewScreen(screen, a, lat.pendingValue(), lon.pendingValue(), mpb, projection,
                            areaField::requestSet, v -> {
                                @SuppressWarnings("unchecked") Option<Double> o = (Option<Double>) scale;
                                if (o != null) o.requestSet(v);
                            }, spawn, ll -> {
                                spawnLat.requestSet(ll[0]);
                                spawnLon.requestSet(ll[1]);
                                customSpawn.requestSet(true);
                            }));
                })
                .build();
        return ConfigCategory.createBuilder()
                .name(t("cat.location"))
                .tooltip(t("cat.location.desc"))
                .option(LabelOption.create(t("cat.location.intro")))
                .option(preset)
                .option(search)
                .option(lookup)
                .option(lat)
                .option(lon)
                .group(OptionGroup.createBuilder()
                        .name(t("group.spawn"))
                        .description(OptionDescription.of(t("group.spawn.desc")))
                        .option(customSpawn)
                        .option(spawnSearch)
                        .option(spawnLookup)
                        .option(spawnLat)
                        .option(spawnLon)
                        .option(exactSpawn)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.preview"))
                        .description(OptionDescription.of(t("group.preview.desc")))
                        .option(areaField)
                        .option(preview)
                        .build())
                .build();
    }

    private static ConfigCategory scaleCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        Option<Double> metersPerBlock = dbl("metersPerBlock", 1.0, 0.25, 64.0, () -> c.metersPerBlock, v -> c.metersPerBlock = v);
        Option<VerticalMode> verticalMode = Option.<VerticalMode>createBuilder()
                .name(t("opt.verticalMode")).description(d("opt.verticalMode"))
                .binding(VerticalMode.RELATIVE, () -> VerticalMode.of(c.verticalMode), v -> c.verticalMode = v.name().toLowerCase(Locale.ROOT))
                .controller(o -> EnumControllerBuilder.create(o).enumClass(VerticalMode.class))
                .build();
        Option<String> projection = cycling("projection", "equirectangular", List.of("equirectangular", "transverse_mercator"),
                () -> c.projection, v -> c.projection = v);
        Option<Integer> demZoom = intSlider("demZoom", 15, 8, 16, 1, () -> c.demZoom, v -> c.demZoom = v, v -> Component.literal(String.valueOf(v)));
        WorldPreset[] chosen = {WorldPreset.CUSTOM};
        Option<WorldPreset> preset = Option.<WorldPreset>createBuilder()
                .name(t("opt.worldPreset")).description(d("opt.worldPreset"))
                .binding(WorldPreset.CUSTOM, () -> chosen[0], v -> chosen[0] = v)
                .controller(o -> EnumControllerBuilder.create(o).enumClass(WorldPreset.class))
                .listener((opt, value) -> {
                    if (value == null || value == WorldPreset.CUSTOM) return;
                    boolean map = value == WorldPreset.COUNTRY_MAP_1_32;
                    metersPerBlock.requestSet(map ? 32.0 : 1.0);
                    verticalMode.requestSet(map ? VerticalMode.CLAMP : VerticalMode.RELATIVE);
                    projection.requestSet(map ? "transverse_mercator" : "equirectangular");
                    demZoom.requestSet(map ? 11 : 15);
                    // Off in a country map: things with a real-world size that cannot be drawn at 32 m per block,
                    // and the city-zoom downloads. Vanilla gameplay (trees, ores, caves, structures, animals) stays on.
                    for (String key : new String[]{"generateBuildings", "generateStreetFurniture", "generateSchematics", "streetLights",
                            "useAerialImagery", "useHighResElevation", "roadSidewalks"}) {
                        setBool(reg, key, !map);
                    }
                    for (String key : new String[]{"generateRoads", "generateWater", "generateLandCover", "generateBedrock", "spawnAnimals",
                            "generateTrees", "generateOres", "vanillaStructures", "vanillaCaves", "interiorLights", "settlementBuildings", "villagerResidents",
                            "buildingSigns", "furnishInteriors", "streetLife", "transitLines", "landmarkAdvancements", "tunnelLoot"}) {
                        setBool(reg, key, true);
                    }
                    setBool(reg, "majorRoadsOnly", map);
                })
                .build();
        reg.put("metersPerBlock", metersPerBlock);
        reg.put("projection", projection);
        return ConfigCategory.createBuilder()
                .name(t("cat.scale"))
                .tooltip(t("cat.scale.desc"))
                .option(LabelOption.create(t("cat.scale.intro")))
                .option(preset)
                .option(metersPerBlock)
                .option(projection)
                .option(demZoom)
                .option(verticalMode)
                .option(intSlider("reliefKneeMeters", 1500, 0, 6000, 100, () -> (int) c.reliefKneeMeters, v -> c.reliefKneeMeters = v, v -> Component.literal(v + " m")))
                .option(intSlider("reliefSmoothingKm", 25, 5, 80, 5, () -> (int) c.reliefSmoothingKm, v -> c.reliefSmoothingKm = v, v -> Component.literal(v + " km")))
                .option(Option.<Integer>createBuilder()
                        .name(t("opt.seaLevelY")).description(d("opt.seaLevelY"))
                        .binding(OrbisConfig.DEFAULT_SEA_LEVEL_Y, () -> c.seaLevelY, v -> c.seaLevelY = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(OrbisConfig.DIMENSION_MIN_Y + 64, OrbisConfig.DIMENSION_MIN_Y + OrbisConfig.DIMENSION_HEIGHT - 1 - 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(t("opt.worldHeight")).description(d("opt.worldHeight"))
                        .binding(0, () -> c.worldHeight, v -> c.worldHeight = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, OrbisConfig.DIMENSION_HEIGHT))
                        .build())
                .build();
    }

    private static ConfigCategory featuresCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        return ConfigCategory.createBuilder()
                .name(t("cat.features"))
                .tooltip(t("cat.features.desc"))
                .option(boolR(reg, "generateBuildings", true, () -> c.generateBuildings, v -> c.generateBuildings = v))
                .option(boolR(reg, "generateRoads", true, () -> c.generateRoads, v -> c.generateRoads = v))
                .option(boolR(reg, "majorRoadsOnly", false, () -> c.majorRoadsOnly, v -> c.majorRoadsOnly = v))
                .option(boolR(reg, "settlementBuildings", true, () -> c.settlementBuildings, v -> c.settlementBuildings = v))
                .option(boolR(reg, "villagerResidents", true, () -> c.villagerResidents, v -> c.villagerResidents = v))
                .option(dblSlider("residentsPerChunk", 0.3, 0.0, 1.5, 0.1, () -> c.residentsPerChunk, v -> c.residentsPerChunk = v, "%.1f"))
                .option(boolR(reg, "residentJobs", false, () -> c.residentJobs, v -> c.residentJobs = v))
                .option(boolR(reg, "buildingSigns", true, () -> c.buildingSigns, v -> c.buildingSigns = v))
                .option(boolR(reg, "furnishInteriors", true, () -> c.furnishInteriors, v -> c.furnishInteriors = v))
                .option(boolR(reg, "streetLife", true, () -> c.streetLife, v -> c.streetLife = v))
                .option(boolR(reg, "transitLines", true, () -> c.transitLines, v -> c.transitLines = v))
                .option(boolR(reg, "landmarkAdvancements", true, () -> c.landmarkAdvancements, v -> c.landmarkAdvancements = v))
                .option(boolR(reg, "tunnelLoot", true, () -> c.tunnelLoot, v -> c.tunnelLoot = v))
                .option(boolR(reg, "generateWater", true, () -> c.generateWater, v -> c.generateWater = v))
                .option(boolR(reg, "generateLandCover", true, () -> c.generateLandCover, v -> c.generateLandCover = v))
                .option(boolR(reg, "generateTrees", true, () -> c.generateTrees, v -> c.generateTrees = v))
                .option(boolR(reg, "generateStreetFurniture", true, () -> c.generateStreetFurniture, v -> c.generateStreetFurniture = v))
                .option(boolR(reg, "generateSchematics", true, () -> c.generateSchematics, v -> c.generateSchematics = v))
                .option(boolR(reg, "generateBedrock", true, () -> c.generateBedrock, v -> c.generateBedrock = v))
                .option(boolR(reg, "generateOres", true, () -> c.generateOres, v -> c.generateOres = v))
                .option(boolR(reg, "spawnAnimals", true, () -> c.spawnAnimals, v -> c.spawnAnimals = v))
                .option(boolR(reg, "vanillaStructures", true, () -> c.vanillaStructures, v -> c.vanillaStructures = v))
                .option(boolR(reg, "vanillaCaves", true, () -> c.vanillaCaves, v -> c.vanillaCaves = v))
                .option(boolR(reg, "interiorLights", true, () -> c.interiorLights, v -> c.interiorLights = v))
                .option(boolR(reg, "streetLights", true, () -> c.streetLights, v -> c.streetLights = v))
                .build();
    }

    /** World type presets: fill in every setting for a kind of world (see the lang file for what each does). */
    public enum WorldPreset implements NameableEnum {
        CUSTOM, CITY_1_1, COUNTRY_MAP_1_32;

        @Override
        public Component getDisplayName() {
            return Component.translatable("orbisterrarum.worldpreset." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static Option<Boolean> boolR(java.util.Map<String, Option<?>> reg, String key, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
        Option<Boolean> o = bool(key, def, get, set);
        reg.put(key, o);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static void setBool(java.util.Map<String, Option<?>> reg, String key, boolean value) {
        Option<?> o = reg.get(key);
        if (o != null) ((Option<Boolean>) o).requestSet(value);
    }

    private static ConfigCategory styleCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        return ConfigCategory.createBuilder()
                .name(t("cat.style"))
                .tooltip(t("cat.style.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.buildings"))
                        .option(bool("hollowBuildings", true, () -> c.hollowBuildings, v -> c.hollowBuildings = v))
                        .option(bool("buildingWindows", true, () -> c.buildingWindows, v -> c.buildingWindows = v))
                        .option(bool("buildingDoors", true, () -> c.buildingDoors, v -> c.buildingDoors = v))
                        .option(intSlider("metersPerStorey", 3, 2, 6, 1, () -> c.metersPerStorey, v -> c.metersPerStorey = v, v -> Component.literal(v + " m")))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.roads"))
                        .option(cycling("roadCenterLineColour", "white", List.of("white", "yellow", "none"), () -> c.roadCenterLineColour, v -> c.roadCenterLineColour = v))
                        .option(boolR(reg, "roadSidewalks", true, () -> c.roadSidewalks, v -> c.roadSidewalks = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.nature"))
                        .option(bool("seaFromElevation", true, () -> c.seaFromElevation, v -> c.seaFromElevation = v))
                        .option(dblSlider("treeDensityForest", 0.045, 0.0, 0.3, 0.005, () -> c.treeDensityForest, v -> c.treeDensityForest = v, "%.3f"))
                        .option(cycling("climateOverride", "", List.of("", "arid", "humid"), () -> c.climateOverride, v -> c.climateOverride = v))
                        .option(dblSlider("temperatureOffsetC", 0.0, -30.0, 30.0, 0.5, () -> c.temperatureOffsetC, v -> c.temperatureOffsetC = v, "%+.1f °C"))
                        .option(dblSlider("snowTemperatureC", 0.5, -20.0, 20.0, 0.5, () -> c.snowTemperatureC, v -> c.snowTemperatureC = v, "%.1f °C"))
                        .option(bool("snowAtHighElevation", true, () -> c.snowAtHighElevation, v -> c.snowAtHighElevation = v))
                        .build())
                .build();
    }

    private static ConfigCategory dataCategory(OrbisConfig c, boolean installation, java.util.Map<String, Option<?>> reg) {
        ConfigCategory.Builder b = ConfigCategory.createBuilder()
                .name(t("cat.data"))
                .tooltip(t("cat.data.desc"))
                .option(boolR(reg, "useAerialImagery", true, () -> c.useAerialImagery, v -> c.useAerialImagery = v))
                .option(boolR(reg, "useHighResElevation", false, () -> c.useHighResElevation, v -> c.useHighResElevation = v))
                .option(boolR(reg, "worldCoverLandCover", true, () -> c.worldCoverLandCover, v -> c.worldCoverLandCover = v))
                .option(boolR(reg, "atlasBuildingHeights", true, () -> c.atlasBuildingHeights, v -> c.atlasBuildingHeights = v))
                .option(boolR(reg, "useBathymetry", true, () -> c.useBathymetry, v -> c.useBathymetry = v))
                .option(boolR(reg, "nvdbRoadWidths", true, () -> c.nvdbRoadWidths, v -> c.nvdbRoadWidths = v))
                .option(boolR(reg, "externalPlaces", true, () -> c.externalPlaces, v -> c.externalPlaces = v))
                .option(boolR(reg, "treesFromCanopy", true, () -> c.treesFromCanopy, v -> c.treesFromCanopy = v))
                .option(boolR(reg, "roofsFromSurfaceModel", true, () -> c.roofsFromSurfaceModel, v -> c.roofsFromSurfaceModel = v))
                .option(boolR(reg, "groundClassesFromModel", true, () -> c.groundClassesFromModel, v -> c.groundClassesFromModel = v));
        if (installation) {
            b.group(OptionGroup.createBuilder()
                    .name(t("group.imagery"))
                    .option(intSlider("imageryZoom", 18, 14, 20, 1, () -> c.imageryZoom, v -> c.imageryZoom = v, v -> Component.literal(String.valueOf(v))))
                    .option(bool("imageryRoofColours", true, () -> c.imageryRoofColours, v -> c.imageryRoofColours = v))
                    .option(bool("imageryGroundClassification", true, () -> c.imageryGroundClassification, v -> c.imageryGroundClassification = v))
                    .option(bool("imageryTreeCover", true, () -> c.imageryTreeCover, v -> c.imageryTreeCover = v))
                    .option(bool("buildingHeightsFromSurfaceModel", true, () -> c.buildingHeightsFromSurfaceModel, v -> c.buildingHeightsFromSurfaceModel = v))
                    .build());
            b.option(LabelOption.create(t("cat.data.sources").withStyle(ChatFormatting.GRAY)));
        }
        return b.build();
    }

    private static ConfigCategory performanceCategory(OrbisConfig c) {
        return ConfigCategory.createBuilder()
                .name(t("cat.performance"))
                .tooltip(t("cat.performance.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.streaming"))
                        .option(bool("waitForOsm", true, () -> c.waitForOsm, v -> c.waitForOsm = v))
                        .option(bool("prefetchSpawnAtStartup", true, () -> c.prefetchSpawnAtStartup, v -> c.prefetchSpawnAtStartup = v))
                        .option(intSlider("regionPrefetchRadius", 2, 0, 6, 1, () -> c.regionPrefetchRadius, v -> c.regionPrefetchRadius = v, v -> Component.literal(v + " (" + (2 * v + 1) * 512 + " m)")))
                        .option(intSlider("regionCacheSize", 64, 8, 256, 8, () -> c.regionCacheSize, v -> c.regionCacheSize = v, v -> Component.literal(v + " (~" + (v * 6) + " MB)")))
                        .option(intSlider("terrainOnlyBeyondBlocks", 0, 0, 8192, 256, () -> c.terrainOnlyBeyondBlocks, v -> c.terrainOnlyBeyondBlocks = v,
                                v -> v == 0 ? t("value.off") : Component.literal(v + " m")))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.network"))
                        .option(intSlider("overpassConcurrentRequests", 4, 1, 8, 1, () -> c.overpassConcurrentRequests, v -> c.overpassConcurrentRequests = v, v -> Component.literal(String.valueOf(v))))
                        .option(intSlider("osmMaxWaitMinutes", 15, 1, 60, 1, () -> c.osmMaxWaitMinutes, v -> c.osmMaxWaitMinutes = v, v -> Component.literal(v + " min")))
                        .option(intSlider("demTileCacheSize", 384, 32, 2048, 32, () -> c.demTileCacheSize, v -> c.demTileCacheSize = v, v -> Component.literal(String.valueOf(v))))
                        .option(intSlider("imageryTileCacheSize", 512, 64, 4096, 64, () -> c.imageryTileCacheSize, v -> c.imageryTileCacheSize = v, v -> Component.literal(String.valueOf(v))))
                        .option(bool("debugLogging", false, () -> c.debugLogging, v -> c.debugLogging = v))
                        .build())
                .build();
    }

    // ------------------------------------------------------------------ helpers

    public enum VerticalMode implements NameableEnum {
        RELATIVE, COMPRESS, CLAMP;

        static VerticalMode of(String s) {
            try {
                return valueOf(s == null ? "RELATIVE" : s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return RELATIVE;
            }
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("orbisterrarum.value.verticalMode." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static MutableComponent t(String key) {
        return Component.translatable("orbisterrarum." + key);
    }

    private static OptionDescription d(String key) {
        return OptionDescription.of(Component.translatable("orbisterrarum." + key + ".desc"));
    }

    private static Option<Boolean> bool(String key, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
        return Option.<Boolean>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, get, set)
                .controller(o -> BooleanControllerBuilder.create(o).yesNoFormatter().coloured(true))
                .build();
    }

    private static Option<Integer> intSlider(String key, int def, int min, int max, int step, Supplier<Integer> get, Consumer<Integer> set,
                                             java.util.function.Function<Integer, Component> format) {
        return Option.<Integer>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, get, set)
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(min, max).step(step).valueFormatter(format))
                .build();
    }

    /**
     * A slider rather than a text field: YACL's number field only hands its text to the option when the field
     * loses focus (Enter, or a click elsewhere in the list), so a value typed and then saved straight away was
     * silently dropped. Sliders apply as they move.
     */
    private static Option<Double> dblSlider(String key, double def, double min, double max, double step, Supplier<Double> get,
                                            Consumer<Double> set, String format) {
        return Option.<Double>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, get, set)
                .controller(o -> DoubleSliderControllerBuilder.create(o).range(min, max).step(step)
                        .valueFormatter(v -> Component.literal(String.format(Locale.ROOT, format, v))))
                .build();
    }

    /** A typed number; it is committed when the field loses focus, so the description tells the player to press Enter. */
    private static Option<Double> dbl(String key, double def, double min, double max, Supplier<Double> get, Consumer<Double> set) {
        return Option.<Double>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, get, set)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(min, max))
                .build();
    }

    private static Option<String> cycling(String key, String def, List<String> values, Supplier<String> get, Consumer<String> set) {
        return Option.<String>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, () -> values.contains(get.get()) ? get.get() : def, set)
                .controller(o -> CyclingListControllerBuilder.create(o).values(values)
                        .valueFormatter(v -> Component.translatable("orbisterrarum.value." + key + "." + (v.isEmpty() ? "auto" : v))))
                .build();
    }

    private static void toast(Component title, Component text) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text);
    }
}
