package com.berg.orbis.sky;

import com.berg.orbis.map.MapColours;
import com.berg.orbis.worldgen.WorldHeight;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The real season in an Orbis world, for players with plain Minecraft too: a data pack in the world
 * ({@link #PACK_NAME}) gives every overworld biome the grass and leaf colours of the month (fresh green in spring,
 * yellow, orange and brown through the autumn, dull in winter) and, in winter, a lower temperature, so rain falls as
 * snow where it is cold enough and the snow melts again in spring. Biome colours reach the game with the world's
 * registries, so the pack is written before a world opens (and on a server before it starts): a new month shows
 * the next time the world is opened. Spruce and birch keep their own fixed colours, as they do in the game.
 * Summer is vanilla: the pack is removed.
 *
 * The month's look follows the world's own climate ({@link ClimateNormals}: each month's mean temperature and
 * precipitation there): winter where the month is near or below freezing (with the cooling that turns rain to snow),
 * a mild winter of bare trees without snow in the coldest months elsewhere, spring and autumn by the month's warmth on
 * the way up or down, and a dry season of straw-coloured grass in a warm month with almost no rain in a place that has
 * a wet one (a Mediterranean summer, a savanna's winter, the months before a monsoon). Until the climate is known
 * (it is asked for once, in the background) the latitude decides: no seasons within 23.5 degrees of the equator,
 * and south of it they are six months apart from the north's.
 */
public final class Seasons {

    public static final String PACK_NAME = "orbis_season";

    /** A month's look: colours the grass and the leaves move towards (by how much), and the winter cooling. */
    private record Look(String name, int grass, double grassBy, int foliage, double foliageBy, double cooling) {}

    private static final int WINTER_GRASS = 0x8C8A5E, WINTER_LEAVES = 0x8A7148;
    /** Bumped whenever {@link #MONTHS} changes. */
    private static final String LOOKS_VERSION = "-4";
    private static final Look[] MONTHS = {
            new Look("winter", WINTER_GRASS, 0.55, WINTER_LEAVES, 0.60, 0.30),              // January
            new Look("winter", WINTER_GRASS, 0.55, WINTER_LEAVES, 0.60, 0.30),              // February
            new Look("early spring", WINTER_GRASS, 0.30, WINTER_LEAVES, 0.35, 0.20),        // March
            new Look("spring", 0x7ED957, 0.25, 0x9ACD32, 0.30, 0.10),                        // April
            new Look("late spring", 0x7ED957, 0.15, 0x8FD14F, 0.20, 0.0),                    // May
            null, null, null,                                                                // June - August: summer
            // Autumn grass goes most of the way to straw (40% looked like a slightly duller green, 5 Oct 2026).
            new Look("early autumn", 0xB5B04E, 0.35, 0xD9A520, 0.30, 0.0),                  // September
            new Look("autumn", 0xB0A04A, 0.65, 0xE0782A, 0.65, 0.0),                        // October
            new Look("late autumn", 0x9A8C55, 0.70, 0x9A6A3A, 0.65, 0.15),                  // November
            new Look("winter", WINTER_GRASS, 0.55, WINTER_LEAVES, 0.60, 0.30),              // December
    };
    private static final Look WINTER = MONTHS[0];
    private static final Look THAWING_WINTER = new Look("winter", WINTER_GRASS, 0.50, WINTER_LEAVES, 0.60, 0.20);
    /** The coldest months above freezing: trees bare, grass a little dull, no snow. */
    private static final Look MILD_WINTER = new Look("mild winter", WINTER_GRASS, 0.25, WINTER_LEAVES, 0.45, 0.0);
    /** A warm month with almost no rain where another month has plenty: straw grass, dusty leaves. */
    private static final Look DRY_SEASON = new Look("dry season", 0xC8B560, 0.55, 0x9A8C55, 0.20, 0.0);
    /** A place with a month this wet (mm) has a wet season at all. */
    private static final double WET_MM = 60;

    private static final Set<String> NOT_OVERWORLD = Set.of("nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest",
            "basalt_deltas", "the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens", "the_void");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Seasons() {
    }

    /** The month's look at a place today, or null for summer and the tropics' wet months (vanilla colours). */
    private static Look lookFor(LocalDate date, double lat, ClimateNormals.Months climate) {
        int month = date.getMonthValue() - 1;
        if (climate != null) return lookFor(climate, month);
        if (Math.abs(lat) < 23.5) return null;
        if (lat < 0) month = (month + 6) % 12;
        return MONTHS[month];
    }

    /** The look of a month (0 January) in a climate; null for vanilla colours. */
    static Look lookFor(ClimateNormals.Months climate, int m) {
        double[] t = climate.temperatureC(), p = climate.precipitationMm();
        double tm = t[m];
        int coldest = 0, warmest = 0, below = 0;
        double wettest = 0;
        for (int i = 0; i < 12; i++) {
            if (t[i] < t[coldest]) coldest = i;
            if (t[i] > t[warmest]) warmest = i;
            if (t[i] < tm) below++;
            wettest = Math.max(wettest, p[i]);
        }
        boolean coldMonth = below < 3; // one of the three coldest
        if (tm < 0) return WINTER;
        if (tm < 3) return THAWING_WINTER;
        // Dry as on a climate chart (Walter and Lieth): less rain in mm than twice the temperature in C.
        if (tm >= 10 && p[m] < 2 * tm && wettest >= WET_MM) return DRY_SEASON;
        if (coldMonth && tm < 12) return MILD_WINTER;
        if (tm >= 16 || coldMonth) return null;
        // From 3 C a month's precipitation is mostly rain: its colours without the table's cooling, which made taiga snow
        // in a Moscow October at 8 C (the latitude table cools early spring and late autumn by 0.15-0.2).
        boolean rising = Math.floorMod(m - coldest, 12) <= Math.floorMod(warmest - coldest, 12);
        Look look = rising ? (tm < 7 ? MONTHS[2] : tm < 12 ? MONTHS[3] : MONTHS[4]) : (tm < 7 ? MONTHS[10] : tm < 12 ? MONTHS[9] : MONTHS[8]);
        return look.cooling() == 0 ? look : new Look(look.name(), look.grass(), look.grassBy(), look.foliage(), look.foliageBy(), 0.0);
    }

    /** The name of a look (for probes and the log): "summer" or "no seasons" for vanilla colours. */
    static String name(ClimateNormals.Months climate, int m) {
        Look look = lookFor(climate, m);
        if (look != null) return look.name() + (look.cooling() > 0 ? " (cooler " + look.cooling() + ")" : "");
        return min(climate.temperatureC()) >= 16 ? "no seasons" : "summer";
    }

    /** The season's name for a world at this place today ("summer" when the colours are vanilla). */
    public static String seasonName(Path dataDir, double lat, double lon) {
        ClimateNormals.Months climate = ClimateNormals.cached(dataDir, lat, lon);
        Look look = lookFor(LocalDate.now(ZoneOffset.UTC), lat, climate);
        if (look != null) return look.name();
        boolean tropical = climate != null ? min(climate.temperatureC()) >= 16 : Math.abs(lat) < 23.5;
        return tropical ? "no seasons" : "summer";
    }

    private static double min(double[] v) {
        double m = Double.MAX_VALUE;
        for (double d : v) m = Math.min(m, d);
        return m;
    }

    /**
     * Brings a world's season pack up to date (writes, rewrites or removes it) for the world's place. Returns true
     * when the pack changed, which Minecraft picks up the next time the world is loaded. The place's climate by month
     * is asked for in the background when it is not kept yet; until then the latitude decides.
     */
    public static boolean update(Path datapacksDir, Path dataDir, double lat, double lon, boolean enabled) {
        Path dir = datapacksDir.resolve(PACK_NAME);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ClimateNormals.Months climate = ClimateNormals.cached(dataDir, lat, lon);
        if (climate == null && enabled) ClimateNormals.prepare(dataDir, lat, lon);
        Look look = enabled ? lookFor(today, lat, climate) : null;
        try {
            if (look == null) {
                if (!Files.exists(dir)) return false;
                deleteTree(dir);
                return true;
            }
            // The looks' version is part of the key, so a change to them reaches packs written before it.
            String key = today.getMonthValue() + (climate != null ? "C" : lat < 0 ? "S" : "N") + "-" + look.name().replace(' ', '_')
                    + "-" + Math.round(look.cooling() * 100) + LOOKS_VERSION;
            if (WorldHeight.isCurrent(dir) && key.equals(packKey(dir))) return false;
            write(dir, look, key);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not write the season pack: " + e);
            return false;
        }
    }

    private static void write(Path dir, Look look, String key) throws IOException {
        Map<String, int[]> table = MapColours.biomeColours();
        if (table.isEmpty()) return;
        if (Files.exists(dir)) deleteTree(dir);
        Path biomeDir = dir.resolve("data").resolve("minecraft").resolve("worldgen").resolve("biome");
        Files.createDirectories(biomeDir);
        int written = 0;
        for (var e : table.entrySet()) {
            String name = e.getKey().substring(e.getKey().indexOf(':') + 1);
            if (NOT_OVERWORLD.contains(name)) continue;
            JsonObject biome = vanillaBiome(name);
            if (biome == null) continue;
            int[] c = e.getValue();
            JsonObject fx = biome.has("effects") ? biome.getAsJsonObject("effects") : new JsonObject();
            // (swamp and dark forest grass is recoloured by the game itself from the colour map: left to it)
            if (!fx.has("grass_color_modifier")) fx.addProperty("grass_color", hex(blend(c[0], look.grass(), look.grassBy())));
            fx.addProperty("foliage_color", hex(blend(c[1], look.foliage(), look.foliageBy())));
            biome.add("effects", fx);
            if (look.cooling() > 0 && !name.contains("ocean") && biome.has("temperature")) {
                biome.addProperty("temperature", biome.get("temperature").getAsDouble() - look.cooling());
            }
            Files.writeString(biomeDir.resolve(name + ".json"), GSON.toJson(biome), StandardCharsets.UTF_8);
            written++;
        }
        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Orbis Terrarum: the real season (" + look.name() + ")");
        WorldHeight.putPackFormat(pack);
        meta.add("pack", pack);
        WorldHeight.stamp(meta);
        meta.getAsJsonObject("orbisterrarum").addProperty("season", key);
        Files.writeString(dir.resolve("pack.mcmeta"), GSON.toJson(meta), StandardCharsets.UTF_8);
        System.out.println("[orbis] Season pack: " + look.name() + " (" + written + " biomes)");
    }

    /** The biome as this Minecraft version defines it (every field, so the pack's copy is complete), or null. */
    private static JsonObject vanillaBiome(String name) {
        String path = "/data/minecraft/worldgen/biome/" + name + ".json";
        try (InputStream in = net.minecraft.SharedConstants.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String packKey(Path dir) {
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
            return meta.getAsJsonObject("orbisterrarum").get("season").getAsString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final String NO_SEASONS = "orbis-no-seasons";

    /**
     * A world whose settings turn seasons off gets a marker file, so the pack can be left out before it loads next
     * time (its settings are only readable once it has loaded).
     */
    public static void rememberWorldChoice(Path levelDir, boolean seasons) {
        try {
            Path marker = levelDir.resolve(NO_SEASONS);
            if (seasons) Files.deleteIfExists(marker);
            else if (!Files.exists(marker)) Files.writeString(marker, "Real seasons are off in this world's settings.");
        } catch (IOException ignored) {
        }
    }

    /** Whether a world (not yet loaded) has seasons on in its own settings, as far as the mod knows. */
    public static boolean worldWantsSeasons(Path levelDir) {
        return !Files.exists(levelDir.resolve(NO_SEASONS));
    }

    /** The latitude a world was made for, from the spawn file the mod writes into it; NaN when there is none yet. */
    public static double worldLatitude(Path levelDir) {
        return worldPlace(levelDir, "lat");
    }

    /** The longitude a world was made for (as {@link #worldLatitude}). */
    public static double worldLongitude(Path levelDir) {
        return worldPlace(levelDir, "lon");
    }

    private static double worldPlace(Path levelDir, String field) {
        try {
            JsonObject spawn = JsonParser.parseString(Files.readString(levelDir.resolve("orbis-spawn.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            return spawn.get(field).getAsDouble();
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
    }

    private static int blend(int from, int to, double by) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * by);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * by);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * by);
        return r << 16 | g << 8 | b;
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
