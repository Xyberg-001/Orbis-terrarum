package com.berg.orbis;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.biome.RealWorldBiomeSource;
import com.berg.orbis.command.TeleportCommands;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;
import com.berg.orbis.dem.BicubicElevationSampler;
import com.berg.orbis.dem.BlendedElevationService;
import com.berg.orbis.dem.DemSource;
import com.berg.orbis.dem.DemTileProvider;
import com.berg.orbis.dem.GlobalDemSource;
import com.berg.orbis.dem.ImageServiceDemSource;
import com.berg.orbis.dem.VerticalMapping;
import com.berg.orbis.imagery.ImageryProvider;
import com.berg.orbis.landmark.LandmarkRegistry;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.FeatureRasterizer;
import com.berg.orbis.osm.OsmDataProvider;
import com.berg.orbis.osm.OsmRegionManager;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Owns the installation config (config/orbisterrarum/orbisterrarum.json)
 * and the active {@link WorldModel}: projection, elevation, OSM streaming,
 * climate, landmarks and painters for one set of {@link WorldSettings}. A
 * world carries its settings inside its generator; when a world with
 * different settings is opened the model is rebuilt for it.
 */
public class OrbisMod implements ModInitializer {

    private static Path configDir;
    private static volatile OrbisConfig config;
    private static volatile WorldModel model;
    private static volatile WorldSettings modelSettings;
    /** True while the server shuts down: nothing may wait for downloads then. */
    private static volatile boolean stopping;

    public static boolean stopping() {
        return stopping;
    }

    /** The world height the active model was built for. */
    private static int modelHeight;
    /** The height of the level actually loaded (from its dimension type), 0 while no level is loaded. */
    private static volatile int levelHeight;
    private static volatile Float cloudHeight;
    private static Thread spawnPrefetch;

    /** Cloud layer Y for the active world (about 800 m above the ground at its origin), or null before a model exists. */
    public static Float cloudHeightOverride() {
        return cloudHeight;
    }

    /** The active world model (never null after initialisation). */
    public static WorldModel model() {
        return model;
    }

    /** The installation config; world settings live in it as the defaults for new worlds. */
    public static OrbisConfig config() {
        return config;
    }

    /** True when a level with this sea level is an Orbis Terrarum world (no vanilla dimension has one near -1700). */
    public static boolean isOrbisSeaLevel(int seaLevel) {
        WorldModel m = model;
        return m != null && seaLevel == m.cfg().seaLevelY;
    }

    public static Path configDir() {
        return configDir;
    }

    public static WorldSettings defaultWorldSettings() {
        return WorldSettings.fromConfig(config);
    }

    /** The model for these settings, rebuilding (and retiring the previous one) if they differ from the active model's. */
    public static synchronized WorldModel modelFor(WorldSettings settings) {
        if (settings == null) settings = defaultWorldSettings();
        WorldModel current = model;
        int height = effectiveHeight(settings);
        if (current != null && settings.equals(modelSettings) && height == modelHeight) return current;
        System.out.println("[orbis] Building world model: " + settings.describe() + " (world height " + height + ")");
        OrbisConfig effective = settings.effective(config);
        effective.worldHeight = height;
        WorldModel built = buildModel(effective, configDir);
        model = built;
        modelSettings = settings.copy();
        modelHeight = height;
        cloudHeight = (float) built.cloudHeightY();
        System.out.println("[orbis] Cloud layer at Y " + cloudHeight);
        if (current != null) current.shutdown();
        if (built.regions() != null && config.prefetchSpawnAtStartup) {
            // Start downloading the spawn regions NOW, while the player is
            // still in the menu: vanilla's spawn search generates chunks
            // synchronously on the server thread and would otherwise look
            // frozen at "Create New World" until the downloads finish.
            spawnPrefetch = new Thread(built.regions()::prefetchSpawnArea, "Orbis-spawn-prefetch");
            spawnPrefetch.setDaemon(true);
            spawnPrefetch.start();
        }
        return built;
    }

    /**
     * The height the model for these settings must use: the loaded level's own (a world's height is fixed by
     * its dimension type), else the settings' value, else the full dimension.
     */
    private static int effectiveHeight(WorldSettings settings) {
        int loaded = levelHeight;
        if (loaded > 0) return loaded;
        return settings.worldHeight > 0 ? com.berg.orbis.worldgen.WorldHeight.snap(settings.worldHeight) : OrbisConfig.DIMENSION_HEIGHT;
    }

    /** Stores world settings as the defaults for new worlds and switches the active model to them. */
    public static synchronized void saveWorldDefaults(WorldSettings settings) {
        settings.applyTo(config);
        config.sanitizeValues();
        saveConfigFile();
        modelFor(settings);
    }

