package com.berg.orbis.worldgen;

import com.berg.orbis.mc.Mc;
import com.berg.orbis.mc.PlaceableFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vanilla's underwater plants: kelp forests, seagrass, sea pickles and warm-water coral reefs.
 *
 * Orbis replaces vanilla's decoration step, so the water biomes it assigns (cold, temperate, lukewarm and warm
 * oceans, rivers and lakes) would otherwise stay bare. Here each of those biomes gets its vanilla plants with
 * vanilla's own placement rules (counts, kelp's patch noise, the spread around a chunk, the floor checks and the
 * biome check at the spot), reproduced by hand because the placement modifiers are not portable between Minecraft
 * versions; the features themselves are vanilla's, looked up by name, so a kelp stalk or a reef grows exactly as in
 * vanilla. Frozen oceans and rivers stay bare, as in vanilla.
 */
public final class SeaVegetation {

    private enum How { KELP, SPREAD, PICKLES, CORAL }

    /**
     * One vanilla placed feature: the feature, how it is placed, and its number (attempts per chunk, or the noise
     * ratio for kelp and coral).
     */
    private record Plant(String name, String feature, How how, int number) {
    }

    private static final Plant KELP_COLD = new Plant("kelp_cold", "kelp", How.KELP, 120);
    private static final Plant KELP_WARM = new Plant("kelp_warm", "kelp", How.KELP, 80);

    private static final Map<ResourceKey<Biome>, List<Plant>> BY_BIOME = Map.of(
            Biomes.OCEAN, List.of(new Plant("seagrass_normal", "seagrass_short", How.SPREAD, 48), KELP_COLD),
            Biomes.DEEP_OCEAN, List.of(new Plant("seagrass_deep", "seagrass_tall", How.SPREAD, 48), KELP_COLD),
            Biomes.COLD_OCEAN, List.of(new Plant("seagrass_cold", "seagrass_short", How.SPREAD, 32), KELP_COLD),
            Biomes.DEEP_COLD_OCEAN, List.of(new Plant("seagrass_deep_cold", "seagrass_tall", How.SPREAD, 40), KELP_COLD),
            Biomes.LUKEWARM_OCEAN, List.of(new Plant("seagrass_warm", "seagrass_short", How.SPREAD, 80), KELP_WARM),
            Biomes.DEEP_LUKEWARM_OCEAN, List.of(new Plant("seagrass_deep_warm", "seagrass_tall", How.SPREAD, 80), KELP_WARM),
            Biomes.WARM_OCEAN, List.of(new Plant("warm_ocean_vegetation", "warm_ocean_vegetation", How.CORAL, 20),
                    new Plant("seagrass_warm", "seagrass_short", How.SPREAD, 80),
                    new Plant("sea_pickle", "sea_pickle", How.PICKLES, 20)),
            Biomes.RIVER, List.of(new Plant("seagrass_river", "seagrass_slightly_less_short", How.SPREAD, 48)),
            Biomes.SWAMP, List.of(new Plant("seagrass_swamp", "seagrass_mid", How.SPREAD, 64)));

    private static volatile boolean warned;

    private SeaVegetation() {
    }

    public static void place(WorldGenLevel level, ChunkGenerator generator, ChunkPos pos) {
        int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
        // The biomes of the chunk's water, as vanilla collects the biomes of a chunk before decorating it.
        Set<Plant> plants = new LinkedHashSet<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = 2; dx < 16; dx += 4) {
            for (int dz = 2; dz < 16; dz += 4) {
                int x = minX + dx, z = minZ + dz;
                probe.set(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), z);
                if (!level.getBlockState(probe).is(Blocks.WATER)) continue;
                List<Plant> here = plantsOf(level.getBiome(probe));
                if (here != null) plants.addAll(here);
            }
        }
        if (plants.isEmpty()) return;

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), minX, minZ);
        int index = 0;
        for (Plant plant : plants) {
            random.setFeatureSeed(seed, index++, GenerationStep.Decoration.VEGETAL_DECORATION.ordinal());
            Optional<PlaceableFeature> found = Mc.feature(level.registryAccess(), plant.feature());
            if (found.isEmpty()) continue;
            try {
                for (BlockPos at : spots(level, plant, random, minX, minZ)) {
                    if (plantsOf(level.getBiome(at)) instanceof List<Plant> there && there.contains(plant)) {
                        found.get().place(level, generator, random, at);
                    }
                }
            } catch (RuntimeException e) {
                if (!warned) {
                    warned = true;
                    System.err.println("[orbis] Sea vegetation " + plant.name() + " failed in chunk " + pos + ": " + e);
                }
            }
        }
    }

    private static List<Plant> plantsOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(BY_BIOME::get).orElse(null);
    }

    /** Where vanilla's placement modifiers of this plant put it in the chunk: the spots that pass its filters. */
    private static List<BlockPos> spots(WorldGenLevel level, Plant plant, RandomSource random, int minX, int minZ) {
        List<BlockPos> out = new ArrayList<>();
        switch (plant.how()) {
            case KELP -> {
                // noise_based_count (noise factor 80), in_square, heightmap OCEAN_FLOOR, then kelp's floor checks.
                int count = (int) Math.ceil(Mc.biomeInfoNoise(minX / 80.0, minZ / 80.0) * plant.number());
                for (int i = 0; i < count; i++) {
                    int x = minX + random.nextInt(16), z = minZ + random.nextInt(16);
                    BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), z);
                    BlockState below = level.getBlockState(at.below());
                    if (level.getBlockState(at).is(Blocks.WATER) && level.getBlockState(at.above()).is(Blocks.WATER)
                            && below.isFaceSturdy(level, at.below(), Direction.UP) && !below.is(BlockTags.CANNOT_SUPPORT_KELP)) {
                        out.add(at);
                    }
                }
            }
            case CORAL -> {
                // noise_based_count (noise factor 400), in_square, heightmap OCEAN_FLOOR_WG; the reef checks the rest.
                int count = (int) Math.ceil(Mc.biomeInfoNoise(minX / 400.0, minZ / 400.0) * plant.number());
                for (int i = 0; i < count; i++) {
                    int x = minX + random.nextInt(16), z = minZ + random.nextInt(16);
                    out.add(new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z), z));
                }
            }
            case SPREAD, PICKLES -> {
                // [rarity 1/16 for pickles], in_square, count, offset -7..7 (trapezoid), heightmap OCEAN_FLOOR, water.
                if (plant.how() == How.PICKLES && random.nextFloat() >= 1.0f / 16) return out;
                int cx = minX + random.nextInt(16), cz = minZ + random.nextInt(16);
                for (int i = 0; i < plant.number(); i++) {
                    int x = cx + trapezoid7(random), z = cz + trapezoid7(random);
                    BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), z);
                    if (level.getBlockState(at).is(Blocks.WATER)) out.add(at);
                }
            }
        }
        return out;
    }

    /** Vanilla's trapezoid -7..7 with no plateau: two dice of 0..7. */
    private static int trapezoid7(RandomSource random) {
        return -7 + random.nextInt(8) + random.nextInt(8);
    }
}
