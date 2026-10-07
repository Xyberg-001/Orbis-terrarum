package com.berg.orbis.worldgen;

import com.berg.orbis.mc.Mc;
import com.berg.orbis.mc.PlaceableFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.util.List;
import java.util.Optional;

/**
 * The cave biomes' own decoration in the {@link UndergroundBand} (underground layout 2): moss, clay pools, azaleas,
 * cave vines and spore blossoms in Lush Caves; dripstone columns, clusters and spikes in Dripstone Caves; sculk in
 * the Deep Dark.
 *
 * Orbis replaces vanilla's decoration step, so the cave biomes RealWorldBiomeSource puts in the band would
 * otherwise be bare stone. Vanilla's placements are reproduced by hand as in {@link SeaVegetation} (counts, the
 * scan up or down to a cave's ceiling or floor, the biome check at the spot) because placement modifiers are not
 * portable between Minecraft versions; the features are vanilla's own, looked up by name. Vanilla spreads its
 * attempts over Y -64..256; the band is vanilla's -64..63, so the counts are scaled by 128 / 320 to give the same
 * density.
 */
public final class UndergroundBiomes {

    /** Where an attempt goes from its random spot: as it is, or up / down through air to a ceiling or floor. */
    private enum Scan { NONE, CEILING, CEILING_STURDY, FLOOR }

    /** One vanilla placed feature: its feature, the attempts per chunk (min..max), the scan, and dripstone's spread. */
    private record Deco(String feature, int min, int max, Scan scan, boolean spread) {
    }

    private record Set(ResourceKey<Biome> biome, GenerationStep.Decoration step, List<Deco> decos) {
    }

    private static final double DENSITY = 128.0 / 320.0;

    private static final List<Set> SETS = List.of(
            new Set(Biomes.DEEP_DARK, GenerationStep.Decoration.UNDERGROUND_DECORATION, List.of(
                    new Deco("sculk_vein", 204, 250, Scan.NONE, false),
                    new Deco("sculk_patch_deep_dark", 256, 256, Scan.NONE, false))),
            new Set(Biomes.DRIPSTONE_CAVES, GenerationStep.Decoration.LOCAL_MODIFICATIONS, List.of(
                    new Deco("large_dripstone", 10, 48, Scan.NONE, false))),
            new Set(Biomes.DRIPSTONE_CAVES, GenerationStep.Decoration.UNDERGROUND_DECORATION, List.of(
                    new Deco("dripstone_cluster", 48, 96, Scan.NONE, false),
                    new Deco("pointed_dripstone", 192, 256, Scan.NONE, true))),
            new Set(Biomes.LUSH_CAVES, GenerationStep.Decoration.VEGETAL_DECORATION, List.of(
                    new Deco("moss_patch_ceiling", 125, 125, Scan.CEILING, false),
                    new Deco("cave_vine", 188, 188, Scan.CEILING_STURDY, false),
                    new Deco("lush_caves_clay", 62, 62, Scan.FLOOR, false),
                    new Deco("moss_patch", 125, 125, Scan.FLOOR, false),
                    new Deco("rooted_azalea_tree", 1, 2, Scan.CEILING, false),
                    new Deco("spore_blossom", 25, 25, Scan.CEILING, false),
                    new Deco("vines", 256, 256, Scan.NONE, false))));

    private static volatile boolean warned;

    private UndergroundBiomes() {
    }

    public static void place(WorldModel model, WorldGenLevel level, ChunkGenerator generator, ChunkPos pos) {
        int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
        int offset = model.band().offset(minX >> 4, minZ >> 4);
        if (!hasCaveBiome(level, minX, minZ, offset)) return;
        int floor = level.getMinY() + 5;
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), minX, minZ);
        int index = 0;
        for (Set set : SETS) {
            for (Deco d : set.decos()) {
                random.setFeatureSeed(seed, 100 + index++, set.step().ordinal());
                Optional<PlaceableFeature> found = Mc.feature(level.registryAccess(), d.feature());
                if (found.isEmpty()) continue;
                int count = (int) Math.round(Mth.randomBetweenInclusive(random, d.min(), d.max()) * DENSITY);
                if (count == 0 && random.nextDouble() < d.max() * DENSITY) count = 1;
                for (int n = 0; n < count; n++) {
                    int y = Mth.randomBetweenInclusive(random, UndergroundBand.VANILLA_BOTTOM, UndergroundBand.VANILLA_SURFACE - 1) + offset;
                    BlockPos at = new BlockPos(minX + random.nextInt(16), y, minZ + random.nextInt(16));
                    if (y <= floor) continue;
                    if (d.spread()) {
                        // pointed_dripstone: 1..5 tries scattered around the spot (clamped normal, 3 across, 0.6 up).
                        int tries = Mth.randomBetweenInclusive(random, 1, 5);
                        for (int t = 0; t < tries; t++) {
                            BlockPos p = at.offset(normal(random, 3.0, 10), normal(random, 0.6, 2), normal(random, 3.0, 10));
                            if (level.getBiome(p).is(set.biome())) placeOne(found.get(), level, generator, random, p, d);
                        }
                        continue;
                    }
                    BlockPos p = scan(level, at, d.scan());
                    if (p != null && level.getBiome(p).is(set.biome())) placeOne(found.get(), level, generator, random, p, d);
                }
            }
        }
    }

    private static void placeOne(PlaceableFeature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos at, Deco d) {
        try {
            feature.place(level, generator, random, at);
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                System.err.println("[orbis] Cave biome feature " + d.feature() + " failed at " + at + ": " + e);
            }
        }
    }

    /** Whether any cave biome is in the chunk's band (sampled every 16 blocks down its middle). */
    private static boolean hasCaveBiome(WorldGenLevel level, int minX, int minZ, int offset) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = 2; dx < 16; dx += 12) {
            for (int dz = 2; dz < 16; dz += 12) {
                for (int v = UndergroundBand.VANILLA_BOTTOM + 4; v < 56; v += 16) {
                    p.set(minX + dx, v + offset, minZ + dz);
                    var b = level.getBiome(p);
                    if (b.is(Biomes.LUSH_CAVES) || b.is(Biomes.DRIPSTONE_CAVES) || b.is(Biomes.DEEP_DARK)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Vanilla's environment_scan (air allowed, 12 steps) followed by its random_offset of one block back into the
     * air: the air under a solid ceiling, the air under a ceiling with a sturdy underside, or the air over a solid
     * floor. Null when the spot is not air or nothing is found within the steps.
     */
    private static BlockPos scan(WorldGenLevel level, BlockPos start, Scan scan) {
        if (scan == Scan.NONE) return start;
        Direction dir = scan == Scan.FLOOR ? Direction.DOWN : Direction.UP;
        BlockPos.MutableBlockPos p = start.mutable();
        if (!level.getBlockState(p).isAir()) return null;
        for (int i = 0; i < 12; i++) {
            p.move(dir);
            if (level.isOutsideBuildHeight(p.getY())) return null;
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            boolean target = scan == Scan.CEILING_STURDY ? s.isFaceSturdy(level, p, Direction.DOWN) : s.isSolid();
            return target ? p.move(dir.getOpposite()).immutable() : null;
        }
        return null;
    }

    private static int normal(RandomSource random, double deviation, int limit) {
        return (int) Mth.clamp(random.nextGaussian() * deviation, -limit, limit);
    }
}
