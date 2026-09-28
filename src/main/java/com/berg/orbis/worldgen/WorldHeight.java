package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The height of a world, fitted to the place it is made for.
 *
 * <p>Every chunk costs vanilla work per 16-block section whatever the section holds: sky light runs down
 * through all of them, heightmaps span them, saving and loading serialise them, and each one takes memory
 * while loaded. The full dimension (Y -2032..2031, 254 sections) exists so that the whole Earth fits at 1:1;
 * around a city 1 000 m high, 200 of those sections are empty air that is paid for on every chunk. A world
 * made for one region therefore gets a ceiling fitted to the terrain around its origin, with room above for
 * flight, clouds and tall buildings. The floor and the sea level stay where they are (the sea floor and the
 * underground need them), so the vertical mapping is unchanged inside the range.
 *
 * <p>The height reaches vanilla through the dimension type, which is data: the mod's own
 * {@code data/minecraft/dimension_type/overworld.json} sets the full height, and a small data pack written
 * into the world's {@code datapacks} folder ({@link #PACK_NAME}) overrides it with the fitted one. World data
 * packs load before the world's registries, are sent to every client at login (vanilla clients included) and
 * travel with the world folder, so the world stays self-describing. A world's height cannot change after
 * creation; the settings' value is resolved once, when the world is created, and stored with it.
 */
public final class WorldHeight {
    public static final String PACK_NAME = "orbis_world";
    /** Smallest world a fit can produce: sea level -1700 with 300 m of mountains and the margin. */
    public static final int MIN_HEIGHT = 1024;
    /** Blocks kept free above the highest terrain: flight, the cloud layer, tall buildings, radio masts. */
    public static final int MARGIN_ABOVE = 512;
    /** Radius (metres) around the origin whose terrain the ceiling must clear. */
    public static final double FIT_RADIUS_M = 30_000;

    private WorldHeight() {}

    /** Snaps a requested height to what the engine accepts: a multiple of 16 within the allowed range. */
    public static int snap(int height) {
        int h = (height + 15) / 16 * 16;
        return Math.max(MIN_HEIGHT, Math.min(OrbisConfig.DIMENSION_HEIGHT, h));
    }

    /**
     * The height the world for these settings needs: the explicit value when set, otherwise a fit to the
     * terrain around the origin (full height when the terrain cannot be sampled).
     */
    public static int resolve(int requested, WorldModel model) {
        if (requested > 0) return snap(requested);
        return fit(model);
    }