    /** Replaces the installation config (from the settings screen), saves it and rebuilds the model. */
    public static synchronized void replaceConfig(OrbisConfig newConfig) {
        newConfig.sanitizeValues();
        config = newConfig;
        saveConfigFile();
        modelSettings = null; // network / data-source settings changed: force a rebuild
        modelFor(WorldSettings.fromConfig(config));
    }

    /** The mod used to be called TellusPlus: carry its config folder (and the valuable caches inside) over once. */
    private static void migrateFromTellusPlus(java.nio.file.Path configRoot) {
        java.nio.file.Path old = configRoot.resolve("tellusplus");
        try {
            if (java.nio.file.Files.isDirectory(old) && !java.nio.file.Files.exists(configDir)) {
                java.nio.file.Files.move(old, configDir);
                java.nio.file.Path oldJson = configDir.resolve("tellusplus.json");
                if (java.nio.file.Files.exists(oldJson)) java.nio.file.Files.move(oldJson, configDir.resolve("orbisterrarum.json"));
                System.out.println("[orbis] Migrated config/tellusplus to config/orbisterrarum (caches kept)");
            }
        } catch (IOException e) {
            System.err.println("[orbis] Could not migrate the old TellusPlus config folder: " + e);
        }
    }

    private static void saveConfigFile() {
        try {
            config.save(configDir.resolve("orbisterrarum.json"));
        } catch (IOException e) {
            System.err.println("[orbis] Could not save config: " + e);
        }
    }

    @Override
    public void onInitialize() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve("orbisterrarum");
        migrateFromTellusPlus(FabricLoader.getInstance().getConfigDir());
        config = OrbisConfig.load(configDir.resolve("orbisterrarum.json"));

