package com.berg.orbis.worldgen;

import com.berg.orbis.mc.Mc;
import com.berg.orbis.mc.PlaceableFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.util.List;
import java.util.Optional;

/**
 * Vanilla's underground features, placed in the {@link UndergroundBand}.
 *
 * Dungeons (monster rooms with a zombie/skeleton/spider spawner and chests),
 * amethyst geodes, fossils, water and lava springs and glow lichen are not
 * structures but features, and vanilla places them at absolute Y between
 * bedrock and Y 58 or so. Here each of vanilla's placements is reproduced
 * with the same attempt counts and Y ranges, then shifted by the chunk's
 * band offset; the features themselves are vanilla's own (they are looked up
 * in the feature registry by name), so a dungeon looks and drops exactly
 * as in vanilla. Dungeons and lichen need a cave wall to attach to, which
 * the {@link CaveCarver} provides.
 */
public final class UndergroundFeatures {

    /** One vanilla placement: attempts per chunk, chance per attempt, vanilla Y range. */
    private record Placement(String feature, int attempts, int extraAttempts, float chance,
                             int minY, int maxY, boolean biasedToBottom) {
    }

    private static final List<Placement> PLACEMENTS = List.of(
            // monster_room: count 10, Y 0..58
            new Placement("monster_room", 10, 0, 1.0f, 0, 58, false),
            // monster_room_deep: count 4, Y above_bottom 6 .. -1
            new Placement("monster_room", 4, 0, 1.0f, -58, -1, false),
            // amethyst_geode: 1 in 24 chunks, Y above_bottom 6 .. 30
            new Placement("amethyst_geode", 1, 0, 1.0f / 24, -58, 30, false),
            // fossil_upper: 1 in 64 chunks, Y 0 .. top (the band top here)
            new Placement("fossil_coal", 1, 0, 1.0f / 64, 0, 63, false),
            // fossil_lower: 1 in 64 chunks, Y bottom .. -8
            new Placement("fossil_diamonds", 1, 0, 1.0f / 64, -64, -8, false),
            // spring_lava: count 20, very biased to the bottom, Y bottom .. below_top 8
            new Placement("spring_lava_overworld", 20, 0, 1.0f, -64, 56, true),
            // spring_water: count 25 over vanilla's 256 blocks of Y; the band is 128 tall
            new Placement("spring_water", 12, 0, 1.0f, -64, 63, false),
            // glow_lichen: count 104..157 over 320 blocks of Y; scaled to the band
            new Placement("glow_lichen", 42, 22, 1.0f, -64, 63, false));

    private static volatile boolean warned;

    private UndergroundFeatures() {
    }

    public static void place(WorldModel model, WorldGenLevel level, ChunkGenerator generator, ChunkPos pos) {
        int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
        int offset = model.band().offset(minX >> 4, minZ >> 4);
        int floor = level.getMinY() + 5;
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), minX, minZ);
        int index = 0;
        for (Placement p : PLACEMENTS) {
            random.setFeatureSeed(seed, index++, GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal());
            Optional<PlaceableFeature> found = Mc.feature(level.registryAccess(), p.feature());
            if (found.isEmpty()) continue;
            PlaceableFeature feature = found.get();
            int attempts = p.attempts() + (p.extraAttempts() > 0 ? random.nextInt(p.extraAttempts()) : 0);
            for (int n = 0; n < attempts; n++) {
                if (p.chance() < 1.0f && random.nextFloat() >= p.chance()) continue;
                int vanillaY = p.biasedToBottom()
                        ? veryBiasedToBottom(random, p.minY(), p.maxY(), 8)
                        : Mth.randomBetweenInclusive(random, p.minY(), p.maxY());
                int y = vanillaY + offset;
                if (y <= floor) continue;
                BlockPos at = new BlockPos(minX + random.nextInt(16), y, minZ + random.nextInt(16));
                try {
                    feature.place(level, generator, random, at);
                } catch (RuntimeException e) {
                    if (!warned) {
                        warned = true;
                        System.err.println("[orbis] Underground feature " + p.feature() + " failed at " + at + ": " + e);
                    }
                }
            }
        }
    }

    /** Vanilla's VeryBiasedToBottomHeight. */
    private static int veryBiasedToBottom(RandomSource random, int min, int max, int inner) {
        int l = Mth.nextInt(random, min + inner, max);
        int m = Mth.nextInt(random, min, l - 1);
        return Mth.nextInt(random, min, m - 1 + inner);
    }
}