    /** Ceiling fitted to the terrain within {@link #FIT_RADIUS_M} of the origin, as a dimension height. */
    public static int fit(WorldModel model) {
        if (model == null) return OrbisConfig.DIMENSION_HEIGHT;
        OrbisConfig cfg = model.cfg();
        int radius = (int) Math.round(FIT_RADIUS_M / cfg.metersPerBlock);
        int step = Math.max(16, radius / 60);
        int maxY = Integer.MIN_VALUE, samples = 0;
        for (int z = -radius; z <= radius; z += step) {
            for (int x = -radius; x <= radius; x += step) {
                if ((long) x * x + (long) z * z > (long) radius * radius) continue;
                double e;
                try {
                    e = model.coarseElevation(x, z);
                } catch (RuntimeException ex) {
                    continue;
                }
                if (Double.isNaN(e)) continue;
                samples++;
                maxY = Math.max(maxY, model.blockY(e, x, z));
            }
        }
        if (cfg.customSpawn) {
            // A spawn point away from the origin: its surroundings (10 km) must fit under the ceiling too.
            int[] c = model.mapper().toBlock(cfg.spawnLat, cfg.spawnLon);
            int r = (int) Math.round(10_000 / cfg.metersPerBlock), st = Math.max(16, r / 30);
            for (int z = -r; z <= r; z += st) {
                for (int x = -r; x <= r; x += st) {
                    if ((long) x * x + (long) z * z > (long) r * r) continue;
                    try {
                        double e = model.coarseElevation(c[0] + x, c[1] + z);
                        if (!Double.isNaN(e)) maxY = Math.max(maxY, model.blockY(e, c[0] + x, c[1] + z));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        if (samples < 16) {
            System.out.println("[orbis] World height: terrain around the origin not available (" + samples + " samples); keeping the full height");
            return OrbisConfig.DIMENSION_HEIGHT;
        }
        int height = snap(maxY + MARGIN_ABOVE - cfg.minY + 1);
        System.out.printf(Locale.ROOT, "[orbis] World height fitted: highest terrain within %.0f km is Y %d (%d samples) -> world Y %d..%d (%d sections instead of %d)%n",
                FIT_RADIUS_M / 1000, maxY, samples, cfg.minY, cfg.minY + height - 1, height / 16, OrbisConfig.DIMENSION_HEIGHT / 16);
        return height;
    }

    /** Writes (or rewrites) the world data pack that sets the overworld's height. */
    public static void writePack(Path datapacksDir, int height) throws IOException {
        Path dir = datapacksDir.resolve(PACK_NAME);
        Path typeDir = dir.resolve("data").resolve("minecraft").resolve("dimension_type");
        Files.createDirectories(typeDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Orbis Terrarum: world height " + height + " (Y " + OrbisConfig.DIMENSION_MIN_Y + ".." + (OrbisConfig.DIMENSION_MIN_Y + height - 1) + ")");
        JsonArray min = new JsonArray();
        min.add(107);
        min.add(0);
        pack.add("min_format", min);
        pack.addProperty("max_format", 107);
        meta.add("pack", pack);
        Files.writeString(dir.resolve("pack.mcmeta"), gson.toJson(meta), StandardCharsets.UTF_8);
        JsonObject type;
        try (InputStream in = OrbisMod.class.getResourceAsStream("/assets/orbisterrarum/dimension_type_template.json")) {
            if (in == null) throw new IOException("dimension type template missing from the mod jar");
            type = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        type.addProperty("min_y", OrbisConfig.DIMENSION_MIN_Y);
        type.addProperty("height", height);
        type.addProperty("logical_height", height);
        Files.writeString(typeDir.resolve("overworld.json"), gson.toJson(type), StandardCharsets.UTF_8);
    }

    /** The height a world's pack declares, or 0 when the world has none (full height). */
    public static int packHeight(Path datapacksDir) {
        Path file = datapacksDir.resolve(PACK_NAME).resolve("data").resolve("minecraft").resolve("dimension_type").resolve("overworld.json");
        try {
            if (!Files.exists(file)) return 0;
            JsonObject type = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return type.get("height").getAsInt();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Dedicated server: the world folder is created by the server itself, so the pack must be in place
     * before it first loads. Called from mod initialisation, which runs before the server reads its world.
     */
    /** Whether server.properties asks for the Orbis world type for a new world. */
    public static boolean isOrbisLevelType(Path props) {
        try {
            if (!Files.exists(props)) return false;
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newInputStream(props)) {
                p.load(in);
            }
            String type = p.getProperty("level-type", "").trim().toLowerCase(java.util.Locale.ROOT);
            return type.equals("orbisterrarum:earth");
        } catch (IOException e) {
            return false;
        }
    }

    public static void prepareDedicatedServer(Path gameDir, OrbisConfig cfg, WorldModel model) {
        try {
            String levelName = "world";
            Path props = gameDir.resolve("server.properties");
            if (Files.exists(props)) {
                java.util.Properties p = new java.util.Properties();
                try (var in = Files.newInputStream(props)) {
                    p.load(in);
                }
                levelName = p.getProperty("level-name", "world").trim();
                if (levelName.isEmpty()) levelName = "world";
            }
            Path level = gameDir.resolve(levelName);
            if (Files.exists(level.resolve("level.dat"))) return; // an existing world keeps its height
            // Only a new Orbis world (level-type=orbisterrarum:earth) gets the pack; any other stays vanilla.
            if (!isOrbisLevelType(props)) return;
            int height = resolve(cfg.worldHeight, model);
            writePack(level.resolve("datapacks"), height);
            System.out.println("[orbis] New world '" + levelName + "': height " + height + " written to datapacks/" + PACK_NAME);
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not prepare the world height for the server: " + e);
        }
    }
}
