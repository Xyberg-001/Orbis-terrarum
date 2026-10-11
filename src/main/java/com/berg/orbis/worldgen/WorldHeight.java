package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    /**
     * The squeeze range of a fitted world (the installation's 900 is for the full height): the room above the
     * highest terrain less the top section, so the squeeze starts above every mountain the fit measured and only
     * terrain higher than that (an area added later) is squeezed. With 900 in a fitted world it started some 400
     * blocks below the highest terrain, and every hill above that came out lower than real (Gullfjellet by about
     * 50 blocks in a 1:2 Bergen world).
     */
    public static final int FITTED_SOFT_CEILING = MARGIN_ABOVE - 32;
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
    /**
     * The highest point found in a selection: its Y before and after the squeeze near the ceiling, and where.
     * {@code squeezedBy} is how many blocks this world lowers it for lack of room (0 when it fits).
     */
    public record Peak(double unsqueezedY, int y, int x, int z, int samples) {
        public int squeezedBy() {
            return (int) Math.max(0, Math.round(unsqueezedY - y));
        }
    }

    /** Samples a selection gets at most: the grid coarsens with its size (a country stays a second's work). */
    private static final int PEAK_SAMPLES = 6000;

    /**
     * The highest point of a selection, from the coarse terrain at the middle of its chunks on a grid of at most
     * {@link #PEAK_SAMPLES} (every chunk of a small selection), or null when it is empty or no terrain answers.
     */
    public static Peak peak(WorldModel model, ChunkSelection selection) {
        if (model == null || selection == null || selection.isEmpty()) return null;
        int step = (int) Math.max(1, Math.ceil(Math.sqrt(selection.count() / (double) PEAK_SAMPLES)));
        double best = Double.NEGATIVE_INFINITY;
        int bestY = 0, bestX = 0, bestZ = 0, samples = 0;
        for (int cz = selection.firstRow(); cz <= selection.lastRow(); cz += step) {
            int[] runs = selection.rawRuns(cz);
            if (runs == null) continue;
            for (int i = 0; i < runs.length; i += 2) {
                for (int cx = runs[i]; cx <= runs[i + 1]; cx += step) {
                    int x = (cx << 4) + 8, z = (cz << 4) + 8;
                    double e;
                    try {
                        e = model.coarseElevation(x, z);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    if (Double.isNaN(e)) continue;
                    samples++;
                    double raw = model.vertical().unsqueezedY(e, x, z);
                    if (raw > best) {
                        best = raw;
                        bestY = model.blockY(e, x, z);
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
        }
        return samples == 0 ? null : new Peak(best, bestY, bestX, bestZ, samples);
    }

    // ------------------------------------------------------------------ high-altitude window

    /**
     * Room under the window's low ground: its bottom squeeze (lower valleys ease down through it, keeping their shape)
     * and the floor's 8, so the low ground itself stays 1:1, with the underground (band, caves, ores) below it.
     */
    public static final int WINDOW_FLOOR_ROOM = 8 + com.berg.orbis.dem.VerticalMapping.WINDOW_FLOOR_SQUEEZE + 8;
    /** The window's squeeze range under the ceiling: small, so its highest mountain stays 1:1. */
    public static final int WINDOW_SOFT_CEILING = 64;

    /** A world's height range laid over its area's own heights: the sea level that puts it there. */
    public record Window(int seaLevelY, double lowMeters, double highMeters) {}

    /**
     * For a world whose area has no sea and lies high (mountains, plateaus): its 4,064 blocks laid over the area's
     * own heights instead of anchored to sea level, all of it 1:1. Anchored to sea level, a world around Everest
     * spends some 1,300 blocks under its lowest valley and has to squeeze the mountain into the rest (the last
     * 900 m from the South Col to the summit came out 139 blocks high). The lowest ground (but the lowest 5%)
     * goes {@link #WINDOW_FLOOR_ROOM} above the floor; if the area is taller than the window, its highest point goes
     * just under the ceiling instead and only the deepest valleys ease towards the floor. Sea level then lies under
     * the floor: the world's heights above sea level stay real (1:1), there is simply no sea in it. Null where the
     * usual mapping is right: any sea or coast in the area, or ground too low for the window to gain much.
     */
    public static Window window(WorldModel model) {
        if (model == null || !model.cfg().highAltitudeWindow || model.cfg().uniformHeights) return null;
        OrbisConfig cfg = model.cfg();
        List<Double> e = areaHeights(model);
        if (e == null) return null;
        double lowest = e.get(0), low = e.get(e.size() / 20), high = e.get(e.size() - 1);
        if (lowest <= 50) return null; // sea or coast: sea level must stay in the world
        int floorY = cfg.minY + WINDOW_FLOOR_ROOM;
        int topY = cfg.minY + OrbisConfig.DIMENSION_HEIGHT - 1 - 16 - WINDOW_SOFT_CEILING;
        int seaForLow = floorY - (int) Math.round(low / cfg.metersPerBlock);
        int seaForHigh = topY - (int) Math.ceil(high / cfg.metersPerBlock);
        int sea = Math.min(seaForLow, seaForHigh);
        if (sea > cfg.seaLevelY - 200 || sea >= cfg.minY) return null; // the usual world has room enough
        System.out.println(String.format(Locale.ROOT, "[orbis] High-altitude window: the area's ground lies %.0f-%.0f m (lowest %.0f m, %d samples):"
                        + " sea level Y %d instead of %d, %.0f m at Y %d, %.0f m at Y %d, all 1:1%s", low, high, lowest, e.size(), sea, cfg.seaLevelY,
                low, sea + (int) Math.round(low / cfg.metersPerBlock), high, sea + (int) Math.round(high / cfg.metersPerBlock),
                seaForHigh < seaForLow ? " (taller than the world: the lowest valleys ease towards the floor)" : ""));
        return new Window(sea, low, high);
    }

    /**
     * The ground heights (metres) the world's creation goes by, sorted: 30 km round the origin, the custom spawn's
     * 10 km, the area selected on the map. Null with too few known.
     */
    static List<Double> areaHeights(WorldModel model) {
        OrbisConfig cfg = model.cfg();
        List<Double> e = new ArrayList<>();
        int radius = (int) Math.round(FIT_RADIUS_M / cfg.metersPerBlock), step = Math.max(16, radius / 60);
        for (int z = -radius; z <= radius; z += step) {
            for (int x = -radius; x <= radius; x += step) {
                if ((long) x * x + (long) z * z > (long) radius * radius) continue;
                sample(model, x, z, e);
            }
        }
        if (cfg.customSpawn) {
            int[] c = model.mapper().toBlock(cfg.spawnLat, cfg.spawnLon);
            int r = (int) Math.round(10_000 / cfg.metersPerBlock), st = Math.max(16, r / 30);
            for (int z = -r; z <= r; z += st) for (int x = -r; x <= r; x += st) if ((long) x * x + (long) z * z <= (long) r * r) sample(model, c[0] + x, c[1] + z, e);
        }
        if (cfg.pregenShapes != null && !cfg.pregenShapes.isEmpty()) {
            ChunkSelection sel = ChunkSelection.ofSettings(cfg.pregenShapes, model.mapper());
            if (sel != null && !sel.isEmpty()) {
                int st = (int) Math.max(1, Math.ceil(Math.sqrt(sel.count() / (double) PEAK_SAMPLES)));
                for (int cz = sel.firstRow(); cz <= sel.lastRow(); cz += st) {
                    int[] runs = sel.rawRuns(cz);
                    if (runs == null) continue;
                    for (int i = 0; i < runs.length; i += 2) for (int cx = runs[i]; cx <= runs[i + 1]; cx += st) sample(model, (cx << 4) + 8, (cz << 4) + 8, e);
                }
            }
        }
        if (e.size() < 16) return null;
        java.util.Collections.sort(e);
        return e;
    }

    // ------------------------------------------------------------------ uniform heights

    /** A uniform-heights world: its one factor (real metres per block of height, over the scale) and the ground it fits. */
    public record Uniform(double squash, double highMeters) {}

    /**
     * Every height of the world divided by one factor, just enough that the area's highest ground stays under the
     * ceiling's short squeeze ({@link #WINDOW_SOFT_CEILING}): 1 (true heights) where it fits already, about 1.3
     * for the Alps, 2.4 for Everest. Taller ground elsewhere is squeezed at the ceiling. {@code requested} is the
     * world's fixed height, or 0 for the full height (then fitted to what the area needs).
     */
    public static Uniform uniform(WorldModel model, int requested) {
        if (model == null || !model.cfg().uniformHeights) return null;
        OrbisConfig cfg = model.cfg();
        List<Double> e = areaHeights(model);
        if (e == null) return null;
        double high = e.get(e.size() - 1);
        int height = requested > 0 ? snap(requested) : OrbisConfig.DIMENSION_HEIGHT;
        int topY = cfg.minY + height - 1 - 16 - WINDOW_SOFT_CEILING;
        double room = Math.max(1, topY - cfg.seaLevelY) * cfg.metersPerBlock;
        double squash = Math.min(16, Math.max(1, high / room));
        System.out.println(String.format(Locale.ROOT, "[orbis] Uniform heights: the area's highest ground is %.0f m (%d samples): 1 block up = %.2f m"
                + " everywhere (%.2f times flatter than the scale), highest ground at Y %d", high, e.size(), cfg.metersPerBlock * squash, squash,
                cfg.seaLevelY + (int) Math.ceil(high / (cfg.metersPerBlock * squash))));
        return new Uniform(squash, high);
    }

    /** The height a uniform-heights world needs: its highest ground plus the usual room above, under the full height. */
    public static int uniformHeight(WorldModel model, Uniform u) {
        OrbisConfig cfg = model.cfg();
        int maxY = cfg.seaLevelY + (int) Math.ceil(u.highMeters() / (cfg.metersPerBlock * u.squash()));
        return snap(maxY + MARGIN_ABOVE - cfg.minY + 1);
    }

    private static void sample(WorldModel model, int x, int z, List<Double> out) {
        try {
            double v = model.coarseElevation(x, z);
            if (!Double.isNaN(v)) out.add(v);
        } catch (RuntimeException ignored) {
        }
    }

    /** The height a window world needs: its highest ground plus the usual room above, under the full height. */
    public static int windowHeight(WorldModel model, Window w) {
        OrbisConfig cfg = model.cfg();
        int maxY = w.seaLevelY() + (int) Math.ceil(w.highMeters() / cfg.metersPerBlock);
        return snap(maxY + MARGIN_ABOVE - cfg.minY + 1);
    }

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
        if (cfg.pregenShapes != null && !cfg.pregenShapes.isEmpty()) {
            // The area chosen on the world generator map: its mountains must fit too, wherever it lies.
            Peak p = peak(model, ChunkSelection.ofSettings(cfg.pregenShapes, model.mapper()));
            if (p != null) {
                maxY = Math.max(maxY, p.y());
                samples += p.samples();
                System.out.println(String.format(Locale.ROOT, "[orbis] World height: the selected area's highest terrain is Y %d (%d samples)", p.y(), p.samples()));
            }
        }
        if (samples < 16) {
            System.out.println("[orbis] World height: terrain around the origin not available (" + samples + " samples); keeping the full height");
            return OrbisConfig.DIMENSION_HEIGHT;
        }
        int height = snap(maxY + MARGIN_ABOVE - cfg.minY + 1);
        System.out.println(String.format(Locale.ROOT, "[orbis] World height fitted: highest terrain within %.0f km is Y %d (%d samples) -> world Y %d..%d (%d sections instead of %d)",
                FIT_RADIUS_M / 1000, maxY, samples, cfg.minY, cfg.minY + height - 1, height / 16, OrbisConfig.DIMENSION_HEIGHT / 16));
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
        putPackFormat(pack);
        meta.add("pack", pack);
        stamp(meta);
        Files.writeString(dir.resolve("pack.mcmeta"), gson.toJson(meta), StandardCharsets.UTF_8);
        JsonObject type = overworldType();
        type.addProperty("min_y", OrbisConfig.DIMENSION_MIN_Y);
        type.addProperty("height", height);
        type.addProperty("logical_height", height);
        Files.writeString(typeDir.resolve("overworld.json"), gson.toJson(type), StandardCharsets.UTF_8);
    }

    /**
     * The data pack format range for the packs the mod writes: from 26.2's format (the oldest Minecraft these files are
     * valid for) up to the running version's, a compile-time constant, so each version's jar declares its own.
     */
    public static void putPackFormat(JsonObject pack) {
        JsonArray min = new JsonArray();
        min.add(107);
        min.add(0);
        pack.add("min_format", min);
        pack.addProperty("max_format", Math.max(107, SharedConstants.DATA_PACK_FORMAT_MAJOR));
    }

    /**
     * Bump when the files the mod writes into its packs change shape, so worlds rewrite them. Together with the data
     * pack format (which changes with every Minecraft version) it tells whether a world's pack was written by this jar.
     */
    private static final int PACK_REVISION = 1;

    /** Marks a pack.mcmeta as written by this build of the mod for this Minecraft version (Minecraft ignores the key). */
    public static void stamp(JsonObject meta) {
        JsonObject orbis = new JsonObject();
        orbis.addProperty("data_format", SharedConstants.DATA_PACK_FORMAT_MAJOR);
        orbis.addProperty("revision", PACK_REVISION);
        meta.add("orbisterrarum", orbis);
    }

    /**
     * Whether a pack the mod wrote earlier matches this Minecraft version and mod build. False for packs written by
     * another Minecraft version (for example a world upgraded from 26.2 to 26.3, whose data formats changed).
     */
    public static boolean isCurrent(Path packDir) {
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(packDir.resolve("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject orbis = meta.getAsJsonObject("orbisterrarum");
            return orbis != null && orbis.get("data_format").getAsInt() == SharedConstants.DATA_PACK_FORMAT_MAJOR
                    && orbis.get("revision").getAsInt() == PACK_REVISION;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The overworld's dimension type: vanilla's own (read from the game jar, so settings a new Minecraft version adds
     * come along), with the Orbis cloud height from the template; the template alone when vanilla's cannot be read.
     */
    private static JsonObject overworldType() throws IOException {
        JsonObject template;
        try (InputStream in = OrbisMod.class.getResourceAsStream("/assets/orbisterrarum/dimension_type_template.json")) {
            if (in == null) throw new IOException("dimension type template missing from the mod jar");
            template = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        try (InputStream in = SharedConstants.class.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")) {
            if (in == null) return template;
            JsonObject vanilla = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            String cloud = "minecraft:visual/cloud_height";
            JsonObject attributes = vanilla.getAsJsonObject("attributes");
            JsonObject ours = template.getAsJsonObject("attributes");
            if (attributes != null && ours != null && ours.has(cloud)) attributes.add(cloud, ours.get(cloud));
            return vanilla;
        } catch (RuntimeException e) {
            System.err.println("[orbis] Vanilla overworld dimension type unreadable (" + e + "); using the mod's template");
            return template;
        }
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
            if (Files.exists(level.resolve("level.dat"))) {
                StructureDensity.refresh(level.resolve("datapacks"));
                // An existing world keeps its height; an Orbis one gets this month's season before it loads.
                if (Files.isDirectory(level.resolve("datapacks").resolve(PACK_NAME))) {
                    double lat = com.berg.orbis.sky.Seasons.worldLatitude(level);
                    com.berg.orbis.sky.Seasons.update(level.resolve("datapacks"), Double.isNaN(lat) ? cfg.originLat : lat,
                            cfg.realSeasons && com.berg.orbis.sky.Seasons.worldWantsSeasons(level));
                }
                return;
            }
            // Only a new Orbis world (level-type=orbisterrarum:earth) gets the pack; any other stays vanilla.
            if (!isOrbisLevelType(props)) return;
            int height = resolve(cfg.worldHeight, model);
            writePack(level.resolve("datapacks"), height);
            StructureDensity.writePack(level.resolve("datapacks"), cfg.undergroundStructureShare);
            System.out.println("[orbis] New world '" + levelName + "': height " + height + " written to datapacks/" + PACK_NAME);
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not prepare the world height for the server: " + e);
        }
    }
}
