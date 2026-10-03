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
        DAPPLED_FOREST
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
        KEYS.put(Pick.DAPPLED_FOREST, ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                Identifier.withDefaultNamespace("dappled_forest")));
    }

    private final HolderGetter<Biome> biomes;
    private final Map<Pick, Holder<Biome>> holders = new EnumMap<>(Pick.class);
    private final Holder<Biome> fallback;
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
    private record Column(long key, Holder<Biome> biome) {
    }

    public RealWorldBiomeSource(HolderGetter<Biome> biomes) {
        this.biomes = biomes;
        for (Map.Entry<Pick, ResourceKey<Biome>> e : KEYS.entrySet()) {
            biomes.get(e.getValue()).ifPresent(h -> holders.put(e.getKey(), h));
        }
        this.fallback = holders.getOrDefault(Pick.PLAINS, biomes.getOrThrow(Biomes.PLAINS));
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
        return List.copyOf(holders.values()).stream();
    }

    @Override
    public Holder<Biome> biomeAt(int qx, int qy, int qz) {
        long key = (((long) qx) << 32) ^ (qz & 0xffffffffL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - CACHE_BITS));
        Column c = columnCache[slot];
        if (c != null && c.key == key) return c.biome;
        // Outside the hard limit the chunk stays empty: any biome will do, without asking the map.
        if (com.berg.orbis.worldgen.HardLimit.blocks(qx >> 2, qz >> 2)) return fallback;
        Pick p = pick((qx << 2) + 2, (qz << 2) + 2);
        Holder<Biome> h = holders.get(p);
        if (h == null) h = p == Pick.DAPPLED_FOREST ? holders.getOrDefault(Pick.FOREST, fallback) : fallback;
        columnCache[slot] = new Column(key, h);
        return h;
    }

    /** The biome decision for a block column; also used by the painter for consistency. */
    public static Pick pick(int x, int z) {
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
