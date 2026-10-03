package com.berg.orbis.render;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.DecorType;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.imagery.GroundClass;
import com.berg.orbis.worldgen.WorldModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The "features" pass: things that need to see their neighbours or write
 * across chunk borders -- trees, street lamps, fences, power towers, zebra
 * crossings. Runs in ChunkGenerator.applyBiomeDecoration with a
 * WorldGenLevel that may write up to one chunk outside the current one.
 */
public final class Decorator {

    private final OrbisConfig cfg;
    private final WorldModel model;

    public Decorator(OrbisConfig cfg, WorldModel model) {
        this.cfg = cfg;
        this.model = model;
    }

    public void decorateChunk(WorldGenLevel level, int chunkMinX, int chunkMinZ) {
        RegionRaster r = model.rasterIfLoaded(chunkMinX, chunkMinZ);
        if (r == null && cfg.waitForOsm && !model.farFromPlayers(chunkMinX + 8, chunkMinZ + 8)) r = model.rasterForBlock(chunkMinX, chunkMinZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxY = cfg.maxY();

        TreeBuilder.Sink treeSink = (x, y, z, state, onlyIfAirOrLeaves) -> {
            if (y <= cfg.minY || y >= maxY) return false;
            pos.set(x, y, z);
            if (onlyIfAirOrLeaves) {
                BlockState existing = level.getBlockState(pos);
                if (!existing.isAir() && !(existing.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
                        && !existing.is(Blocks.SHORT_GRASS) && !existing.is(Blocks.FERN) && !existing.is(Blocks.SNOW)
                        && !existing.is(Blocks.SNOW_BLOCK)
                        && !existing.is(Blocks.TALL_GRASS) && !existing.is(Blocks.LEAF_LITTER)) {
                    return false;
                }
            }
            level.setBlock(pos, state, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
            return true;
        };

        // Coarse worlds: vanilla-scale towns on the built-up areas, before anything else claims the ground.
        SettlementBuilder.Grid grid = SettlementBuilder.of(level);
        boolean[] built = SettlementBuilder.place(cfg, grid, r, chunkMinX, chunkMinZ);
        // Real buildings (1:1 worlds): villager households with beds, and workstations where the map has a business.
        try {
            Residents.place(cfg, grid, r, chunkMinX, chunkMinZ);
        } catch (RuntimeException e) {
            System.err.println("[orbis] Residents failed in chunk " + (chunkMinX >> 4) + "," + (chunkMinZ >> 4) + ": " + e);
        }
        // City life: furniture by building type, name signs at doors, rideable railways with waiting minecarts,
        // animals / traders / boats from the land cover, gravestones and playgrounds.
        // Each pass on its own, so one failing pass never costs the chunk the others.
        final RegionRaster raster = r;
        guarded("Interiors", chunkMinX, chunkMinZ, () -> Interiors.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("Signage", chunkMinX, chunkMinZ, () -> Signage.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("Awnings", chunkMinX, chunkMinZ, () -> Awnings.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("RoadRamps", chunkMinX, chunkMinZ, () -> RoadRamps.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("Transit", chunkMinX, chunkMinZ, () -> Transit.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("StreetLife", chunkMinX, chunkMinZ, () -> StreetLife.place(cfg, level, raster, chunkMinX, chunkMinZ));
        guarded("Grounds", chunkMinX, chunkMinZ, () -> Grounds.place(cfg, level, raster, chunkMinX, chunkMinZ, built));
        guarded("Tunnels", chunkMinX, chunkMinZ, () -> Tunnels.place(cfg, level, raster, chunkMinX, chunkMinZ));

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (built[lx * 16 + lz]) continue;
                int x = chunkMinX + lx, z = chunkMinZ + lz;
                int idx = r == null ? -1 : r.index(x, z);
                LandCover lc = idx >= 0 ? r.landCoverAt(idx) : LandCover.NONE;
                DecorType decor = idx >= 0 ? r.decorAt(idx) : DecorType.NONE;
                boolean occupied = idx >= 0 && (r.road[idx] != 0 || r.buildingAt(idx) != null || r.water[idx] != 0
                        || (r.hasCoastline && r.isSea(idx)));

                int surfaceY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                if (surfaceY <= cfg.minY || surfaceY >= maxY - 2) continue;
                pos.set(x, surfaceY, z);
                BlockState ground = level.getBlockState(pos);
                // Deep real snow: trees and street furniture stand on the ground under it, poking through.
                for (int i = 0; i < 24 && ground.is(Blocks.SNOW_BLOCK) && surfaceY - 1 > cfg.minY; i++) {
                    pos.set(x, --surfaceY, z);
                    ground = level.getBlockState(pos);
                }
                pos.set(x, surfaceY + 1, z);
                BlockState above = level.getBlockState(pos);
                if (!above.getFluidState().isEmpty()) continue; // under water
                double elev = model.elevation(x, z);
                BiomeClassifier.Climate climate = model.climate(x, z, elev);

                if (decor != DecorType.NONE && cfg.generateStreetFurniture) {
                    int[] shifted = shiftOffRoadway(r, idx, x, z, decor);
                    if (shifted != null) {
                        int sx = shifted[0], sz = shifted[1];
                        int sIdx = r.index(sx, sz);
                        int sSurface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz) - 1;
                        pos.set(sx, sSurface, sz);
                        BlockState sGround = level.getBlockState(pos);
                        if (sSurface > cfg.minY && sSurface < maxY - 2 && placeDecor(level, r, sIdx, sx, sSurface, sz, decor, sGround, climate, treeSink)) continue;
                    } else if (placeDecor(level, r, idx, x, surfaceY, z, decor, ground, climate, treeSink)) {
                        continue;
                    }
                }
                if (occupied || !cfg.generateTrees) continue;
                if (r != null && r.canopyTrees) continue; // every real tree is already marked from the canopy model
                if (!lc.allowsTrees()) continue;
                if (!isPlantable(ground)) continue;
                if (climate.zone() == BiomeClassifier.Zone.ICE_CAP) continue;

                GroundClass gc = idx >= 0 ? GroundClass.byCode(r.groundClassAt(idx)) : GroundClass.UNKNOWN;
                double density;
                if (cfg.imageryTreeCover && gc == GroundClass.TREE_CANOPY) {
                    density = Math.max(cfg.treeDensityForest, 0.05);
                    if (!lc.isForest()) lc = LandCover.FOREST;
                } else if (gc == GroundClass.PAVED_DARK || gc == GroundClass.PAVED_LIGHT || gc == GroundClass.SAND
                        || gc == GroundClass.ROCK || gc == GroundClass.SNOW) {
                    continue; // imagery says there is no tree here
                } else {
                    density = treeDensity(lc, climate);
                    if (gc == GroundClass.GRASS && lc.isForest()) density *= 0.25; // clearing inside a mapped forest
                }
                if (density <= 0) continue;
                if (!treeChosen(x, z, density, r, lc)) continue;
                TreeBuilder.Species species = pickSpecies(lc, climate, x, z);
                if (species == null) continue;
                // Autumn woods: some of the trees lie on the ground, mushrooms on and beside them.
                if (cfg.autumnColours && lc.isForest() && Math.floorMod(ColumnPainter.hash(x, z, 0xFA11), 100) < 9
                        && fallenTree(level, r, x, surfaceY, z, species, ColumnPainter.hash(x, z, 0xFA12))) {
                    continue;
                }
                int hint = 0;
                if (lc == LandCover.ORCHARD) hint = 5;
                TreeBuilder.place(treeSink, x, surfaceY + 1, z, species, hint, ColumnPainter.hash(x, z, 0x7EE5), cfg.autumnColours);
                if (cfg.autumnColours && TreeBuilder.isBroadleaf(species)) litterRing(level, x, surfaceY, z, ColumnPainter.hash(x, z, 0x11FE));
            }
        }
    }

    /**
     * Autumn: fallen leaves around a broadleaf tree, thickest near the trunk, on the ground wherever it is (slopes
     * included) and only where nothing else grows.
     */
    private static void litterRing(WorldGenLevel level, int x, int surfaceY, int z, long seed) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 == 0 || d2 > 9) continue;
                long h = Materials.mix(seed ^ (dx * 0x9E3779B1L) ^ (dz * 0x85EBCA77L));
                if (Math.floorMod(h, 100) >= (d2 <= 2 ? 60 : 35)) continue;
                int cx = x + dx, cz = z + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                if (Math.abs(top - (surfaceY + 1)) > 2) continue;
                p.set(cx, top - 1, cz);
                BlockState ground = level.getBlockState(p);
                if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT)
                        || ground.is(Blocks.ROOTED_DIRT) || ground.is(Blocks.MOSS_BLOCK))) continue;
                p.set(cx, top, cz);
                BlockState at = level.getBlockState(p);
                if (!at.isAir() && !at.is(Blocks.SHORT_GRASS) && !at.is(Blocks.FERN)) continue;
                level.setBlock(p, ColumnPainter.leafLitter(h, true), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Autumn: a fallen tree of the forest's species, four to seven logs long, lying along x or z on level ground (it
     * is not placed where the ground steps or anything is in the way). Brown mushrooms grow on one log in ten and a
     * shelf mushroom on its side (Minecraft 26.3), as on 26.3's fallen poplars.
     */
    private boolean fallenTree(WorldGenLevel level, RegionRaster r, int x, int surfaceY, int z, TreeBuilder.Species species, long h) {
        boolean alongX = (h & 1) == 0;
        int length = 4 + (int) Math.floorMod(h >>> 8, 4);
        int stepX = alongX ? 1 : 0, stepZ = alongX ? 0 : 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < length; i++) {
            int cx = x + stepX * i, cz = z + stepZ * i;
            if (r != null) {
                int idx = r.index(cx, cz);
                if (idx < 0 || r.road[idx] != 0 || r.building[idx] != 0 || r.water[idx] != 0) return false;
            }
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) != surfaceY + 1) return false;
            p.set(cx, surfaceY + 1, cz);
            BlockState at = level.getBlockState(p);
            if (!at.isAir() && !at.is(Blocks.SHORT_GRASS) && !at.is(Blocks.FERN) && !at.is(Blocks.LEAF_LITTER)) return false;
        }
        BlockState log = TreeBuilder.logOf(species).setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                alongX ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Z);
        for (int i = 0; i < length; i++) {
            int cx = x + stepX * i, cz = z + stepZ * i;
            set(level, cx, surfaceY + 1, cz, log);
            p.set(cx, surfaceY + 2, cz);
            if (Math.floorMod(h >>> (12 + i), 10) == 0 && level.getBlockState(p).isAir()) {
                set(level, cx, surfaceY + 2, cz, Blocks.BROWN_MUSHROOM.defaultBlockState());
            }
        }
        if (NewBlocks.SHELF_MUSHROOM != null && Math.floorMod(h >>> 24, 10) < 8) {
            int i = 1 + (int) Math.floorMod(h >>> 28, Math.max(1, length - 2));
            net.minecraft.core.Direction side = alongX ? ((h >>> 32 & 1) == 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH)
                    : ((h >>> 32 & 1) == 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST);
            int mx = x + stepX * i + side.getStepX(), mz = z + stepZ * i + side.getStepZ();
            p.set(mx, surfaceY + 1, mz);
            if (level.getBlockState(p).isAir()) {
                BlockState m = NewBlocks.SHELF_MUSHROOM;
                if (m.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                    m = m.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, side);
                }
                set(level, mx, surfaceY + 1, mz, m);
            }
        }
        return true;
    }

    private static void guarded(String pass, int chunkMinX, int chunkMinZ, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            System.err.println("[orbis] " + pass + " failed in chunk " + (chunkMinX >> 4) + "," + (chunkMinZ >> 4) + ": " + e);
        }
    }

    private static boolean isPlantable(BlockState ground) {
        return ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT)
                || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MUD) || ground.is(Blocks.SAND) || ground.is(Blocks.ROOTED_DIRT);
    }

    private double treeDensity(LandCover lc, BiomeClassifier.Climate climate) {
        BiomeClassifier.Zone zone = climate.zone();
        if (zone == BiomeClassifier.Zone.ARID) return lc.isForest() ? cfg.treeDensityForest * 0.4 : 0;
        return switch (lc) {
            case FOREST, FOREST_BROADLEAF, FOREST_CONIFER -> cfg.treeDensityForest;
            case ORCHARD -> 0.04;
            case PARK, GARDEN, CEMETERY, GOLF, SCHOOL, HOSPITAL -> cfg.treeDensityPark;
            case SCRUB, HEATH -> cfg.treeDensityScrub;
            case RESIDENTIAL -> cfg.treeDensityPark * 0.5;
            case WETLAND -> cfg.treeDensityScrub;
            case MEADOW, GRASS -> cfg.treeDensityPark * 0.25;
            case NONE -> switch (zone) {
                case TAIGA, TEMPERATE, SUBTROPICAL -> 0.003;
                case TROPICAL_HUMID -> 0.02;
                case SAVANNA -> 0.006;
                default -> 0.0;
            };
            default -> 0;
        };
    }

    /**
     * Deterministic blue-noise-ish selection: a column is a tree if its hash
     * is below the density AND no column within 2 blocks has a smaller hash
     * that also qualifies (so trunks never touch).
     */
    private static boolean treeChosen(int x, int z, double density, RegionRaster r, LandCover lc) {
        if (lc == LandCover.ORCHARD) {
            return Math.floorMod(x, 5) == 2 && Math.floorMod(z, 5) == 2;
        }
        long own = ColumnPainter.hash(x, z, 0x7A33) % 100000;
        if (own >= density * 100000) return false;
        int spacing = density > 0.03 ? 2 : 3;
        for (int dx = -spacing; dx <= spacing; dx++) {
            for (int dz = -spacing; dz <= spacing; dz++) {
                if (dx == 0 && dz == 0) continue;
                long other = ColumnPainter.hash(x + dx, z + dz, 0x7A33) % 100000;
                if (other < density * 100000 && other < own) {
                    // Only a competitor if it is also on plantable land.
                    if (r != null) {
                        int i = r.index(x + dx, z + dz);
                        if (i >= 0 && (r.road[i] != 0 || r.building[i] != 0 || r.water[i] != 0)) continue;
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private static TreeBuilder.Species pickSpecies(LandCover lc, BiomeClassifier.Climate climate, int x, int z) {
        int roll = (int) (ColumnPainter.hash(x, z, 0x5EED) % 100);
        BiomeClassifier.Zone zone = climate.zone();
        boolean cold = climate.isCold();
        if (lc == LandCover.FOREST_CONIFER) return roll < 80 ? TreeBuilder.Species.SPRUCE : TreeBuilder.Species.PINE;
        if (lc == LandCover.FOREST_BROADLEAF) {
            if (climate.isTropical()) return TreeBuilder.Species.JUNGLE;
            if (cold) return roll < 65 ? TreeBuilder.Species.BIRCH : TreeBuilder.Species.OAK;
            return roll < 55 ? TreeBuilder.Species.OAK : roll < 85 ? TreeBuilder.Species.BIRCH : TreeBuilder.Species.DARK_OAK;
        }
        if (lc == LandCover.ORCHARD) return roll < 70 ? TreeBuilder.Species.OAK : TreeBuilder.Species.CHERRY;
        if (lc == LandCover.PARK || lc == LandCover.GARDEN || lc == LandCover.RESIDENTIAL || lc == LandCover.CEMETERY) {
            if (climate.isTropical()) return roll < 60 ? TreeBuilder.Species.PALM : TreeBuilder.Species.JUNGLE;
            if (cold) return roll < 50 ? TreeBuilder.Species.BIRCH : roll < 85 ? TreeBuilder.Species.SPRUCE : TreeBuilder.Species.OAK;
            return roll < 45 ? TreeBuilder.Species.OAK : roll < 75 ? TreeBuilder.Species.BIRCH : roll < 90 ? TreeBuilder.Species.CHERRY : TreeBuilder.Species.SPRUCE;
        }
        if (lc == LandCover.SCRUB || lc == LandCover.HEATH) {
            if (zone == BiomeClassifier.Zone.ARID) return TreeBuilder.Species.BUSH;
            return roll < 60 ? TreeBuilder.Species.BUSH : cold ? TreeBuilder.Species.SPRUCE : TreeBuilder.Species.OAK;
        }
        return switch (zone) {
            case ICE_CAP -> null;
            case TUNDRA, ALPINE -> roll < 70 ? TreeBuilder.Species.SPRUCE : TreeBuilder.Species.BIRCH;
            case TAIGA -> roll < 65 ? TreeBuilder.Species.SPRUCE : roll < 92 ? TreeBuilder.Species.BIRCH : TreeBuilder.Species.PINE;
            case TEMPERATE -> roll < 45 ? TreeBuilder.Species.OAK : roll < 70 ? TreeBuilder.Species.BIRCH : roll < 90 ? TreeBuilder.Species.SPRUCE : TreeBuilder.Species.DARK_OAK;
            case SUBTROPICAL -> roll < 50 ? TreeBuilder.Species.OAK : roll < 70 ? TreeBuilder.Species.PINE : roll < 85 ? TreeBuilder.Species.JUNGLE : TreeBuilder.Species.CHERRY;
            case ARID -> TreeBuilder.Species.ACACIA;
            case SAVANNA -> roll < 80 ? TreeBuilder.Species.ACACIA : TreeBuilder.Species.JUNGLE;
            case TROPICAL_HUMID -> roll < 70 ? TreeBuilder.Species.JUNGLE : TreeBuilder.Species.PALM;
        };
    }

    // ------------------------------------------------------------------ point / line decor

    private boolean placeDecor(WorldGenLevel level, RegionRaster r, int idx, int x, int surfaceY, int z, DecorType type,
                               BlockState ground, BiomeClassifier.Climate climate, TreeBuilder.Sink treeSink) {
        int y = surfaceY + 1;
        int data = r.decorData[idx] & 0xFF;
        switch (type) {
            case TREE, TREE_ROW -> {
                if (!cfg.generateTrees || !isPlantable(ground)) return false;
                TreeBuilder.Species species = TreeBuilder.fromCode(data & 0x0F);
                if (species == null) species = pickSpecies(r.landCoverAt(idx) == LandCover.NONE ? LandCover.PARK : r.landCoverAt(idx), climate, x, z);
                if (species == null) return false;
                int hint = (data >> 4) * 2;
                TreeBuilder.place(treeSink, x, y, z, species, hint, ColumnPainter.hash(x, z, 0x7EE5), cfg.autumnColours);
                if (cfg.autumnColours && TreeBuilder.isBroadleaf(species)) litterRing(level, x, surfaceY, z, ColumnPainter.hash(x, z, 0x11FE));
                return true;
            }
            case BUSH -> {
                if (!isPlantable(ground)) return false;
                TreeBuilder.place(treeSink, x, y, z, TreeBuilder.Species.BUSH, 0, ColumnPainter.hash(x, z, 0xB05), cfg.autumnColours);
                return true;
            }
            case STREET_SIGN -> {
                // A metal post two blocks high and the name on a sign on top, along the street, both sides written.
                String name = r.labels.get(idx);
                if (name == null || y + 2 >= cfg.maxY()) return false;
                // Only where nothing stands yet (a station's name sign, a lamp, a fence): the post would replace it.
                for (int i = 0; i <= 2; i++) {
                    BlockState there = level.getBlockState(new BlockPos(x, y + i, z));
                    if (!there.isAir() && !there.canBeReplaced()) return false;
                }
                for (int i = 0; i < 2; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                BlockPos at = new BlockPos(x, y + 2, z);
                level.setBlock(at, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, data & 15),
                        Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
                if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity be) Signage.writeBothSides(be, name);
                return true;
            }
            case LAMP -> {
                for (int i = 0; i < 4; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                set(level, x, y + 4, z, Blocks.LANTERN.defaultBlockState());
                return true;
            }
            case TRAFFIC_SIGNAL -> {
                for (int i = 0; i < 3; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                set(level, x, y + 3, z, Blocks.CONCRETE.black().defaultBlockState());
                set(level, x, y + 4, z, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true));
                return true;
            }
            case CROSSING -> {
                paintCrossing(level, r, idx, x, surfaceY, z);
                return true;
            }
            case BUS_STOP -> {
                set(level, x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                set(level, x, y + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                set(level, x, y + 2, z, Blocks.CONCRETE.blue().defaultBlockState());
                return true;
            }
            case BENCH -> {
                Direction facing = Direction.from2DDataValue((int) (ColumnPainter.hash(x, z, 0xBE) % 4));
                set(level, x, y, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
                return true;
            }
            case FOUNTAIN -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(level, x + dx, surfaceY, z + dz, Blocks.SMOOTH_STONE.defaultBlockState());
                        set(level, x + dx, y, z + dz, (dx == 0 && dz == 0) ? Blocks.WATER.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
                set(level, x, y + 1, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                set(level, x, y + 2, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                return true;
            }
            case WASTE_BASKET -> {
                set(level, x, y, z, Blocks.CAULDRON.defaultBlockState());
                return true;
            }
            case SHELTER -> {
                for (int dx = -1; dx <= 1; dx += 2) {
                    for (int dz = -1; dz <= 1; dz += 2) {
                        for (int i = 0; i < 3; i++) set(level, x + dx, y + i, z + dz, Blocks.SPRUCE_FENCE.defaultBlockState());
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) set(level, x + dx, y + 3, z + dz, Blocks.SPRUCE_SLAB.defaultBlockState());
                }
                return true;
            }
            case POWER_TOWER -> {
                int height = 24;
                for (int i = 0; i < height - 4; i++) {
                    for (int dx = -1; dx <= 1; dx += 2) {
                        for (int dz = -1; dz <= 1; dz += 2) set(level, x + dx, y + i, z + dz, Blocks.IRON_BARS.defaultBlockState());
                    }
                    if (i % 6 == 3) {
                        for (int dx = -1; dx <= 1; dx++) {
                            set(level, x + dx, y + i, z - 1, Blocks.IRON_BARS.defaultBlockState());
                            set(level, x + dx, y + i, z + 1, Blocks.IRON_BARS.defaultBlockState());
                        }
                    }
                }
                for (int i = height - 4; i <= height; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                for (int dx = -3; dx <= 3; dx++) set(level, x + dx, y + height - 3, z, Blocks.IRON_BARS.defaultBlockState());
                for (int dx = -2; dx <= 2; dx++) set(level, x + dx, y + height, z, Blocks.IRON_BARS.defaultBlockState());
                return true;
            }
            case POWER_POLE -> {
                for (int i = 0; i < 8; i++) set(level, x, y + i, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                set(level, x - 1, y + 7, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                set(level, x + 1, y + 7, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                return true;
            }
            case FLAGPOLE -> {
                for (int i = 0; i < 9; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                set(level, x + 1, y + 8, z, Blocks.WOOL.red().defaultBlockState());
                return true;
            }
            case MAST -> {
                for (int i = 0; i < 26; i++) set(level, x, y + i, z, Blocks.IRON_BARS.defaultBlockState());
                set(level, x, y + 26, z, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true));
                return true;
            }
            case LIGHTHOUSE -> {
                int height = 14;
                for (int i = 0; i < height; i++) {
                    BlockState band = (i / 3) % 2 == 0 ? Blocks.CONCRETE.white().defaultBlockState() : Blocks.CONCRETE.red().defaultBlockState();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) set(level, x + dx, y + i, z + dz, band);
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(level, x + dx, y + height, z + dz, (dx == 0 && dz == 0) ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.GLASS.defaultBlockState());
                        set(level, x + dx, y + height + 1, z + dz, Blocks.CONCRETE.red().defaultBlockState());
                    }
                }
                return true;
            }
            case BOLLARD -> {
                set(level, x, y, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                return true;
            }
            case FENCE -> {
                BlockState fence = switch (data) {
                    case 1 -> Blocks.IRON_BARS.defaultBlockState();
                    case 2 -> Blocks.STONE_BRICK_WALL.defaultBlockState();
                    default -> climate.isCold() ? Blocks.SPRUCE_FENCE.defaultBlockState() : Blocks.OAK_FENCE.defaultBlockState();
                };
                set(level, x, y, z, fence);
                return true;
            }
            case WALL -> {
                BlockState wall = switch (data) {
                    case 1 -> Blocks.BRICK_WALL.defaultBlockState();
                    case 2 -> Blocks.STONE_BRICK_WALL.defaultBlockState();
                    default -> Blocks.COBBLESTONE_WALL.defaultBlockState();
                };
                set(level, x, y, z, wall);
                return true;
            }
            case HEDGE -> {
                BlockState leaves = ColumnPainter.persistentLeaves(Blocks.OAK_LEAVES.defaultBlockState());
                set(level, x, y, z, leaves);
                if (ColumnPainter.hash(x, z, 0x4E) % 100 < 30) set(level, x, y + 1, z, leaves);
                return true;
            }
            case RETAINING_WALL -> {
                set(level, x, y, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                return true;
            }
            case CITY_WALL -> {
                for (int i = 0; i < 4; i++) set(level, x, y + i, z, Blocks.STONE_BRICKS.defaultBlockState());
                if (((x + z) & 1) == 0) set(level, x, y + 4, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                return true;
            }
            case GUARD_RAIL -> {
                set(level, x, y, z, Blocks.IRON_BARS.defaultBlockState());
                return true;
            }
            case CROSS -> {
                set(level, x, y, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                set(level, x, y + 1, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Street furniture whose OSM node sits on the carriageway (common with
     * imprecise mapping) is nudged sideways onto the sidewalk / verge.
     * Returns the new column, or null when no move is needed.
     */
    private static int[] shiftOffRoadway(RegionRaster r, int idx, int x, int z, DecorType type) {
        switch (type) {
            case LAMP, BENCH, BUS_STOP, WASTE_BASKET, BOLLARD, TRAFFIC_SIGNAL, POWER_POLE, FLAGPOLE, SHELTER -> { }
            default -> {
                return null;
            }
        }
        var rf = r.roadAt(idx);
        if (rf == null || r.roadDistAt(idx) > rf.halfWidth) return null;
        int octant = r.roadDirAt(idx);
        int perpX, perpZ;
        switch (octant) {
            case 0, 4 -> { perpX = 0; perpZ = 1; }
            case 2, 6 -> { perpX = 1; perpZ = 0; }
            case 1, 5 -> { perpX = 1; perpZ = -1; }
            default -> { perpX = 1; perpZ = 1; }
        }
        for (int sign = 1; sign >= -1; sign -= 2) {
            for (int step = 1; step <= rf.halfWidth + 3; step++) {
                int nx = x + perpX * step * sign, nz = z + perpZ * step * sign;
                if (Math.abs(nx - x) > 15 || Math.abs(nz - z) > 15) break;
                int nidx = r.index(nx, nz);
                if (nidx < 0) break;
                if (r.building[nidx] != 0 || r.water[nidx] != 0) break;
                var nrf = r.roadAt(nidx);
                if (nrf == null || r.roadDistAt(nidx) > nrf.halfWidth) return new int[]{nx, nz};
            }
        }
        return null;
    }

    /** Zebra stripes: bars parallel to the road, spaced across its width. */
    private void paintCrossing(WorldGenLevel level, RegionRaster r, int idx, int x, int surfaceY, int z) {
        if (r.road[idx] == 0) return;
        int octant = r.roadDirAt(idx);
        int dirX, dirZ;
        switch (octant) {
            case 0, 4 -> { dirX = 1; dirZ = 0; }
            case 2, 6 -> { dirX = 0; dirZ = 1; }
            case 1, 5 -> { dirX = 1; dirZ = 1; }
            default -> { dirX = 1; dirZ = -1; }
        }
        int perpX = -dirZ, perpZ = dirX;
        int half = r.roadAt(idx).halfWidth;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = -half; n <= half; n += 2) {
            for (int m = -1; m <= 1; m++) {
                int cx = x + n * perpX + m * dirX, cz = z + n * perpZ + m * dirZ;
                if (Math.abs(cx - x) > 16 || Math.abs(cz - z) > 16) continue;
                int cy = level.getHeight(Heightmap.Types.OCEAN_FLOOR, cx, cz) - 1;
                if (Math.abs(cy - surfaceY) > 2) continue;
                pos.set(cx, cy, cz);
                BlockState existing = level.getBlockState(pos);
                if (existing.is(Blocks.CONCRETE.gray()) || existing.is(Blocks.CONCRETE.lightGray()) || existing.is(Blocks.CONCRETE.white())) {
                    level.setBlock(pos, Blocks.CONCRETE.white().defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private void set(WorldGenLevel level, int x, int y, int z, BlockState state) {
        if (y <= cfg.minY || y >= cfg.maxY()) return;
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
    }
}
