package com.berg.orbis.biome;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.worldgen.WorldModel;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import com.berg.orbis.mc.BiomeSourceBridge;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Chooses a vanilla biome per column from the real climate model plus OSM
 * land cover / water. Biomes are only used for what they still control in
 * a real-world map: grass and foliage colour, water tint, snowfall, ambient
 * mob spawning. Terrain shape and trees come from the generator itself.
 *
 * Registered as orbisterrarum:real_world; referenced from the dimension json.
 *
 * Underground (worlds with underground layout 2): vanilla's cave biomes in the underground band. Deep Dark below
 * the band's vanilla Y 0 in patches, mostly under mountains (where vanilla's Ancient Cities are found), Lush Caves
 * mostly under wet and wooded land, Dripstone Caves mostly under dry land and mountains, the surface biome elsewhere.
 */
public class RealWorldBiomeSource extends BiomeSourceBridge {

    public static final MapCodec<RealWorldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    RegistryOps.<Biome, RealWorldBiomeSource>retrieveGetter(Registries.BIOME)
            ).apply(instance, RealWorldBiomeSource::new));

    public enum Pick {
        PLAINS, SUNFLOWER_PLAINS, FOREST, BIRCH_FOREST, DARK_FOREST, TAIGA, SNOWY_TAIGA, OLD_GROWTH_PINE_TAIGA, SNOWY_PLAINS,
        DESERT, SAVANNA, JUNGLE, SPARSE_JUNGLE, SWAMP, MANGROVE_SWAMP, BEACH, SNOWY_BEACH, STONY_SHORE, MEADOW,
        STONY_PEAKS, JAGGED_PEAKS, FROZEN_PEAKS, SNOWY_SLOPES, GROVE, WINDSWEPT_HILLS, OCEAN, DEEP_OCEAN, COLD_OCEAN,
        DEEP_COLD_OCEAN, FROZEN_OCEAN, LUKEWARM_OCEAN, WARM_OCEAN, RIVER, FROZEN_RIVER, CHERRY_GROVE, FLOWER_FOREST, BADLANDS,
        /** The autumn forest of Minecraft 26.3 (orange grass and foliage); a plain forest on versions without it. */
        DAPPLED_FOREST, WINDSWEPT_FOREST, WINDSWEPT_SAVANNA
    }

    private static final Map<Pick, ResourceKey<Biome>> KEYS = new EnumMap<>(Pick.class);

    static {
        KEYS.put(Pick.PLAINS, Biomes.PLAINS);
        KEYS.put(Pick.SUNFLOWER_PLAINS, Biomes.SUNFLOWER_PLAINS);
        KEYS.put(Pick.FOREST, Biomes.FOREST);
        KEYS.put(Pick.BIRCH_FOREST, Biomes.BIRCH_FOREST);
        KEYS.put(Pick.DARK_FOREST, Biomes.DARK_FOREST);
        KEYS.put(Pick.TAIGA, Biomes.TAIGA);
        KEYS.put(Pick.SNOWY_TAIGA, Biomes.SNOWY_TAIGA);
        KEYS.put(Pick.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA);
        KEYS.put(Pick.SNOWY_PLAINS, Biomes.SNOWY_PLAINS);
        KEYS.put(Pick.DESERT, Biomes.DESERT);
        KEYS.put(Pick.SAVANNA, Biomes.SAVANNA);
        KEYS.put(Pick.JUNGLE, Biomes.JUNGLE);
        KEYS.put(Pick.SPARSE_JUNGLE, Biomes.SPARSE_JUNGLE);
        KEYS.put(Pick.SWAMP, Biomes.SWAMP);
        KEYS.put(Pick.MANGROVE_SWAMP, Biomes.MANGROVE_SWAMP);
        KEYS.put(Pick.BEACH, Biomes.BEACH);
        KEYS.put(Pick.SNOWY_BEACH, Biomes.SNOWY_BEACH);
        KEYS.put(Pick.STONY_SHORE, Biomes.STONY_SHORE);
        KEYS.put(Pick.MEADOW, Biomes.MEADOW);
        KEYS.put(Pick.STONY_PEAKS, Biomes.STONY_PEAKS);
        KEYS.put(Pick.JAGGED_PEAKS, Biomes.JAGGED_PEAKS);
        KEYS.put(Pick.FROZEN_PEAKS, Biomes.FROZEN_PEAKS);
        KEYS.put(Pick.SNOWY_SLOPES, Biomes.SNOWY_SLOPES);
        KEYS.put(Pick.GROVE, Biomes.GROVE);
        KEYS.put(Pick.WINDSWEPT_HILLS, Biomes.WINDSWEPT_HILLS);
        KEYS.put(Pick.OCEAN, Biomes.OCEAN);
        KEYS.put(Pick.DEEP_OCEAN, Biomes.DEEP_OCEAN);
        KEYS.put(Pick.COLD_OCEAN, Biomes.COLD_OCEAN);
        KEYS.put(Pick.DEEP_COLD_OCEAN, Biomes.DEEP_COLD_OCEAN);
        KEYS.put(Pick.FROZEN_OCEAN, Biomes.FROZEN_OCEAN);
        KEYS.put(Pick.LUKEWARM_OCEAN, Biomes.LUKEWARM_OCEAN);
        KEYS.put(Pick.WARM_OCEAN, Biomes.WARM_OCEAN);
        KEYS.put(Pick.RIVER, Biomes.RIVER);
        KEYS.put(Pick.FROZEN_RIVER, Biomes.FROZEN_RIVER);
        KEYS.put(Pick.CHERRY_GROVE, Biomes.CHERRY_GROVE);
        KEYS.put(Pick.FLOWER_FOREST, Biomes.FLOWER_FOREST);
        KEYS.put(Pick.BADLANDS, Biomes.BADLANDS);
        KEYS.put(Pick.WINDSWEPT_FOREST, Biomes.WINDSWEPT_FOREST);
        KEYS.put(Pick.WINDSWEPT_SAVANNA, Biomes.WINDSWEPT_SAVANNA);
        KEYS.put(Pick.DAPPLED_FOREST, ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                Identifier.withDefaultNamespace("dappled_forest")));
    }

    private final HolderGetter<Biome> biomes;
    private final Map<Pick, Holder<Biome>> holders = new EnumMap<>(Pick.class);
    private final Holder<Biome> fallback;
    /** The cave biomes (null where a version lacks one). */
    private final Holder<Biome> deepDark, lushCaves, dripstoneCaves;
    /** A column whose band top is not known yet (its chunk's structures and biomes have not been made). */
    private static final int UNKNOWN = Integer.MIN_VALUE;

    /**
     * Set while vanilla chooses structure starts (RealWorldChunkGenerator.createStructures). Vanilla checks a
     * start's biome at its own Y before Orbis moves the structure into the band: an Ancient City asks at Y -27, a
     * trial chamber at Y -40..-20. Inside this frame, a Y in vanilla's underground range (-64..63) that is not near
     * the real surface is taken as a Y in the band's vanilla frame, so the city finds its Deep Dark.
     */
    public static final ThreadLocal<Boolean> VANILLA_FRAME = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /**
     * Column -> biome, bounded by clearing it when full. A synchronized LRU map here was a global lock that every
     * worker thread took several times per chunk section (thread samples of a sweep showed workers queueing on
     * it); a lock-free map that is simply emptied now and then costs a few recomputations instead.
     */
    private final Column[] columnCache = new Column[1 << CACHE_BITS];
    private static final int CACHE_BITS = 16;

    /**
     * One cached column. The cache is a direct-mapped array of these (immutable, so a slot is always read whole
     * without locks); the ConcurrentHashMap it replaces keyed columns by a Long whose hash was qx ^ qz, so every
     * column on a diagonal shared a bucket, the buckets turned into trees, and lookups were 4% of generation.
     */
    private record Column(long key, Holder<Biome> biome, int bandTop, Holder<Biome> cave, boolean deepDark) {
    }

    public RealWorldBiomeSource(HolderGetter<Biome> biomes) {
        this.biomes = biomes;
        for (Map.Entry<Pick, ResourceKey<Biome>> e : KEYS.entrySet()) {
            biomes.get(e.getValue()).ifPresent(h -> holders.put(e.getKey(), h));
        }
        this.fallback = holders.getOrDefault(Pick.PLAINS, biomes.getOrThrow(Biomes.PLAINS));
        this.deepDark = biomes.get(Biomes.DEEP_DARK).orElse(null);
        this.lushCaves = biomes.get(Biomes.LUSH_CAVES).orElse(null);
        this.dripstoneCaves = biomes.get(Biomes.DRIPSTONE_CAVES).orElse(null);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath("orbisterrarum", "real_world"), CODEC);
        // Worlds created while the mod was still called TellusPlus keep loading (distinct wrapper: a registry
        // refuses the same object under two ids).
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath("tellusplus", "real_world"),
                CODEC.xmap(b -> b, b -> b));
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        List<Holder<Biome>> all = new java.util.ArrayList<>(holders.values());
        for (Holder<Biome> h : java.util.Arrays.asList(deepDark, lushCaves, dripstoneCaves)) if (h != null) all.add(h);
        return List.copyOf(all).stream();
    }

    /**
     * The surface biome of a column for the far view, also past the hard limit's border (where {@link #biomeAt}
     * answers plains: the chunks there stay empty, but the far view draws the land, and plains tinted it all alike).
     */
    public Holder<Biome> farBiome(int qx, int qz) {
        long key = (((long) qx) << 32) ^ (qz & 0xffffffffL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - CACHE_BITS));
        Column c = columnCache[slot];
        if (c == null || c.key != key) {
            c = column(qx, qz, key);
            columnCache[slot] = c;
        }
        return c.biome;
    }

    /**
     * The biome of the far view's water past the map data, where only the far view knows it is water: the sea's for
     * the climate at sea level (as the world's own sea gets it), or a river's for a lake.
     */
    public Holder<Biome> farWaterBiome(int x, int z, boolean sea) {
        WorldModel model = OrbisMod.model();
        if (model == null) return fallback;
        BiomeClassifier.Climate climate = model.climate(x, z, 0.0);
        double t = climate.meanTempC();
        Pick p = !sea ? (climate.frozenWater() ? Pick.FROZEN_RIVER : Pick.RIVER)
                : climate.frozenWater() ? Pick.FROZEN_OCEAN : t < 8 ? Pick.COLD_OCEAN : t < 17 ? Pick.OCEAN : t < 22 ? Pick.LUKEWARM_OCEAN : Pick.WARM_OCEAN;
        return holders.getOrDefault(p, fallback);
    }

    @Override
    public Holder<Biome> biomeAt(int qx, int qy, int qz) {
        long key = (((long) qx) << 32) ^ (qz & 0xffffffffL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - CACHE_BITS));
        Column c = columnCache[slot];
        if (c == null || c.key != key) {
            // Outside the hard limit the chunk stays empty: any biome will do, without asking the map.
            if (com.berg.orbis.worldgen.HardLimit.blocks(qx >> 2, qz >> 2)) return fallback;
            c = column(qx, qz, key);
            columnCache[slot] = c;
        }
        if (c.cave == null && !c.deepDark) return c.biome;
        int bandTop = c.bandTop;
        if (bandTop == UNKNOWN) {
            // Known once the chunk's structures or biomes are being made; never computed here (this is also asked
            // from the server thread, which must not wait for map data).
            WorldModel model = OrbisMod.model();
            bandTop = model == null ? UNKNOWN : model.band().peek(qx >> 2, qz >> 2);
            if (bandTop == UNKNOWN) return c.biome;
            columnCache[slot] = c = new Column(key, c.biome, bandTop, c.cave, c.deepDark);
        }
        int y = qy << 2;
        int v;   // the Y in the band's vanilla frame (vanilla's -64..63)
        if (y >= com.berg.orbis.worldgen.UndergroundBand.VANILLA_BOTTOM && y < com.berg.orbis.worldgen.UndergroundBand.VANILLA_SURFACE
                && VANILLA_FRAME.get() && nearVanillaDepth(qx, qz, y)) {
            v = y;
        } else {
            if (y >= bandTop - 8) return c.biome;
            v = y - (bandTop - com.berg.orbis.worldgen.UndergroundBand.VANILLA_SURFACE);
        }
        if (v < com.berg.orbis.worldgen.UndergroundBand.VANILLA_BOTTOM || v >= 56) return c.biome;
        if (c.deepDark && v < 0) return deepDark;
        return c.cave != null ? c.cave : c.biome;
    }

    /** A structure's own Y that is not at the real ground (more than 16 blocks from it): a vanilla underground Y. */
    private static boolean nearVanillaDepth(int qx, int qz, int y) {
        WorldModel model = OrbisMod.model();
        return model != null && Math.abs(y - model.terrainHeight((qx << 2) + 2, (qz << 2) + 2)) > 16;
    }

    /** The surface biome of a column and, in layout 2, the cave biome under it. */
    private Column column(int qx, int qz, long key) {
        int x = (qx << 2) + 2, z = (qz << 2) + 2;
        WorldModel model = OrbisMod.model();
        double[] relief = model == null ? null : relief(model, x, z);
        Pick p = pick(x, z, relief);
        Holder<Biome> h = holders.get(p);
        if (h == null) h = p == Pick.DAPPLED_FOREST ? holders.getOrDefault(Pick.FOREST, fallback) : fallback;
        if (model == null || model.cfg().undergroundVersion < 2) return new Column(key, h, UNKNOWN, null, false);
        // Deep Dark mostly under mountains, as in vanilla (its low-erosion, deep ground): half the underground where
        // the ground rises and falls 420 m or more within half a kilometre, 3% under flat land, in between by relief.
        boolean dd = deepDark != null && patch(x, z, 320, 0x5C7L) > DEEP_DARK_FLAT + (DEEP_DARK_MOUNTAINS - DEEP_DARK_FLAT) * mountains(relief);
        // Lush caves under wet and wooded land, dripstone under dry land and mountains; a few of each elsewhere.
        double lush = patch(x, z, 224, 0x105L) - (wet(p) ? CAVE_COMMON : CAVE_RARE);
        double drip = patch(x, z, 224, 0xD21L) - (dry(p) ? CAVE_COMMON : CAVE_RARE);
        Holder<Biome> cave = null;
        if (lush > 0 && lush >= drip) cave = lushCaves;
        else if (drip > 0) cave = dripstoneCaves;
        return new Column(key, h, UNKNOWN, cave, dd);
    }

    /**
     * Thresholds on {@link #patch} for the share of the underground each cave biome takes (measured over a large
     * area): about 25% Deep Dark (below vanilla Y 0), 35% Lush or Dripstone where the land suits it, 10% elsewhere.
     */
    static final double DEEP_DARK_MOUNTAINS = 0.50, DEEP_DARK_FLAT = 0.79, CAVE_COMMON = 0.566, CAVE_RARE = 0.709;

    /** 0 on flat land .. 1 in mountains: the relief (highest minus lowest ground) within 500 m, 120 m .. 420 m. */
    private static double mountains(double[] relief) {
        return Math.max(0, Math.min(1, (relief[1] - relief[0] - 120) / 300));
    }

    /**
     * The lowest and highest ground (metres) within about 500 m of a column: nine coarse samples, 500 m apart, at
     * the world's scale. Elevation gaps (NaN) are left out; {0, 0} when there is nothing.
     */
    static double[] relief(WorldModel model, int x, int z) {
        int r = (int) Math.max(8, Math.round(500 / model.cfg().metersPerBlock));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += r) {
            for (int dz = -r; dz <= r; dz += r) {
                double e = model.coarseElevation(x + dx, z + dz);
                if (Double.isNaN(e)) continue;
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
            }
        }
        return lo > hi ? new double[]{0, 0} : new double[]{lo, hi};
    }

    /** Relief that counts as mountains for the surface biomes (metres within 500 m), and the part of it that does. */
    static final double STEEP_RELIEF = 300, UPPER_PART = 0.4, JAGGED_RELIEF = 600, JAGGED_PART = 0.7;

    /** Whether a column lies on the upper slopes or top (the upper 60%) of ground rising this much around it. */
    private static boolean upperSlope(double[] relief, double elev, double minRelief, double part) {
        double span = relief[1] - relief[0];
        return span >= minRelief && (elev - relief[0]) >= part * span;
    }

    /** Land left as nature: what can take a mountain biome (not towns, farms, parks, water or sand). */
    private static boolean naturalCover(LandCover lc) {
        return switch (lc) {
            case NONE, GRASS, MEADOW, SCRUB, HEATH, FOREST, FOREST_CONIFER, FOREST_BROADLEAF, BARE_ROCK, SCREE, ROCK_OUTCROP -> true;
            default -> false;
        };
    }

    /** The mountain biome for an upper slope, or null to keep the usual one (warm and humid mountains). */
    private static Pick reliefPick(LandCover lc, BiomeClassifier.Zone zone, boolean snowy, double meanTempC, boolean jagged) {
        boolean rock = lc == LandCover.BARE_ROCK || lc == LandCover.SCREE || lc == LandCover.ROCK_OUTCROP;
        if (rock) return snowy ? (jagged ? Pick.JAGGED_PEAKS : Pick.FROZEN_PEAKS) : Pick.STONY_PEAKS;
        if (zone == BiomeClassifier.Zone.ARID || zone == BiomeClassifier.Zone.SAVANNA) return Pick.WINDSWEPT_SAVANNA;
        if (lc.isForest()) {
            if (snowy) return Pick.GROVE;
            return meanTempC < COOL_YEAR_C ? Pick.WINDSWEPT_FOREST : null;
        }
        if (snowy) return Pick.SNOWY_SLOPES;
        return meanTempC < COOL_YEAR_C ? Pick.WINDSWEPT_HILLS : null;
    }

    /**
     * Mean yearly temperature (°C) below which a mountain may be Windswept: in its winter (Nov..Apr) snow is right
     * there. Warmer mountains (the Mediterranean, the subtropics) keep their usual biome, which only rains.
     */
    static final double COOL_YEAR_C = 9.0;

    private static boolean wet(Pick p) {
        return switch (p) {
            case JUNGLE, SPARSE_JUNGLE, SWAMP, MANGROVE_SWAMP, FOREST, DARK_FOREST, BIRCH_FOREST, FLOWER_FOREST,
                 DAPPLED_FOREST, CHERRY_GROVE, OLD_GROWTH_PINE_TAIGA -> true;
            default -> false;
        };
    }

    private static boolean dry(Pick p) {
        return switch (p) {
            case DESERT, SAVANNA, BADLANDS, STONY_PEAKS, JAGGED_PEAKS, FROZEN_PEAKS, SNOWY_SLOPES, GROVE, WINDSWEPT_HILLS, WINDSWEPT_SAVANNA,
                 STONY_SHORE, MEADOW -> true;
            default -> false;
        };
    }

    /** Smooth value noise in 0..1 with cells of about {@code cell} blocks (two octaves, so patches are not squares). */
    static double patch(int x, int z, int cell, long salt) {
        return 0.65 * valueNoise(x / (double) cell, z / (double) cell, salt)
                + 0.35 * valueNoise(x / (cell / 2.7), z / (cell / 2.7), salt * 31 + 7);
    }

    private static double valueNoise(double fx, double fz, long salt) {
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(x0, z0, salt), b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt), d = lattice(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private static double lattice(int x, int z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** The biome decision for a block column; also used by the painter for consistency. */
    public static Pick pick(int x, int z) {
        return pick(x, z, null);
    }

    /** As {@link #pick(int, int)}, with the relief around the column when the caller has measured it. */
    private static Pick pick(int x, int z, double[] relief) {
        WorldModel model = OrbisMod.model();
        if (model == null) return Pick.PLAINS;
        double elev = model.coarseElevation(x, z);
        BiomeClassifier.Climate climate = model.climate(x, z, elev);
        // Never block here: the generator awaits the region before the biome
        // step, so during generation this is always loaded; for stray
        // lookups elsewhere a land-only answer beats stalling a thread.
        RegionRaster r = model.rasterIfLoaded(x, z);
        int idx = r == null ? -1 : r.index(x, z);

        boolean sea = false;
        WaterFeature wf = null;
        LandCover lc = LandCover.NONE;
        if (r != null && idx >= 0) {
            wf = r.waterAt(idx);
            // elev is in metres above mean sea level, so "below the sea" is simply < 0.
            sea = r.hasCoastline ? r.isSea(idx) : (model.cfg().seaFromElevation && elev < 0.0);
            // Coarse worlds: the coastline flood fill is unreliable at sub-block detail; the bathymetry overrides
            // clear disagreements (same rule as the painter, so biome and blocks agree).
            if (r.hasCoastline && model.cfg().metersPerBlock >= 8.0 && model.cfg().seaFromElevation) {
                double mpb = model.cfg().metersPerBlock;
                if (!sea && elev < -1.5 * mpb) sea = true;
                else if (sea && elev > 1.5 * mpb) sea = false;
            }
            lc = r.landCoverAt(idx);
            if (r.buildingAt(idx) != null) {
                sea = false;
                wf = null;
            }
        } else {
            sea = model.cfg().seaFromElevation && elev < 0.0;
        }

        double t = climate.meanTempC();
        if (sea || (wf != null && wf.atSeaLevel)) {
            boolean deep = elev < -40;
            if (climate.frozenWater()) return Pick.FROZEN_OCEAN;
            if (t < 8) return deep ? Pick.DEEP_COLD_OCEAN : Pick.COLD_OCEAN;
            if (t < 17) return deep ? Pick.DEEP_OCEAN : Pick.OCEAN;
            if (t < 22) return Pick.LUKEWARM_OCEAN;
            return Pick.WARM_OCEAN;
        }
        if (wf != null) {
            if (wf.kind == WaterFeature.Kind.SWIMMING_POOL || wf.kind == WaterFeature.Kind.FOUNTAIN_BASIN) {
                // keep the surrounding land biome for tint
            } else {
                return climate.frozenWater() ? Pick.FROZEN_RIVER : Pick.RIVER;
            }
        }

        BiomeClassifier.Zone zone = climate.zone();
        boolean snowy = climate.snowy();

        // Mountains by the shape of the ground (worlds with reliefBiomes): the upper slopes and tops of steep relief
        // in natural cover. The biome's temperature decides the weather there through the seasons: Windswept (0.2)
        // gets snow from November to April and rain in summer, so it is used only where the year is cool; the
        // snow-all-year mountain biomes (Grove, Snowy Slopes, Jagged Peaks) only where the climate is snowy anyway.
        if (model.cfg().reliefBiomes && naturalCover(lc) && !(r != null && idx >= 0 && r.buildingAt(idx) != null)) {
            if (relief == null) relief = relief(model, x, z);
            if (upperSlope(relief, elev, STEEP_RELIEF, UPPER_PART)) {
                Pick mountain = reliefPick(lc, zone, snowy, t, upperSlope(relief, elev, JAGGED_RELIEF, JAGGED_PART));
                if (mountain != null) return mountain;
            }
        }

        switch (lc) {
            case BEACH, SAND -> {
                if (zone == BiomeClassifier.Zone.ARID) return Pick.DESERT;
                // A beach is by the water: sand inland (dunes, sandpits, bare ground in warm lands) takes the land's
                // biome below, so no beach biome, with its buried treasure, sits on a mountainside.
                if (lc == LandCover.BEACH || elev < 15) return snowy ? Pick.SNOWY_BEACH : Pick.BEACH;
            }
            case WETLAND, SALT_MARSH, MUD -> {
                if (snowy) return Pick.SNOWY_TAIGA;
                return climate.isTropical() ? Pick.MANGROVE_SWAMP : Pick.SWAMP;
            }
            case BARE_ROCK, SCREE, ROCK_OUTCROP, QUARRY -> {
                return snowy ? Pick.FROZEN_PEAKS : Pick.STONY_PEAKS;
            }
            case GLACIER -> {
                return Pick.FROZEN_PEAKS;
            }
            default -> { }
        }

        if (lc.isForest()) {
            if (snowy) return Pick.SNOWY_TAIGA;
            // Autumn: broadleaf woods of the temperate and boreal zones in their October colours.
            if (model.cfg().autumnColours && lc != LandCover.FOREST_CONIFER
                    && (zone == BiomeClassifier.Zone.TEMPERATE || zone == BiomeClassifier.Zone.SUBTROPICAL
                    || lc == LandCover.FOREST_BROADLEAF && (zone == BiomeClassifier.Zone.TAIGA || zone == BiomeClassifier.Zone.TUNDRA))) {
                return Pick.DAPPLED_FOREST;
            }
            switch (zone) {
                case TUNDRA, TAIGA, ALPINE -> {
                    return lc == LandCover.FOREST_BROADLEAF ? Pick.BIRCH_FOREST : Pick.TAIGA;
                }
                case TEMPERATE -> {
                    return lc == LandCover.FOREST_CONIFER ? Pick.OLD_GROWTH_PINE_TAIGA : Pick.FOREST;
                }
                case SUBTROPICAL -> {
                    return lc == LandCover.FOREST_CONIFER ? Pick.OLD_GROWTH_PINE_TAIGA : Pick.DARK_FOREST;
                }
                case ARID -> {
                    return Pick.SAVANNA;
                }
                case SAVANNA -> {
                    return Pick.SPARSE_JUNGLE;
                }
                case TROPICAL_HUMID -> {
                    return Pick.JUNGLE;
                }
                default -> {
                    return Pick.FOREST;
                }
            }
        }
        if (lc == LandCover.ORCHARD || lc == LandCover.GARDEN) {
            if (!snowy && (zone == BiomeClassifier.Zone.TEMPERATE || zone == BiomeClassifier.Zone.SUBTROPICAL)) return Pick.FLOWER_FOREST;
        }

        return switch (zone) {
            case ICE_CAP -> Pick.FROZEN_PEAKS;
            case ALPINE -> snowy ? Pick.SNOWY_SLOPES : Pick.STONY_PEAKS;
            case TUNDRA -> Pick.SNOWY_PLAINS;
            case TAIGA -> snowy ? Pick.SNOWY_TAIGA : (elev > 900 ? Pick.GROVE : Pick.TAIGA);
            case TEMPERATE -> snowy ? Pick.SNOWY_PLAINS : (elev > 1800 ? Pick.MEADOW : Pick.PLAINS);
            case SUBTROPICAL -> elev > 2000 ? Pick.MEADOW : Pick.PLAINS;
            case ARID -> elev > 1500 ? Pick.BADLANDS : Pick.DESERT;
            case SAVANNA -> Pick.SAVANNA;
            case TROPICAL_HUMID -> Pick.SPARSE_JUNGLE;
        };
    }
}