        RealWorldChunkGenerator.register();
        // The world map's data: where this world sits on Earth, for players who have the mod (vanilla players
        // never get it, so they still join with plain Minecraft).
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
                .register(com.berg.orbis.net.WorldInfoPayload.TYPE, com.berg.orbis.net.WorldInfoPayload.CODEC);
        com.berg.orbis.map.BlockMapService.register();
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            WorldModel m = model;
            if (m == null || !(server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
            if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(handler.getPlayer(), com.berg.orbis.net.WorldInfoPayload.TYPE)) return;
            OrbisConfig c = m.cfg();
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(handler.getPlayer(),
                    new com.berg.orbis.net.WorldInfoPayload(c.originLat, c.originLon, c.metersPerBlock, c.projection));
        });
        RealWorldBiomeSource.register();
        TeleportCommands.register();

        modelFor(defaultWorldSettings());
        if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
            // A dedicated server creates its world itself: give it the height pack before it does.
            com.berg.orbis.worldgen.WorldHeight.prepareDedicatedServer(FabricLoader.getInstance().getGameDir(), config, model);
        }

        // One advancement per real landmark, as a world datapack written on the first start.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                com.berg.orbis.worldgen.Landmarks.ensure(server);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Landmark datapack: " + e);
            }
            try {
                com.berg.orbis.worldgen.WorldSpawn.applyOnce(server);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Spawn point: " + e);
            }
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            net.minecraft.world.level.dimension.LevelStem stem = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
                    .getValue(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
            if (stem == null || !(stem.generator() instanceof RealWorldChunkGenerator)) {
                System.out.println("[orbis] Not an Orbis Terrarum world; the mod stays out of it");
                return;
            }
            // The level's dimension type is the truth about its height; the model must agree with it.
            int actual = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                    .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value().height();
            levelHeight = actual;
            WorldModel m = model;
            if (m != null && m.cfg().worldHeight != actual) {
                System.out.println("[orbis] Level height is " + actual + " (model had " + m.cfg().worldHeight + "); rebuilding the model");
                m = modelFor(modelSettings);
            }
            System.out.println("[orbis] World Y " + OrbisConfig.DIMENSION_MIN_Y + ".." + (OrbisConfig.DIMENSION_MIN_Y + actual - 1) + " (" + actual / 16 + " sections)");
            if (m != null && m.regions() != null) {
                System.out.println("[orbis] Server starting; " + m.regions().stats());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> levelHeight = 0);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> stopping = false);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopping = true);
        // Every 2 s, keep the regions around and ahead of each player
        // downloading so exploration never waits on Overpass.
        int[] tick = {0};
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            com.berg.orbis.worldgen.Landmarks.tick(server);
            if (++tick[0] % 40 != 0) return;
            WorldModel m = model;
            if (m == null || m.regions() == null) return;
            List<int[]> positions = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level().dimension() != Level.OVERWORLD) continue;
                positions.add(new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getZ())});
            }
            if (!positions.isEmpty()) m.regions().updatePlayers(positions);
        });
        ServerTickEvents.END_SERVER_TICK.register(com.berg.orbis.worldgen.PregenTask::tick);

        System.out.println("[orbis] Origin " + config.originLat + ", " + config.originLon + " at 1 block = " + config.metersPerBlock
                + " m; sea level Y=" + config.seaLevelY + "; world Y " + config.minY + ".." + config.maxY());
    }

    // ------------------------------------------------------------------ model construction

    private static WorldModel buildModel(OrbisConfig cfg, Path cfgDir) {
        // ---- elevation ----
        DemTileProvider tiles = new DemTileProvider(cfgDir.resolve("dem-cache"), cfg.demTileUrl, cfg.demTileCacheSize);
        System.out.println("[orbis] Terrain tiles (zoom " + cfg.demZoom + "): " + tiles.describeSource());
        BicubicElevationSampler globalSampler = new BicubicElevationSampler(tiles, cfg.demZoom);
        List<DemSource> sources = new ArrayList<>();
        if (cfg.useHighResElevation) {
            for (OrbisConfig.ElevationSource es : cfg.elevationSources) {
                if (es != null && es.enabled && es.urlTemplate != null && !es.urlTemplate.isBlank()) {
                    sources.add(new ImageServiceDemSource(es, cfgDir.resolve("dem-hires-cache")));
                }
            }
        }
        sources.add(new GlobalDemSource(globalSampler));
        BlendedElevationService elevation = new BlendedElevationService(sources);
        if (cfg.useBathymetry && cfg.generateWater) {
            DemTileProvider seaTiles = new DemTileProvider(cfgDir.resolve("dem-cache"), cfg.bathymetryTileUrl, 128);
            // Seascape has regional detail to zoom 14 and global coverage below; missing zooms fall back to the parent tile.
            elevation.setBathymetry(new GlobalDemSource(new BicubicElevationSampler(seaTiles, Math.min(cfg.demZoom, 14))), cfg.maxSeaDepthMeters);
            System.out.println("[orbis] Sea floor: " + seaTiles.describeSource() + " (floor drawn down to " + (int) cfg.maxSeaDepthMeters + " m)");
        }

        // Surface model (tops of buildings/trees) -- only where a source covers the point, no global fallback.
        List<DemSource> surfaceSources = new ArrayList<>();
        if (cfg.useHighResElevation && cfg.buildingHeightsFromSurfaceModel) {
            for (OrbisConfig.ElevationSource es : cfg.surfaceModelSources) {
                if (es != null && es.enabled && es.urlTemplate != null && !es.urlTemplate.isBlank()) {
                    surfaceSources.add(new ImageServiceDemSource(es, cfgDir.resolve("dsm-cache")));
                }
            }
        }

        // ---- aerial imagery ----
        ImageryProvider imagery = cfg.useAerialImagery ? new ImageryProvider(cfg, cfgDir.resolve("imagery-cache")) : null;
        if (imagery != null && imagery.isEnabled()) {
            System.out.println("[orbis] Aerial imagery enabled (zoom " + imagery.zoom() + "). " + imagery.attribution());
        }

        // ---- projection / climate / landmarks ----
        CoordinateMapper mapper = new CoordinateMapper(cfg.originLat, cfg.originLon, cfg.metersPerBlock,
                CoordinateMapper.Projection.of(cfg.projection));
        VerticalMapping vertical = new VerticalMapping(cfg, mapper, tiles);
        System.out.println("[orbis] Vertical mapping: " + vertical.mode() + " (1:1 below " + cfg.reliefKneeMeters
                + " m, sea level Y=" + cfg.seaLevelY + ", ceiling Y=" + cfg.maxY() + ")");
        BiomeClassifier classifier = new BiomeClassifier(cfg);
        LandmarkRegistry landmarks = LandmarkRegistry.load(cfgDir.resolve("landmarks.json"), cfgDir.resolve("schematics"), mapper);

        // ---- OSM streaming ----
        OsmRegionManager regions = null;
        boolean anyOsm = cfg.generateBuildings || cfg.generateRoads || cfg.generateWater || cfg.generateLandCover
                || cfg.generateTrees || cfg.generateStreetFurniture;
        if (anyOsm) {
            OsmDataProvider osm = new OsmDataProvider(cfgDir.resolve("osm-cache"), cfg.overpassUrls, cfg.overpassTimeoutSeconds,
                    cfg.overpassConcurrentRequests, cfg.overpassRetries);
            // Imported country extracts (config/orbisterrarum/extracts/<name>/) are used before Overpass.
            osm.setLocalExtracts(com.berg.orbis.osm.extract.LocalExtractStore.get(cfgDir.resolve("extracts")), cfg.metersPerBlock);
            // Points of interest imported from Overture Places (config/orbisterrarum/places/) join OSM's.
            if (cfg.externalPlaces) osm.setPlaces(com.berg.orbis.osm.PlacesStore.get(cfgDir.resolve("places")));
            // Both lambdas answer in block Y through the shared vertical mapping,
            // so bridge decks, water surfaces and building bases sit on the terrain.
            FeatureRasterizer.Elevation elevationInBlocks = (bx, bz) -> {
                try {
                    double[] ll = mapper.toLatLon(bx, bz);
                    double e = elevation.sampleMeters(ll[0], ll[1]);
                    return Double.isNaN(e) ? Double.NaN : vertical.toY(e, bx, bz);
                } catch (RuntimeException ex) {
                    return Double.NaN;
                }
            };
            FeatureRasterizer.Elevation surfaceInBlocks = surfaceSources.isEmpty() ? null : (bx, bz) -> {
                try {
                    double[] ll = mapper.toLatLon(bx, bz);
                    for (DemSource s : surfaceSources) {
                        if (!s.hasCoverage(ll[0], ll[1])) continue;
                        double v = s.sampleMeters(ll[0], ll[1]);
                        if (!Double.isNaN(v)) return vertical.toY(v, bx, bz);
                    }
                    return Double.NaN;
                } catch (RuntimeException ex) {
                    return Double.NaN;
                }
            };
            FeatureRasterizer rasterizer = new FeatureRasterizer(mapper, cfg, elevationInBlocks, surfaceInBlocks, imagery, landmarks);
            // Tunnel ends near a region edge: the ways around that node, from the same source as the region data.
            rasterizer.setWayLookup((lat, lon, radiusM) -> {
                double dLat = radiusM / 111_320.0, dLon = radiusM / (111_320.0 * Math.cos(Math.toRadians(lat)));
                try {
                    return osm.getData(lat - dLat, lon - dLon, lat + dLat, lon + dLon);
                } catch (Exception e) {
                    return null;
                }
            });
            if (cfg.generateBuildings && cfg.atlasBuildingHeights) {
                com.berg.orbis.osm.HeightStore hs = com.berg.orbis.osm.HeightStore.get(cfgDir.resolve("heights"));
                rasterizer.setHeights(hs);
                if (!hs.isEmpty()) System.out.println("[orbis] Building heights found in heights/: GlobalBuildingAtlas (TUM, CC BY-NC 4.0) fills in untagged buildings");
            }
            if (cfg.generateLandCover && cfg.worldCoverLandCover) {
                rasterizer.setWorldCover(new com.berg.orbis.landcover.WorldCoverProvider(cfgDir.resolve("worldcover-cache"), cfg.worldCoverUrl));
                System.out.println("[orbis] Land cover gaps are filled from " + com.berg.orbis.landcover.WorldCoverProvider.ATTRIBUTION);
            }
            // Tree canopy height in blocks: lidar surface minus terrain where a surface model covers, else the
            // imported global canopy map, else unknown (random trees as before).
            if (cfg.treesFromCanopy && cfg.generateTrees) {
                com.berg.orbis.dem.CanopyProvider canopyMap = com.berg.orbis.dem.CanopyProvider.open(cfgDir.resolve("canopy-cache"));
                if (canopyMap != null) System.out.println("[orbis] Canopy height tiles found in canopy-cache: trees follow the canopy map");
                if (!surfaceSources.isEmpty() || canopyMap != null) {
                    rasterizer.setCanopy((bx, bz) -> {
                        try {
                            double[] ll = mapper.toLatLon(bx, bz);
                            for (DemSource s : surfaceSources) {
                                if (!s.hasCoverage(ll[0], ll[1])) continue;
                                double dsm = s.sampleMeters(ll[0], ll[1]);
                                if (Double.isNaN(dsm)) continue;
                                double dtm = elevation.sampleMeters(ll[0], ll[1]);
                                if (!Double.isNaN(dtm)) return Math.max(0, dsm - dtm) / cfg.metersPerBlock;
                            }
                            if (canopyMap != null) {
                                double c = canopyMap.sampleMeters(ll[0], ll[1]);
                                if (!Double.isNaN(c)) return c / cfg.metersPerBlock;
                            }
                            return Double.NaN;
                        } catch (RuntimeException ex) {
                            return Double.NaN;
                        }
                    });
                }
            }
            // Surveyed road widths and lanes from the Norwegian road database, where OSM has none.
            if (cfg.nvdbRoadWidths) rasterizer.setNvdb(new com.berg.orbis.osm.NvdbRoads.Store(cfgDir.resolve("nvdb-cache")));
            regions = new OsmRegionManager(cfg, mapper, osm, rasterizer);
        }

        WorldModel model = new WorldModel(cfg, mapper, elevation, vertical, classifier, regions, landmarks);
        model.setCoarseSampler(new BicubicElevationSampler(tiles, Math.min(cfg.demZoom, 11)));
        model.setTerrainTiles(tiles, cfg.demZoom);
        if (regions != null) regions.setTerrainFailures(tiles::failures);
        return model;
    }
}
