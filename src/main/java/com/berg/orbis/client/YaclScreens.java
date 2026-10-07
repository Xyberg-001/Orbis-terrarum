package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
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
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
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

    /*
     * The tabs run from what matters most to what most players never touch: World (where, how big, what to
     * generate), Features, Look & Climate, Data Sources, Advanced (and Performance & Network in Mod Menu). Groups
     * few players need start folded.
     */

    /**
     * New-world screen (Create New World → Customize): everything that is generated, and only that. Hands the chosen
     * settings to onSave; they are also the defaults for the next world.
     */
    public static Screen worldSettings(Screen parent, Consumer<WorldSettings> onSave) {
        OrbisConfig c = OrbisMod.config().copy();
        java.util.Map<String, Option<?>> reg = new java.util.HashMap<>();
        ScaleOptions scale = scaleOptions(c, reg);
        return YetAnotherConfigLib.createBuilder()
                .title(t("screen.world"))
                .category(worldCategory(c, reg, scale, parent, onSave))
                .category(featuresCategory(c, reg))
                .category(styleCategory(c, reg))
                .category(dataCategory(c, reg))
                .category(advancedCategory(c, reg, scale))
                .save(() -> {
                    // How the photos are used is installation-wide (not saved in a world): it goes to the mod's settings.
                    OrbisMod.updateConfig(x -> {
                        x.imageryZoom = c.imageryZoom;
                        x.imageryRoofColours = c.imageryRoofColours;
                        x.imageryGroundClassification = c.imageryGroundClassification;
                        x.imageryTreeCover = c.imageryTreeCover;
                        x.buildingHeightsFromSurfaceModel = c.buildingHeightsFromSurfaceModel;
                    });
                    // The selection drawn in the world preview goes with the world (AutoPregen generates it).
                    c.pregenShapes = com.berg.orbis.client.map.MapSelectTool.previewShapes();
                    c.pregenSelectionSkipsSea = com.berg.orbis.client.map.MapSelectTool.previewSkipsSea();
                    com.berg.orbis.client.map.PreviewChoices.applyTo(c);
                    onSave.accept(WorldSettings.fromConfig(c));
                })
                .build()
                .generateScreen(parent);
    }

    /**
     * Mod Menu screen: the mod itself, never a world (worlds are set up in Customize, which remembers the last
     * choices): an overview, the downloads on disk, performance and network. Saved as the installation config.
     */
    public static Screen installationSettings(Screen parent) {
        OrbisConfig c = OrbisMod.config().copy();
        return YetAnotherConfigLib.createBuilder()
                .title(t("screen.config"))
                .category(overviewCategory())
                .category(storageCategory(c))
                .category(performanceCategory(c))
                .category(networkCategory(c))
                .save(() -> OrbisMod.replaceConfig(c))
                .build()
                .generateScreen(parent);
    }

    // ------------------------------------------------------------------ categories

    private static ConfigCategory worldCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg, ScaleOptions scaleOptions,
                                                Screen parent, Consumer<WorldSettings> onSave) {
        // Customize remembers the last world's choices; this puts every setting back to the mod's defaults.
        ButtonOption reset = ButtonOption.createBuilder()
                .name(t("opt.resetWorld")).text(t("opt.resetWorld.button")).description(d("opt.resetWorld"))
                .action((screen, opt) -> Minecraft.getInstance().setScreenAndShow(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                    if (yes) OrbisMod.saveWorldDefaults(WorldSettings.fromConfig(new OrbisConfig()));
                    Minecraft.getInstance().setScreenAndShow(yes ? worldSettings(parent, onSave) : screen);
                }, t("opt.resetWorld"), t("opt.resetWorld.ask"))))
                .build();
        // Where the world is, and where players spawn, is chosen on the world generator map (right-click, or a search):
        // the spawn is the world's centre, block 0, 0. The presets only put the map somewhere to start from. A list on
        // its own screen (PickScreen), not a button that cycles: each click of the old one moved the world to the next
        // city at once and without a word.
        ButtonOption preset = ButtonOption.createBuilder()
                .name(t("opt.preset")).text(t("opt.choose.button")).description(d("opt.preset"))
                .action((screen, opt) -> {
                    List<PickScreen.Choice> choices = new ArrayList<>();
                    for (LocationPreset p : LocationPreset.values()) {
                        if (p == LocationPreset.CUSTOM) continue;
                        choices.add(new PickScreen.Choice(p.getDisplayName(), () -> {
                            com.berg.orbis.client.map.PreviewChoices.setCentre(p.lat, p.lon);
                            toast(t("toast.location"), p.getDisplayName());
                        }));
                    }
                    Minecraft.getInstance().setScreenAndShow(new PickScreen(screen, t("opt.preset"), choices));
                })
                .build();

        // The world generator map, at the scale on this tab (its pending value: no need to press Done first). Its
        // spawn, area and pre-generation switches go into the world at Create (SpawnGate.takeSelection).
        ButtonOption preview = ButtonOption.createBuilder()
                .name(t("opt.previewOpen")).text(t("opt.previewOpen.button")).description(d("opt.previewOpen"))
                .action((screen, opt) -> {
                    Option<?> scale = reg.get("metersPerBlock"), proj = reg.get("projection");
                    double mpb = scale != null && scale.pendingValue() instanceof Double v ? v : c.metersPerBlock;
                    String projection = proj != null ? String.valueOf(proj.pendingValue()) : c.projection;
                    double[] at = com.berg.orbis.client.map.PreviewChoices.centreOr(c);
                    Minecraft.getInstance().setScreenAndShow(new AreaPreviewScreen(screen, at[0], at[1], mpb, projection,
                            v -> {
                                @SuppressWarnings("unchecked") Option<Double> o = (Option<Double>) scale;
                                if (o != null) o.requestSet(v);
                            },
                            ll -> com.berg.orbis.client.map.PreviewChoices.setCentre(ll[0], ll[1]),
                            // Switched on here, or carried over from the last world only while an area is selected:
                            // with nothing selected they do nothing, and Create turns them off (SpawnGate).
                            () -> {
                                Boolean b = com.berg.orbis.client.map.PreviewChoices.generate();
                                return b != null ? b : c.pregenOnCreate && !com.berg.orbis.client.map.MapSelectTool.previewShapes().isEmpty();
                            }, com.berg.orbis.client.map.PreviewChoices::setGenerate,
                            () -> {
                                Boolean b = com.berg.orbis.client.map.PreviewChoices.limit();
                                return b != null ? b : c.pregenHardLimit && !com.berg.orbis.client.map.MapSelectTool.previewShapes().isEmpty();
                            }, com.berg.orbis.client.map.PreviewChoices::setLimit));
                })
                .build();
        return ConfigCategory.createBuilder()
                .name(t("cat.location"))
                .tooltip(t("cat.location.desc"))
                .option(LabelOption.create(t("cat.location.intro")))
                .option(preview)
                .option(preset)
                .option(scaleOptions.metersPerBlock())
                .option(scaleOptions.worldPreset())
                .option(reset)
                .build();
    }

    /** The scale and terrain options: World scale and World type on the World tab, the technical rest on Advanced. */
    private record ScaleOptions(Option<Double> metersPerBlock, ButtonOption worldPreset, Option<String> projection,
                                Option<Integer> demZoom, Option<Integer> reliefKnee,
                                Option<Integer> reliefSmoothing, Option<Integer> seaLevel, Option<Integer> worldHeight) {
    }

    private static ScaleOptions scaleOptions(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        Option<Double> metersPerBlock = dbl("metersPerBlock", 1.0, 0.25, 64.0, () -> c.metersPerBlock, v -> c.metersPerBlock = v);
        Option<String> projection = cycling("projection", "equirectangular", List.of("equirectangular", "transverse_mercator"),
                () -> c.projection, v -> c.projection = v);
        Option<Integer> demZoom = intSlider("demZoom", 15, 8, 16, 1, () -> c.demZoom, v -> c.demZoom = v, v -> Component.literal(String.valueOf(v)));
        // A new scale gets the terrain detail that suits it (a chosen 16 stays up to 1:2); it can still be set by hand after.
        double[] lastScale = {c.metersPerBlock};
        metersPerBlock.addListener((opt, v) -> {
            if (v == null || Math.abs(v - lastScale[0]) < 1e-9) return;
            lastScale[0] = v;
            int want = OrbisConfig.demZoomFor(v);
            Integer now = demZoom.pendingValue();
            if (!(v <= 2.0 && now != null && now >= want)) demZoom.requestSet(want);
            // Coarser than 1:4 the lidar downloads buy nothing: switch them off (never switched on by a scale change).
            if (!OrbisConfig.lidarUseful(v)) {
                setBool(reg, "useHighResElevation", false);
                setBool(reg, "lidarSurfaceModel", false);
            }
        });
        // A list on its own screen, like the location presets: a button that cycled changed some twenty settings per click.
        Consumer<WorldPreset> applyWorldType = value -> {
                    toast(t("toast.worldType"), value.getDisplayName());
                    boolean map = value == WorldPreset.COUNTRY_MAP_1_32;
                    metersPerBlock.requestSet(map ? 32.0 : 1.0);
                    projection.requestSet(map ? "transverse_mercator" : "equirectangular");
                    demZoom.requestSet(map ? 11 : 15);
                    // Off in a country map: things with a real-world size that cannot be drawn at 32 m per block,
                    // and the city-zoom downloads. Vanilla gameplay (trees, ores, caves, structures, animals) stays on.
                    for (String key : new String[]{"generateBuildings", "generateStreetFurniture", "generateSchematics", "streetLights",
                            "useAerialImagery", "roadSidewalks"}) {
                        setBool(reg, key, !map);
                    }
                    // Kartverket's lidar terrain duplicates Mapterhorn's and is slow: never switched on by a preset.
                    if (map) {
                        setBool(reg, "useHighResElevation", false);
                        setBool(reg, "lidarSurfaceModel", false);
                    }
                    for (String key : new String[]{"generateRoads", "generateWater", "generateLandCover", "generateBedrock", "spawnAnimals",
                            "generateTrees", "generateOres", "vanillaStructures", "vanillaCaves", "interiorLights", "settlementBuildings", "villagerResidents",
                            "buildingSigns", "streetSigns", "furnishInteriors", "streetLife", "transitLines", "landmarkAdvancements", "tunnelLoot"}) {
                        setBool(reg, key, true);
                    }
                    setBool(reg, "majorRoadsOnly", map);
        };
        ButtonOption preset = ButtonOption.createBuilder()
                .name(t("opt.worldPreset")).text(t("opt.choose.button")).description(d("opt.worldPreset"))
                .action((screen, opt) -> {
                    List<PickScreen.Choice> choices = new ArrayList<>();
                    for (WorldPreset p : WorldPreset.values()) {
                        if (p != WorldPreset.CUSTOM) choices.add(new PickScreen.Choice(p.getDisplayName(), () -> applyWorldType.accept(p)));
                    }
                    Minecraft.getInstance().setScreenAndShow(new PickScreen(screen, t("opt.worldPreset"), choices));
                })
                .build();
        reg.put("metersPerBlock", metersPerBlock);
        reg.put("projection", projection);
        return new ScaleOptions(metersPerBlock, preset, projection, demZoom,
                intSlider("reliefKneeMeters", 1500, 0, 6000, 100, () -> (int) c.reliefKneeMeters, v -> c.reliefKneeMeters = v, v -> Component.literal(v + " m")),
                intSlider("reliefSmoothingKm", 25, 5, 80, 5, () -> (int) c.reliefSmoothingKm, v -> c.reliefSmoothingKm = v, v -> Component.literal(v + " km")),
                Option.<Integer>createBuilder()
                        .name(t("opt.seaLevelY")).description(d("opt.seaLevelY"))
                        .binding(OrbisConfig.DEFAULT_SEA_LEVEL_Y, () -> c.seaLevelY, v -> c.seaLevelY = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(OrbisConfig.DIMENSION_MIN_Y + 64, OrbisConfig.DIMENSION_MIN_Y + OrbisConfig.DIMENSION_HEIGHT - 1 - 512))
                        .build(),
                Option.<Integer>createBuilder()
                        .name(t("opt.worldHeight")).description(d("opt.worldHeight"))
                        .binding(0, () -> c.worldHeight, v -> c.worldHeight = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, OrbisConfig.DIMENSION_HEIGHT))
                        .build());
    }

    /** Advanced: how the terrain becomes blocks. The defaults suit nearly every world. */
    private static ConfigCategory advancedCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg, ScaleOptions scale) {
        return ConfigCategory.createBuilder()
                .name(t("cat.advanced"))
                .tooltip(t("cat.advanced.desc"))
                .option(LabelOption.create(t("cat.scale.intro")))
                .group(OptionGroup.createBuilder()
                        .name(t("group.spawnpregen"))
                        .collapsed(true)
                        .option(bool("exactSpawn", true, () -> c.exactSpawn, v -> c.exactSpawn = v))
                        .option(bool("pregenSkipOpenSea", true, () -> c.pregenSkipOpenSea, v -> c.pregenSkipOpenSea = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.terrain"))
                        .option(scale.worldHeight())
                        .option(bool("highAltitudeWindow", true, () -> c.highAltitudeWindow, v -> c.highAltitudeWindow = v))
                        .option(bool("uniformHeights", false, () -> c.uniformHeights, v -> c.uniformHeights = v))
                        .option(scale.seaLevel())
                        .option(scale.projection())
                        .option(scale.demZoom())
                        .option(scale.reliefKnee())
                        .option(scale.reliefSmoothing())
                        .option(bool("seaFromElevation", true, () -> c.seaFromElevation, v -> c.seaFromElevation = v))
                        .build())
                // Kartverket's lidar building heights: off by default (slow, and it needs a VPN outside Norway); the
                // world's creation tells what it needs when it is on. (The lidar terrain services are gone: Mapterhorn
                // already carries Kartverket's and USGS's terrain.)
                .group(OptionGroup.createBuilder()
                        .name(t("group.data.lidar"))
                        .collapsed(true)
                        .description(OptionDescription.of(t("group.data.lidar.desc")))
                        .option(boolR(reg, "lidarSurfaceModel", false, () -> c.lidarSurfaceModel, v -> c.lidarSurfaceModel = v))
                        .option(boolR(reg, "roofsFromSurfaceModel", true, () -> c.roofsFromSurfaceModel, v -> c.roofsFromSurfaceModel = v))
                        .build())
                .build();
    }

    private static ConfigCategory featuresCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        return ConfigCategory.createBuilder()
                .name(t("cat.features"))
                .tooltip(t("cat.features.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.cities"))
                        .option(boolR(reg, "generateBuildings", true, () -> c.generateBuildings, v -> c.generateBuildings = v))
                        .option(boolR(reg, "generateRoads", true, () -> c.generateRoads, v -> c.generateRoads = v))
                        .option(boolR(reg, "furnishInteriors", true, () -> c.furnishInteriors, v -> c.furnishInteriors = v))
                        .option(boolR(reg, "buildingSigns", true, () -> c.buildingSigns, v -> c.buildingSigns = v))
                        .option(boolR(reg, "streetSigns", true, () -> c.streetSigns, v -> c.streetSigns = v))
                        .option(boolR(reg, "shopAwnings", true, () -> c.shopAwnings, v -> c.shopAwnings = v))
                        .option(boolR(reg, "generateStreetFurniture", true, () -> c.generateStreetFurniture, v -> c.generateStreetFurniture = v))
                        .option(boolR(reg, "streetLights", true, () -> c.streetLights, v -> c.streetLights = v))
                        .option(boolR(reg, "interiorLights", true, () -> c.interiorLights, v -> c.interiorLights = v))
                        .option(boolR(reg, "generateSchematics", true, () -> c.generateSchematics, v -> c.generateSchematics = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.life"))
                        .option(boolR(reg, "villagerResidents", true, () -> c.villagerResidents, v -> c.villagerResidents = v))
                        .option(dblSlider("residentsPerChunk", 0.3, 0.0, 1.5, 0.1, () -> c.residentsPerChunk, v -> c.residentsPerChunk = v, "%.1f"))
                        .option(boolR(reg, "residentJobs", false, () -> c.residentJobs, v -> c.residentJobs = v))
                        .option(boolR(reg, "streetLife", true, () -> c.streetLife, v -> c.streetLife = v))
                        .option(boolR(reg, "transitLines", true, () -> c.transitLines, v -> c.transitLines = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.landscape"))
                        .option(boolR(reg, "generateWater", true, () -> c.generateWater, v -> c.generateWater = v))
                        .option(boolR(reg, "generateLandCover", true, () -> c.generateLandCover, v -> c.generateLandCover = v))
                        .option(boolR(reg, "generateTrees", true, () -> c.generateTrees, v -> c.generateTrees = v))
                        .option(boolR(reg, "autumnColours", false, () -> c.autumnColours, v -> c.autumnColours = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.gameplay"))
                        .option(boolR(reg, "generateOres", true, () -> c.generateOres, v -> c.generateOres = v))
                        .option(boolR(reg, "vanillaCaves", true, () -> c.vanillaCaves, v -> c.vanillaCaves = v))
                        .option(boolR(reg, "vanillaStructures", true, () -> c.vanillaStructures, v -> c.vanillaStructures = v))
                        .option(boolR(reg, "spawnAnimals", true, () -> c.spawnAnimals, v -> c.spawnAnimals = v))
                        .option(boolR(reg, "generateBedrock", true, () -> c.generateBedrock, v -> c.generateBedrock = v))
                        .option(boolR(reg, "landmarkAdvancements", true, () -> c.landmarkAdvancements, v -> c.landmarkAdvancements = v))
                        .option(boolR(reg, "tunnelLoot", true, () -> c.tunnelLoot, v -> c.tunnelLoot = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.tuning"))
                        .collapsed(true)
                        .option(boolR(reg, "roadGrading", true, () -> c.roadGrading, v -> c.roadGrading = v))
                        .option(boolR(reg, "roadRamps", true, () -> c.roadRamps, v -> c.roadRamps = v))
                        .option(boolR(reg, "majorRoadsOnly", false, () -> c.majorRoadsOnly, v -> c.majorRoadsOnly = v))
                        .option(boolR(reg, "settlementBuildings", true, () -> c.settlementBuildings, v -> c.settlementBuildings = v))
                        .build())
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
                        .name(t("group.sky"))
                        .option(bool("realDaylight", true, () -> c.realDaylight, v -> c.realDaylight = v))
                        .option(bool("realWeather", true, () -> c.realWeather, v -> c.realWeather = v))
                        .option(bool("realSeasons", true, () -> c.realSeasons, v -> c.realSeasons = v))
                        .option(bool("realSnow", true, () -> c.realSnow, v -> c.realSnow = v))
                        .option(bool("villagerClockHours", true, () -> c.villagerClockHours, v -> c.villagerClockHours = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.buildings"))
                        .collapsed(true)
                        .option(bool("hollowBuildings", true, () -> c.hollowBuildings, v -> c.hollowBuildings = v))
                        .option(bool("buildingWindows", true, () -> c.buildingWindows, v -> c.buildingWindows = v))
                        .option(bool("buildingDoors", true, () -> c.buildingDoors, v -> c.buildingDoors = v))
                        .option(cycling("buildingHeights", "auto", List.of("auto", "walkable", "scale"), () -> c.buildingHeights, v -> c.buildingHeights = v))
                        .option(intSlider("metersPerStorey", 3, 2, 6, 1, () -> c.metersPerStorey, v -> c.metersPerStorey = v, v -> Component.literal(v + " m")))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.roads"))
                        .collapsed(true)
                        .option(cycling("roadCenterLineColour", "white", List.of("white", "yellow", "none"), () -> c.roadCenterLineColour, v -> c.roadCenterLineColour = v))
                        .option(boolR(reg, "roadSidewalks", true, () -> c.roadSidewalks, v -> c.roadSidewalks = v))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.nature"))
                        .collapsed(true)
                        .option(dblSlider("treeDensityForest", 0.045, 0.0, 0.3, 0.005, () -> c.treeDensityForest, v -> c.treeDensityForest = v, "%.3f"))
                        .option(cycling("climateOverride", "", List.of("", "arid", "humid"), () -> c.climateOverride, v -> c.climateOverride = v))
                        .option(dblSlider("temperatureOffsetC", 0.0, -30.0, 30.0, 0.5, () -> c.temperatureOffsetC, v -> c.temperatureOffsetC = v, "%+.1f °C"))
                        .option(dblSlider("snowTemperatureC", 0.5, -20.0, 20.0, 0.5, () -> c.snowTemperatureC, v -> c.snowTemperatureC = v, "%.1f °C"))
                        .option(bool("snowAtHighElevation", true, () -> c.snowAtHighElevation, v -> c.snowAtHighElevation = v))
                        .build())
                .build();
    }

    private static ConfigCategory dataCategory(OrbisConfig c, java.util.Map<String, Option<?>> reg) {
        ConfigCategory.Builder b = ConfigCategory.createBuilder()
                .name(t("cat.data"))
                .tooltip(t("cat.data.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.data.auto"))
                        .collapsed(true)
                        .description(OptionDescription.of(t("group.data.auto.desc")))
                        .option(boolR(reg, "useAerialImagery", true, () -> c.useAerialImagery, v -> c.useAerialImagery = v))
                        .option(boolR(reg, "useBathymetry", true, () -> c.useBathymetry, v -> c.useBathymetry = v))
                        .option(boolR(reg, "worldCoverLandCover", true, () -> c.worldCoverLandCover, v -> c.worldCoverLandCover = v))
                        .option(boolR(reg, "bedrockTypes", true, () -> c.bedrockTypes, v -> c.bedrockTypes = v))
                        .option(boolR(reg, "realWaterDepths", true, () -> c.realWaterDepths, v -> c.realWaterDepths = v))
                        .build())
                // A country's own services (photos, rock map, NVDB road widths, lake surveys) are used by themselves
                // wherever they cover; the lidar services, slow and off by default, are in the Advanced tab.
                .group(OptionGroup.createBuilder()
                        .name(t("group.data.import"))
                        .collapsed(true)
                        .description(OptionDescription.of(t("group.data.import.desc")))
                        .option(boolR(reg, "atlasBuildingHeights", true, () -> c.atlasBuildingHeights, v -> c.atlasBuildingHeights = v))
                        .option(boolR(reg, "treesFromCanopy", true, () -> c.treesFromCanopy, v -> c.treesFromCanopy = v))
                        .option(boolR(reg, "externalPlaces", true, () -> c.externalPlaces, v -> c.externalPlaces = v))
                        .build());
        b.group(OptionGroup.createBuilder()
                .name(t("group.imagery"))
                .collapsed(true)
                .option(intSlider("imageryZoom", 18, 14, 20, 1, () -> c.imageryZoom, v -> c.imageryZoom = v, v -> Component.literal(String.valueOf(v))))
                .option(bool("imageryRoofColours", true, () -> c.imageryRoofColours, v -> c.imageryRoofColours = v))
                .option(bool("imageryGroundClassification", true, () -> c.imageryGroundClassification, v -> c.imageryGroundClassification = v))
                .option(bool("imageryTreeCover", true, () -> c.imageryTreeCover, v -> c.imageryTreeCover = v))
                .option(bool("buildingHeightsFromSurfaceModel", true, () -> c.buildingHeightsFromSurfaceModel, v -> c.buildingHeightsFromSurfaceModel = v))
                .build());
        return b.build();
    }

    // ------------------------------------------------------------------ the mod's own settings (Mod Menu)

    /** What the mod has: its version, the data folder, imported extracts and data, the last pre-generations. */
    private static ConfigCategory overviewCategory() {
        ConfigCategory.Builder b = ConfigCategory.createBuilder().name(t("cat.overview")).tooltip(t("cat.overview.desc"));
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        String version = loader.getModContainer("orbisterrarum").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String mc = loader.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        b.option(LabelOption.create(Component.translatable("orbisterrarum.overview.version", version, mc)));
        b.option(LabelOption.create(Component.translatable("orbisterrarum.overview.folder", OrbisMod.dataDir().toAbsolutePath().toString())));
        String disk = DataSourcesScreen.lastTotal();
        b.option(LabelOption.create(disk == null ? t("overview.disk.unknown") : Component.translatable("orbisterrarum.overview.disk", disk)));
        // Imported extracts and data
        var store = com.berg.orbis.osm.extract.LocalExtractStore.get(OrbisMod.dataDir().resolve("extracts"));
        store.rescan();
        OptionGroup.Builder imports = OptionGroup.createBuilder().name(t("overview.imports"));
        if (store.extracts().isEmpty()) imports.option(LabelOption.create(t("overview.noExtracts").withStyle(ChatFormatting.GRAY)));
        for (var e : store.extracts()) imports.option(LabelOption.create(Component.literal(e.describe())));
        String[][] folders = {{"heights", "overview.import.heights"}, {"building-db-cache/de-lod2", "overview.import.lod2"}, {"canopy-cache", "overview.import.canopy"},
                {"lake-depths", "overview.import.lakes"}, {"places", "overview.import.places"}};
        for (String[] f : folders) {
            java.nio.file.Path p = OrbisMod.dataDir().resolve(f[0]);
            boolean any;
            try (var s = java.nio.file.Files.list(p)) {
                any = s.findAny().isPresent();
            } catch (java.io.IOException | RuntimeException e) {
                any = false;
            }
            imports.option(LabelOption.create(t(f[1]).append(": ").append(t(any ? "overview.present" : "overview.absent"))));
        }
        b.group(imports.build());
        // The last pre-generations (kept by PregenTask)
        OptionGroup.Builder pregens = OptionGroup.createBuilder().name(t("overview.pregens"));
        List<String> history = com.berg.orbis.worldgen.PregenTask.history(8);
        if (history.isEmpty()) pregens.option(LabelOption.create(t("overview.noPregens").withStyle(ChatFormatting.GRAY)));
        for (String line : history) pregens.option(LabelOption.create(Component.literal(line)));
        b.group(pregens.build());
        return b.build();
    }

    /** The data folder and what every source keeps in it. */
    private static ConfigCategory storageCategory(OrbisConfig c) {
        return ConfigCategory.createBuilder()
                .name(t("cat.storage"))
                .tooltip(t("cat.storage.desc"))
                .option(ButtonOption.createBuilder()
                        .name(t("opt.sourcesByCountry")).text(t("opt.open.button")).description(d("opt.sourcesByCountry"))
                        .action((screen, opt) -> Minecraft.getInstance().setScreenAndShow(DataSourcesScreen.manage(screen, c, com.berg.orbis.client.map.PreviewChoices.centreOr(c)[0], com.berg.orbis.client.map.PreviewChoices.centreOr(c)[1])))
                        .build())
                .group(storageGroup(c))
                .option(LabelOption.create(t("cat.data.sources").withStyle(ChatFormatting.GRAY)))
                .build();
    }

    /**
     * Where the downloads are kept: the folder in use (the config folder until another is chosen), a button for the
     * system's folder dialog, one to open the folder, one to go back to the default.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean MOVING = new java.util.concurrent.atomic.AtomicBoolean();

    private static OptionGroup storageGroup(OrbisConfig c) {
        String defaultPath = OrbisMod.configDir().toAbsolutePath().toString();
        Option<String> folder = Option.<String>createBuilder()
                .name(t("opt.dataFolder")).description(d("opt.dataFolder"))
                .binding(defaultPath, () -> c.dataFolder == null || c.dataFolder.isBlank() ? defaultPath : c.dataFolder,
                        v -> c.dataFolder = v == null || v.isBlank() || v.trim().equals(defaultPath) ? "" : v.trim())
                .controller(StringControllerBuilder::create)
                .build();
        ButtonOption choose = ButtonOption.createBuilder()
                .name(t("opt.chooseFolder")).text(t("opt.chooseFolder.button")).description(d("opt.chooseFolder"))
                .action((screen, opt) -> {
                    String current = folder.pendingValue();
                    java.nio.file.Path start = current == null || current.isBlank() ? OrbisMod.configDir() : java.nio.file.Path.of(current);
                    FolderPicker.choose(t("opt.chooseFolder").getString(), start).thenAccept(p -> Minecraft.getInstance().execute(() -> {
                        if (p != null) folder.requestSet(p.toAbsolutePath().toString());
                        else toast(t("toast.nofolder"), t("toast.nofolder.desc"));
                    }));
                })
                .build();
        ButtonOption open = ButtonOption.createBuilder()
                .name(t("opt.openFolder")).text(t("opt.openFolder.button")).description(d("opt.openFolder"))
                .action((screen, opt) -> {
                    String current = folder.pendingValue();
                    java.nio.file.Path p = current == null || current.isBlank() ? OrbisMod.configDir() : java.nio.file.Path.of(current);
                    try {
                        java.nio.file.Files.createDirectories(p);
                    } catch (java.io.IOException ignored) {
                    }
                    com.berg.orbis.mc.McClient.openPath(p);
                })
                .build();
        ButtonOption move = ButtonOption.createBuilder()
                .name(t("opt.moveData")).text(t("opt.moveData.button")).description(d("opt.moveData"))
                .action((screen, opt) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level != null) {
                        toast(t("toast.move.inworld"), t("toast.move.inworld.desc"));
                        return;
                    }
                    String target = folder.pendingValue();
                    java.nio.file.Path to = target == null || target.isBlank() ? OrbisMod.configDir() : java.nio.file.Path.of(target.trim()).toAbsolutePath().normalize();
                    java.nio.file.Path from = OrbisMod.dataDir().toAbsolutePath().normalize();
                    if (from.equals(to)) {
                        toast(t("toast.move.same"), t("toast.move.same.desc"));
                        return;
                    }
                    if (MOVING.getAndSet(true)) return;
                    ProgressToast progress = new ProgressToast(t("toast.move.start").getString());
                    progress.set(t("toast.move.counting").getString(), -1);
                    mc.gui.toastManager().addToast(progress);
                    Thread worker = new Thread(() -> {
                        try {
                            long[] total = OrbisMod.countData(from);
                            System.out.println(String.format(java.util.Locale.ROOT, "[orbis] Moving %,d files (%.1f GB) from %s to %s", total[0], total[1] / 1e9, from, to));
                            OrbisMod.MoveResult r = OrbisMod.moveData(from, to, (files, bytes) -> progress.set(
                                    Component.translatable("orbisterrarum.toast.move.progress", String.format(java.util.Locale.ROOT, "%,d", files),
                                            String.format(java.util.Locale.ROOT, "%,d", total[0]), String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e9),
                                            String.format(java.util.Locale.ROOT, "%.1f", total[1] / 1e9)).getString(),
                                    total[0] == 0 ? 1f : (float) files / total[0]));
                            String folderSetting = to.equals(OrbisMod.configDir().toAbsolutePath().normalize()) ? "" : to.toString();
                            OrbisMod.updateConfig(c2 -> c2.dataFolder = folderSetting);
                            System.out.println(String.format(java.util.Locale.ROOT, "[orbis] Moved %,d files (%.1f GB) to %s; %,d were there already, %,d failed",
                                    r.files(), r.bytes() / 1e9, to, r.skipped(), r.failed()));
                            progress.finish(t("toast.move.done").getString(), Component.translatable("orbisterrarum.toast.move.done.desc",
                                    String.format(java.util.Locale.ROOT, "%,d", r.files()), String.format(java.util.Locale.ROOT, "%.1f", r.bytes() / 1e9),
                                    String.format(java.util.Locale.ROOT, "%,d", r.failed())).getString());
                            mc.execute(() -> {
                                c.dataFolder = folderSetting;
                                folder.requestSet(to.toString());
                            });
                        } catch (Exception e) {
                            System.err.println("[orbis] Moving the downloads failed: " + e);
                            progress.finish(t("toast.move.failed").getString(), String.valueOf(e.getMessage()));
                        } finally {
                            MOVING.set(false);
                        }
                    }, "Orbis-move-data");
                    worker.setDaemon(false); // finishes even if the settings screen is closed
                    worker.start();
                })
                .build();
        ButtonOption reset = ButtonOption.createBuilder()
                .name(t("opt.defaultFolder")).text(t("opt.defaultFolder.button")).description(d("opt.defaultFolder"))
                .action((screen, opt) -> folder.requestSet(defaultPath))
                .build();
        return OptionGroup.createBuilder()
                .name(t("group.storage"))
                .collapsed(true)
                .description(OptionDescription.of(t("group.storage.desc")))
                .option(folder)
                .option(choose)
                .option(move)
                .option(open)
                .option(reset)
                .build();
    }

    private static ConfigCategory performanceCategory(OrbisConfig c) {
        return ConfigCategory.createBuilder()
                .name(t("cat.performance"))
                .tooltip(t("cat.performance.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.streaming"))
                        .option(bool("waitForOsm", true, () -> c.waitForOsm, v -> c.waitForOsm = v))
                        .option(bool("prefetchSpawnAtStartup", true, () -> c.prefetchSpawnAtStartup, v -> c.prefetchSpawnAtStartup = v))
                        .option(bool("fastChunkWrites", true, () -> c.fastChunkWrites, v -> c.fastChunkWrites = v))
                        .option(bool("fastPregen", true, () -> c.fastPregen, v -> c.fastPregen = v))
                        .option(intSlider("fastPregenThreads", 0, 0, Runtime.getRuntime().availableProcessors(), 1, () -> c.fastPregenThreads, v -> c.fastPregenThreads = v,
                                v -> v == 0 ? Component.translatable("orbisterrarum.opt.fastPregenThreads.auto", Math.max(2, Runtime.getRuntime().availableProcessors() / 2)) : Component.literal(String.valueOf(v))))
                        .option(bool("parallelChunkCompression", true, () -> c.parallelChunkCompression, v -> c.parallelChunkCompression = v))
                        .option(bool("pregenPauseWorld", true, () -> c.pregenPauseWorld, v -> c.pregenPauseWorld = v))
                        .option(intSlider("regionPrefetchRadius", 2, 0, 6, 1, () -> c.regionPrefetchRadius, v -> c.regionPrefetchRadius = v, v -> Component.literal(v + " (" + (2 * v + 1) * 512 + " m)")))
                        .option(intSlider("regionCacheSize", 64, 8, 256, 8, () -> c.regionCacheSize, v -> c.regionCacheSize = v, v -> Component.literal(v + " (~" + (v * 6) + " MB)")))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(t("group.farView"))
                        .option(bool("farViewFromData", true, () -> c.farViewFromData, v -> c.farViewFromData = v))
                        .option(bool("voxyFarView", false, () -> c.voxyFarView, v -> c.voxyFarView = v))
                        .option(intSlider("voxyFarViewChunks", 512, 64, 1024, 64, () -> c.voxyFarViewChunks, v -> c.voxyFarViewChunks = v,
                                v -> Component.literal(v + " chunks")))
                        .build())
                .build();
    }

    private static ConfigCategory networkCategory(OrbisConfig c) {
        return ConfigCategory.createBuilder()
                .name(t("cat.network"))
                .tooltip(t("cat.network.desc"))
                .group(OptionGroup.createBuilder()
                        .name(t("group.network"))
                        .option(intSlider("overpassConcurrentRequests", 4, 1, 8, 1, () -> c.overpassConcurrentRequests, v -> c.overpassConcurrentRequests = v, v -> Component.literal(String.valueOf(v))))
                        .option(intSlider("osmMaxWaitMinutes", 15, 1, 60, 1, () -> c.osmMaxWaitMinutes, v -> c.osmMaxWaitMinutes = v, v -> Component.literal(v + " min")))
                        .option(intSlider("demTileCacheSize", 384, 32, 2048, 32, () -> c.demTileCacheSize, v -> c.demTileCacheSize = v, v -> Component.literal(String.valueOf(v))))
                        .option(intSlider("imageryTileCacheSize", 512, 64, 4096, 64, () -> c.imageryTileCacheSize, v -> c.imageryTileCacheSize = v, v -> Component.literal(String.valueOf(v))))
                        .option(bool("offerMapDownloads", true, () -> c.offerMapDownloads, v -> c.offerMapDownloads = v))
                        .option(bool("keepDownloadedMapFiles", false, () -> c.keepDownloadedMapFiles, v -> c.keepDownloadedMapFiles = v))
                        .option(bool("debugLogging", false, () -> c.debugLogging, v -> c.debugLogging = v))
                        .build())
                .build();
    }

    // ------------------------------------------------------------------ helpers

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

    /** A typed number (PreciseDoubleField: full precision, applied as it is typed). */
    private static Option<Double> dbl(String key, double def, double min, double max, Supplier<Double> get, Consumer<Double> set) {
        return Option.<Double>createBuilder()
                .name(t("opt." + key)).description(d("opt." + key))
                .binding(def, get, set)
                .customController(o -> new PreciseDoubleField(o, min, max))
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
