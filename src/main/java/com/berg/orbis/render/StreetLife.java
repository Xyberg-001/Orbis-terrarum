package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Life on the streets, from the land cover: cats in residential streets, small herds of sheep and cows on
 * meadows and farmland, a wandering trader (with no despawn timer) on marketplaces, and boats moored on
 * the water of marinas. All vanilla entities, spawned once at chunk generation and kept.
 */
public final class StreetLife {

    private StreetLife() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.streetLife) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long chunkHash = ColumnPainter.hash(minX >> 4, minZ >> 4, 0x11FE);
        boolean traderDone = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0) continue;
                LandCover lc = r.landCoverAt(idx);
                long h = ColumnPainter.hash(x, z, 0x5EE7);
                switch (lc) {
                    case RESIDENTIAL, GARDEN -> {
                        int y = h % 1500 == 0 ? standY(level, pos, r, idx, x, z, cfg) : Integer.MIN_VALUE;
                        if (y != Integer.MIN_VALUE) spawn(level, EntityTypes.CAT, x, y, z, true);
                    }
                    case MEADOW, FARMLAND, GRASS -> {
                        int y = h % 900 == 0 ? standY(level, pos, r, idx, x, z, cfg) : Integer.MIN_VALUE;
                        if (y != Integer.MIN_VALUE) {
                            EntityType<? extends Mob> type = (h >>> 12) % 3 == 0 ? EntityTypes.COW : EntityTypes.SHEEP;
                            spawn(level, type, x, y, z, true);
                            long taken = ((long) x << 32) ^ (z & 0xFFFFFFFFL), takenToo = taken;
                            for (int i = 1; i <= 2; i++) {
                                int gx = x + (int) ((h >>> (16 + 4 * i)) % 5) - 2, gz = z + (int) ((h >>> (18 + 4 * i)) % 5) - 2;
                                long spot = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
                                if (spot == taken || spot == takenToo) continue; // one animal a spot (they would share an id, see SpawnIds)
                                takenToo = spot;
                                int gi = r.index(gx, gz);
                                int gy = gi >= 0 ? standY(level, pos, r, gi, gx, gz, cfg) : Integer.MIN_VALUE;
                                if (gy != Integer.MIN_VALUE && Math.abs(gy - y) <= 2) spawn(level, type, gx, gy, gz, true);
                            }
                        }
                    }
                    case MARKETPLACE -> {
                        int y = !traderDone && chunkHash % 2 == 0 ? standY(level, pos, r, idx, x, z, cfg) : Integer.MIN_VALUE;
                        if (y != Integer.MIN_VALUE) {
                            Entity e = spawn(level, EntityTypes.WANDERING_TRADER, x, y, z, true);
                            if (e instanceof WanderingTrader t) {
                                t.setDespawnDelay(0);
                                t.setWanderTarget(new BlockPos(x, y, z));
                            }
                            traderDone = true;
                        }
                    }
                    case MARINA -> {
                        if (h % 40 == 0 && ((x & 3) == 0) && ((z & 3) == 0)) {
                            int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); // first block above the sea bed = water
                            pos.set(x, top, z);
                            BlockState here = level.getBlockState(pos);
                            int surface = top;
                            while (surface < cfg.maxY() - 1 && !level.getBlockState(pos.set(x, surface, z)).getFluidState().isEmpty()) surface++;
                            if (surface - top < 2 || !here.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER)) break;
                            EntityType<?> boat = (h >>> 8) % 3 == 0 ? EntityTypes.SPRUCE_BOAT : (h >>> 8) % 3 == 1 ? EntityTypes.BIRCH_BOAT : EntityTypes.OAK_BOAT;
                            Entity b = boat.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
                            if (b != null) {
                                b.snapTo(x + 0.5, surface, z + 0.5, (float) (h % 360), 0f);
                                SpawnIds.assign(level, b);
                                level.addFreshEntity(b);
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
    }

    /** Feet Y on open ground (not road, building or water; solid block with two clear blocks above), or MIN_VALUE. */
    private static int standY(WorldGenLevel level, BlockPos.MutableBlockPos pos, RegionRaster r, int idx, int x, int z, OrbisConfig cfg) {
        if (r.road[idx] != 0 || r.building[idx] != 0 || r.water[idx] != 0 || (r.hasCoastline && r.isSea(idx))) return Integer.MIN_VALUE;
        int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        if (surface <= cfg.minY || surface + 3 >= cfg.maxY()) return Integer.MIN_VALUE;
        if (!level.getBlockState(pos.set(x, surface, z)).isSolid()) return Integer.MIN_VALUE;
        BlockState a = level.getBlockState(pos.set(x, surface + 1, z));
        if (!a.getFluidState().isEmpty() || !a.getCollisionShape(level, pos).isEmpty()) return Integer.MIN_VALUE;
        BlockState b = level.getBlockState(pos.set(x, surface + 2, z));
        if (!b.getCollisionShape(level, pos).isEmpty()) return Integer.MIN_VALUE;
        return surface + 1;
    }

    private static Entity spawn(WorldGenLevel level, EntityType<?> type, int x, int y, int z, boolean persistent) {
        Entity e = type.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
        if (e == null) return null;
        e.snapTo(x + 0.5, y, z + 0.5, (float) (ColumnPainter.hash(x, z, 0x7A11) % 360L), 0f);
        if (e instanceof net.minecraft.world.entity.animal.feline.Cat cat) {
            // Cat.finalizeSpawn picks the variant with a structure check that reads chunks through the real
            // ServerLevel: from a worldgen thread that deadlocks against the server thread. Pick the coat here.
            catCoat(level, cat, ColumnPainter.hash(x, z, 0xCA7));
            if (persistent) cat.setPersistenceRequired();
        } else if (e instanceof Mob mob) {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), EntitySpawnReason.STRUCTURE, null);
            if (persistent) mob.setPersistenceRequired();
        }
        SpawnIds.assign(level, e);
        level.addFreshEntityWithPassengers(e);
        return e;
    }

    private static java.lang.reflect.Method setCatVariant;

    private static void catCoat(WorldGenLevel level, net.minecraft.world.entity.animal.feline.Cat cat, long h) {
        try {
            var variants = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.CAT_VARIANT)
                    .listElements().toList();
            if (variants.isEmpty()) return;
            var pick = variants.get((int) Math.floorMod(h >>> 4, (long) variants.size()));
            if (setCatVariant == null) {
                java.lang.reflect.Method m = net.minecraft.world.entity.animal.feline.Cat.class.getDeclaredMethod("setVariant", net.minecraft.core.Holder.class);
                m.setAccessible(true);
                setCatVariant = m;
            }
            setCatVariant.invoke(cat, pick);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // default coat then
        }
    }
}
