package com.berg.orbis.render;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.DecorType;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import com.berg.orbis.feature.RoofShape;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.imagery.GroundClass;
import com.berg.orbis.sky.SnowCover;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Writes one complete block column: bedrock, rock, soil, the real ground
 * cover, water bodies with proper beds, roads with markings and sidewalks,
 * bridges, tunnels, and buildings with hollow interiors, windows, doors and
 * shaped roofs. Everything is deterministic in (x, z) so chunk borders never
 * show seams.
 */
public final class ColumnPainter {

    @FunctionalInterface
    public interface Sink {
        void set(int x, int y, int z, BlockState state);

        /**
         * The same block from y0 to y1 inclusive (nothing when y0 > y1). The generator's sink writes such a run
         * straight into the chunk sections and touches the heightmaps once, at the top: the ~330 blocks of rock
         * under every column were the bulk of all block writes and went one by one through the general setter.
         */
        default void fill(int x, int y0, int y1, int z, BlockState state) {
            for (int y = y0; y <= y1; y++) set(x, y, z, state);
        }
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState GRASS_SNOWY = Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState SNOW_LAYER = Blocks.SNOW.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);

    /** Depth the sea bed shelves down to from the shore where the elevation data has none, in metres. */
    private static final double SHELF_M = 12;

    private final OrbisConfig cfg;
    private final int minY;
    private final int maxY;
    private final int seaLevel;

    public ColumnPainter(OrbisConfig cfg) {
        this.cfg = cfg;
        this.minY = cfg.minY;
        this.maxY = cfg.maxY();
        this.seaLevel = cfg.waterLevelY(); // the sea's surface: in a window world a shallow sea on the floor
    }

    /**
     * @param terrainY   smoothed real elevation for this column (block Y of the natural ground surface)
     * @param r          region raster containing the column, or null when OSM data is unavailable
     * @param idx        raster index of the column (ignored when r == null)
     */
    public void paint(Sink s, int x, int z, int terrainY, BiomeClassifier.Climate climate, RegionRaster r, int idx) {
        paint(s, x, z, terrainY, climate, r, idx, seaLevel - 60);
    }

    /**
     * As above, with deepslate from {@code deepslateTop} down (layout 2: the underground band's vanilla Y 0, with
     * vanilla's ragged eight-block transition; before, 60 blocks under sea level everywhere).
     */
    public void paint(Sink s, int x, int z, int terrainY, BiomeClassifier.Climate climate, RegionRaster r, int idx, int deepslateTop) {
        paint(s, x, z, terrainY, climate, r, idx, deepslateTop, null);
    }

    /** As above, with the land cover to paint where there is no raster (the far view's, from ESA WorldCover), or null. */
    public void paint(Sink s, int x, int z, int terrainY, BiomeClassifier.Climate climate, RegionRaster r, int idx, int deepslateTop, LandCover noRasterCover) {
        terrainY = clampY(terrainY);
        boolean hasRaster = r != null && idx >= 0;
        LandCover lc = hasRaster ? r.landCoverAt(idx) : noRasterCover != null ? noRasterCover : LandCover.NONE;
        GroundClass gc = hasRaster ? GroundClass.byCode(r.groundClassAt(idx)) : GroundClass.UNKNOWN;
        WaterFeature wf = hasRaster ? r.waterAt(idx) : null;
        RoadFeature rf = hasRaster ? r.roadAt(idx) : null;
        BuildingFeature bf = hasRaster ? r.buildingAt(idx) : null;
        DecorType dec = hasRaster ? r.decorAt(idx) : DecorType.NONE;

        // ---- side slopes of a graded road: the ground blends from the road height back to its own ------------
        if (hasRaster && r.shoulderDist != null && rf == null && bf == null && wf == null) {
            int k = r.shoulderDist[idx];
            if (k > 0 && !(r.hasCoastline && r.isSea(idx))) {
                int road = r.shoulderY[idx];
                int blended = (int) Math.round(road + (terrainY - road) * (k / (double) (RegionRaster.SHOULDER + 1)));
                if (!(terrainY >= seaLevel && blended < seaLevel)) terrainY = clampY(blended);
            }
        }

        boolean sea;
        if (hasRaster && r.hasCoastline) {
            sea = r.isSea(idx);
            // Coarse worlds: skerries, spits and harbour walls are smaller than a block, so the coastline flood
            // fill mislabels thin strips (grass lines zigzagging across the sea, water trenches along shores).
            // There the bathymetry has the final say whenever it clearly disagrees.
            if (cfg.metersPerBlock >= 8.0 && cfg.seaFromElevation) {
                if (!sea && terrainY < seaLevel - 1) sea = true;
                else if (sea && terrainY > seaLevel + 1) sea = false;
            }
        } else {
            sea = cfg.seaFromElevation && terrainY < seaLevel;
        }
        if (!cfg.generateWater) {
            sea = false;
            wf = null;
        }

        // ---- doorstep: the ground in front of a door meets the door sill ---------
        // The walls start at the lowest corner of the footprint, so on a slope the natural ground in front of
        // the door can be several blocks above (or below) the sill. The three cells outside the door become
        // a stair of one block per cell, so residents can walk in and out.
        boolean doorstep = false;
        if (bf == null && hasRaster && cfg.buildingDoors && !sea && wf == null) {
            int step = doorstepY(r, x, z, terrainY);
            if (step != Integer.MIN_VALUE) {
                terrainY = clampY(step);
                doorstep = true;
            }
        }

        // ---- water levels ----------------------------------------------------
        int waterTop = Integer.MIN_VALUE;
        int groundTop = terrainY;
        if (bf == null && (sea || wf != null)) {
            if (sea || wf.atSeaLevel || (wf.surfaceY == WaterFeature.FOLLOW_TERRAIN && terrainY <= seaLevel)) {
                waterTop = seaLevel;
                int depth = wf != null ? wf.depth : 6;
                int shore = hasRaster && r.seaShore != null ? r.seaShore[idx] : 0;
                if (shore > 0) {
                    // The elevation data records the sea surface as 0 m and blends into the bathymetry over tens of
                    // metres, so near the shore it knows no depth. The bed shelves down from the shore (a block per
                    // three blocks out) to about 12 m and follows the bathymetry wherever that is deeper: no trench
                    // along the shore and no step up where the bathymetry begins.
                    int cap = Math.min(depth, Math.max(2, (int) Math.round(SHELF_M / cfg.metersPerBlock)));
                    groundTop = Math.min(terrainY, seaLevel - Math.min(cap, 1 + (shore - 1) / 3));
                } else {
                    groundTop = terrainY < seaLevel ? terrainY : Math.min(terrainY, seaLevel - depth);
                }
            } else if (wf.surfaceY != WaterFeature.FOLLOW_TERRAIN) {
                // A shaped bed (a surveyed lake, a bowl, a river channel) where there is one, else the body's own depth.
                int bed = hasRaster ? r.bedDepthAt(idx) : 0;
                waterTop = clampY(wf.surfaceY);
                groundTop = Math.min(terrainY, waterTop - (bed > 0 ? bed : wf.depth));
            } else {
                int bed = hasRaster ? r.bedDepthAt(idx) : 0;
                waterTop = terrainY - 1;
                groundTop = waterTop - (bed > 0 ? bed : wf.depth);
            }
            if (groundTop >= waterTop) groundTop = waterTop - 1;
            groundTop = Math.max(minY + 1, groundTop);
        }

        // ---- buildings pin the ground to their base -----------------------------
        if (bf != null) {
            waterTop = Integer.MIN_VALUE;
            groundTop = clampY(bf.baseY);
        }

        // ---- road level (tunnels / bridges carry their own deck height) ---------
        int deckY = Integer.MIN_VALUE;
        boolean roadOnGround = false;
        if (rf != null && rf.tunnel && rf.layer < 0) {
            // A real tunnel is carved under whatever is above it: open ground, a street, a building's floor or a
            // lake bed. Where it comes within 3 blocks of that cover (the portal approaches) it is a road in the
            // open instead.
            int explicit = r.roadY[idx];
            deckY = explicit == RegionRaster.NO_Y ? groundTop : explicit;
            if (groundTop - deckY < 3) {
                deckY = Integer.MIN_VALUE;
                if (bf == null && waterTop == Integer.MIN_VALUE) roadOnGround = true;
            }
        } else if (rf != null && bf == null) {
            int explicit = r.roadY[idx];
            if (rf.tunnel) {
                // A covered passage: street level.
                deckY = explicit == RegionRaster.NO_Y ? terrainY : explicit;
                if (terrainY - deckY < 3) {
                    roadOnGround = true;
                    deckY = Integer.MIN_VALUE;
                }
            } else if (rf.bridge || rf.kind == RoadFeature.Kind.PIER) {
                deckY = explicit == RegionRaster.NO_Y ? terrainY + 1 : explicit;
                int floor = waterTop != Integer.MIN_VALUE ? waterTop : groundTop;
                if (deckY <= floor) {
                    if (waterTop != Integer.MIN_VALUE) {
                        deckY = waterTop + 1; // causeway through water
                    } else {
                        roadOnGround = true;
                        deckY = Integer.MIN_VALUE;
                    }
                }
            } else {
                roadOnGround = true;
                if (explicit != RegionRaster.NO_Y && explicit > groundTop) {
                    // Approach ramp up to a bridge deck: an embankment.
                    groundTop = explicit;
                    waterTop = Integer.MIN_VALUE;
                    lc = LandCover.NONE;
                } else if (explicit != RegionRaster.NO_Y && explicit < groundTop && waterTop == Integer.MIN_VALUE) {
                    // Approach down to a sunken tunnel portal: a cutting.
                    groundTop = clampY(explicit);
                } else if (waterTop != Integer.MIN_VALUE) {
                    // A road drawn through water without a bridge tag: build a causeway.
                    groundTop = waterTop + 1;
                    waterTop = Integer.MIN_VALUE;
                    lc = LandCover.NONE;
                }
            }
        }
        if (deckY != Integer.MIN_VALUE) deckY = clampY(deckY);

        // ---- solid column ------------------------------------------------------
        long h = hash(x, z, 0x51);
        boolean waterColumn = waterTop != Integer.MIN_VALUE;
        // The street running under a flyover keeps its surface (and, if it is a
        // lower bridge of a stacked interchange, its own thin deck).
        RoadFeature under = null;
        if (!waterColumn && (bf != null || deckY != Integer.MIN_VALUE || roadOnGround)) {
            under = r.roadUnderAt(idx);
            // Only a tunnel can run under a tunnel or under a building.
            if (under != null && !under.tunnel && (bf != null || rf.tunnel)) under = null;
        }
        int underDeckY = Integer.MIN_VALUE;
        boolean underRoad = false;
        RoadFeature tunnelBelow = null;
        int tunnelBelowY = RegionRaster.NO_Y;
        if (under != null) {
            int uy = r.roadUnderYAt(idx);
            if (under.tunnel) {
                // A tunnel running under this street / under this bridge's ground: carved after the column is built.
                tunnelBelow = under;
                tunnelBelowY = uy;
            } else if (roadOnGround) {
                // (a ground road never has a non-tunnel road stored below it)
            } else if (under.bridge && uy != RegionRaster.NO_Y && uy > groundTop + 1) {
                underDeckY = clampY(uy);
            } else {
                if (uy != RegionRaster.NO_Y && uy > groundTop) groundTop = clampY(uy);
                underRoad = true;
            }
        }
        BlockState top, sub, deep;
        // The real rock under this column (bedrock map), else plain stone.
        BlockState rock = hasRaster ? com.berg.orbis.geology.Rocks.Rock.of(r.rockAt(idx)).block : STONE;
        if (bf != null) {
            top = bf.minHeightBlocks > 0 ? groundBlock(lc, gc, climate, h, x, z) : bf.floor;
            sub = rock;
            deep = rock;
        } else if (waterColumn) {
            top = bedBlock(wf, sea, groundTop, h);
            sub = top.is(Blocks.SAND) ? SAND : GRAVEL;
            deep = rock;
        } else if (roadOnGround) {
            top = roadSurface(rf, r, idx, x, z);
            sub = rf.isRail() ? GRAVEL : (rf.kind == RoadFeature.Kind.PATH || rf.kind == RoadFeature.Kind.TRACK ? DIRT : GRAVEL);
            deep = rock;
        } else if (underRoad) {
            top = plainSurface(under, r.roadUnderDistAt(idx));
            sub = GRAVEL;
            deep = rock;
        } else {
            top = groundBlock(lc, gc, climate, h, x, z);
            // Sports pitches get their white touchline where the pitch polygon ends.
            if ((lc == LandCover.PITCH || lc == LandCover.PITCH_ARTIFICIAL || lc == LandCover.PITCH_HARD) && coverEdge(r, lc, x, z)) {
                top = Blocks.CONCRETE.white().defaultBlockState();
            }
            sub = subBlock(top, lc, climate);
            deep = rock;
            // Bare rock, screes and outcrops show the rock they are made of.
            if (top == STONE) top = rock;
            if (sub == STONE) sub = rock;
        }

        if (cfg.generateBedrock) s.set(x, minY, z, BEDROCK);
        int rockTop = groundTop - 4;
        // Deepslate below deepslateTop, stone above it, as two runs.
        s.fill(x, minY + 1, Math.min(rockTop, deepslateTop - 1), z, DEEPSLATE);
        s.fill(x, Math.max(minY + 1, deepslateTop), rockTop, z, deep);
        s.fill(x, Math.max(minY + 1, groundTop - 3), groundTop - 1, z, sub);
        // Layout 2: sandstone under sand, as vanilla has it. Buried treasure looks down from the surface for the
        // first block standing on sandstone or stone and puts its chest there; over a region's own rock (tuff,
        // basalt, ...) it found none and its chest ended up deep in the deepslate or nowhere.
        if (cfg.undergroundVersion >= 2 && top.is(Blocks.SAND) && rockTop > minY) s.set(x, rockTop, z, SANDSTONE);
        if (groundTop > minY) s.set(x, groundTop, z, top);

        // ---- water ---------------------------------------------------------------
        if (waterColumn) {
            s.fill(x, groundTop + 1, waterTop, z, WATER);
            // Seagrass and kelp come from the water biomes in the decoration step (SeaVegetation).
            if (climate.frozenWater() && waterTop < maxY) {
                s.set(x, waterTop, z, ICE);
            }
            if (!climate.frozenWater() && wf != null && (wf.kind == WaterFeature.Kind.POND || wf.kind == WaterFeature.Kind.WETLAND_WATER)
                    && (h % 100) < 12 && waterTop < maxY) {
                s.set(x, waterTop + 1, z, Blocks.LILY_PAD.defaultBlockState());
            }
        }

        // ---- bridges / piers / tunnels ---------------------------------------------
        if (tunnelBelow != null && tunnelBelowY != RegionRaster.NO_Y && groundTop - tunnelBelowY >= 3) {
            int ty = clampY(tunnelBelowY);
            int ceiling = Math.min(groundTop - 1, ty + 4);
            for (int y = ty + 1; y <= ceiling; y++) s.set(x, y, z, AIR);
            s.set(x, ty, z, plainSurface(tunnelBelow, r.roadUnderDistAt(idx)));
            if (ty - 1 > minY) s.set(x, ty - 1, z, GRAVEL);
            lineTunnelRoof(s, x, z, ceiling + 1, groundTop, top);
        }
        if (underDeckY != Integer.MIN_VALUE && underDeckY < deckY - 1) {
            // Lower deck of a stacked interchange.
            if (underDeckY - 1 > groundTop) s.set(x, underDeckY - 1, z, Blocks.STONE_BRICKS.defaultBlockState());
            s.set(x, underDeckY, z, plainSurface(under, r.roadUnderDistAt(idx)));
        }
        if (deckY != Integer.MIN_VALUE) {
            if (rf.tunnel) {
                int ceiling = Math.min(groundTop - 1, deckY + 4);
                for (int y = deckY + 1; y <= ceiling; y++) s.set(x, y, z, AIR);
                s.set(x, deckY, z, roadSurface(rf, r, idx, x, z));
                if (deckY - 1 > minY) s.set(x, deckY - 1, z, GRAVEL);
                lineTunnelRoof(s, x, z, ceiling + 1, groundTop, top);
            } else {
                boolean pier = rf.kind == RoadFeature.Kind.PIER;
                BlockState deckBase = pier ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                        : rf.isRail() ? Blocks.STONE_BRICKS.defaultBlockState()
                        : rf.kind == RoadFeature.Kind.PATH || rf.kind == RoadFeature.Kind.STEPS ? Blocks.OAK_PLANKS.defaultBlockState()
                        : Blocks.STONE_BRICKS.defaultBlockState();
                int support = waterColumn ? waterTop : groundTop;
                if (deckY - 1 > support) s.set(x, deckY - 1, z, deckBase);
                s.set(x, deckY, z, roadSurface(rf, r, idx, x, z));
                int outer = rf.halfWidth + (rf.sidewalk ? 1 : 0);
                int dist = r.roadDistAt(idx);
                if (dist >= outer && outer > 0 && deckY + 1 < maxY) {
                    BlockState rail = pier ? Blocks.SPRUCE_FENCE.defaultBlockState()
                            : rf.isRail() ? Blocks.IRON_BARS.defaultBlockState()
                            : rf.kind == RoadFeature.Kind.PATH ? Blocks.OAK_FENCE.defaultBlockState()
                            : Blocks.STONE_BRICK_WALL.defaultBlockState();
                    s.set(x, deckY + 1, z, rail);
                }
                // Pier posts every 4 blocks; bridge pillars where the rasteriser flagged them.
                boolean post = pier ? ((x & 3) == 0 && (z & 3) == 0) : dec == DecorType.BRIDGE_PILLAR;
                if (post && deckY - 2 > support) {
                    BlockState pillar = pier ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
                    for (int y = support + 1; y <= deckY - 2; y++) s.set(x, y, z, pillar);
                    if (waterColumn) for (int y = groundTop + 1; y <= waterTop; y++) s.set(x, y, z, pillar);
                }
            }
        }

        // ---- building ----------------------------------------------------------------
        if (bf != null) {
            paintBuilding(s, x, z, bf, r, idx, climate);
            boolean railThrough = rf != null && rf.isRail() && !rf.tunnel && rf.layer >= 0;
            if ((rf != null && rf.tunnel && rf.layer >= 0) || railThrough || dec == DecorType.EMBEDDED_RAIL) {
                // A passage through the building (tunnel=building_passage / covered), or a railway running into
                // a station hall: the ground floor stays open along it and the track carries on inside.
                s.set(x, groundTop, z, roadSurface(rf != null ? rf : r.roadAt(idx), r, idx, x, z));
                for (int y = groundTop + 1; y <= Math.min(maxY - 1, groundTop + 4); y++) s.set(x, y, z, AIR);
                if (groundTop + 1 < maxY) {
                    if (railThrough && r.roadDistAt(idx) == 0) placeRail(s, r, idx, x, groundTop + 1, z, false);
                    else if (dec == DecorType.EMBEDDED_RAIL) placeRail(s, r, idx, x, groundTop + 1, z, true);
                }
            }
            return;
        }

        // ---- surface details: snow, plants, crops ------------------------------------
        if (!waterColumn && !roadOnGround && !underRoad && groundTop + 1 < maxY) {
            if (gc == GroundClass.TREE_CANOPY && !lc.isForest()) lc = LandCover.FOREST;
            else if ((gc == GroundClass.PAVED_DARK || gc == GroundClass.PAVED_LIGHT || gc == GroundClass.ROCK) && lc == LandCover.NONE) lc = LandCover.PEDESTRIAN;
            decorateGround(s, x, z, groundTop, lc, climate, top, h, doorstep);
        } else if (roadOnGround && snowLayers(climate) > 0 && groundTop + 1 < maxY && !rf.isRail()) {
            s.set(x, groundTop + 1, z, SNOW_LAYER);
        }
        boolean railHere = rf != null && rf.isRail() && r.roadDistAt(idx) == 0;
        // A tram line inside a street: the street painted the surface, the rail still runs on it.
        boolean embedded = !railHere && roadOnGround && dec == DecorType.EMBEDDED_RAIL;
        if (railHere || embedded) {
            // The rail runs on the ground, on a bridge deck or on a tunnel floor. With transit lines on, every
            // tenth block of the line is a powered rail over a redstone block, so a cart keeps its speed
            // along the real line; the decorator later turns corners and slopes into curves and ascending rails.
            int railY = Integer.MIN_VALUE;
            if (roadOnGround && groundTop + 1 < maxY) railY = groundTop + 1;
            else if (deckY != Integer.MIN_VALUE && deckY + 1 < maxY) railY = deckY + 1;
            if (railY != Integer.MIN_VALUE) placeRail(s, r, idx, x, railY, z, embedded);
        }
    }

    /** The rail block of a centreline cell: a powered rail over a redstone block on the marked spots, else a plain rail. */
    private void placeRail(Sink s, RegionRaster r, int idx, int x, int railY, int z, boolean embedded) {
        int octant = embedded ? (r.decorData[idx] & 7) : r.roadDirAt(idx);
        boolean poweredSpot = embedded ? (r.decorData[idx] & 8) != 0 : r.roadTAt(idx) % 10 == 0;
        if (cfg.transitLines && poweredSpot) {
            // On a diagonal run the transit pass keeps this powered only where the cell ends up straight (a
            // powered rail cannot curve); the staircase it builds leaves the line's own cells straight.
            RailShape shape = (octant & 1) == 0 ? ((octant & 2) == 0 ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                    : (((x + z) & 1) == 0 ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
            s.set(x, railY - 1, z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            s.set(x, railY, z, Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(PoweredRailBlock.SHAPE, shape)
                    .setValue(PoweredRailBlock.POWERED, true));
        } else {
            s.set(x, railY, z, railFor(octant, x, z));
        }
    }

    private static final BlockState TUNNEL_LINING = Blocks.STONE_BRICKS.defaultBlockState();

    /**
     * The block right above a tunnel's air: a stone-brick lining, never gravel or sand. The subsoil layers
     * under a street or a field are gravel and dirt, and gravel over a void drops into the tunnel at the
     * first block update, which is what used to block them.
     */
    private void lineTunnelRoof(Sink s, int x, int z, int roofY, int groundTop, BlockState surface) {
        if (roofY < groundTop) {
            s.set(x, roofY, z, TUNNEL_LINING);
        } else if (roofY == groundTop && (surface.is(Blocks.GRAVEL) || surface.is(Blocks.SAND) || surface.is(Blocks.RED_SAND))) {
            s.set(x, roofY, z, STONE);
        }
    }

    // ------------------------------------------------------------------ ground

    private BlockState groundBlock(LandCover lc, GroundClass gc, BiomeClassifier.Climate climate, long h, int x, int z) {
        int r = (int) (h % 100);
        BiomeClassifier.Zone zone = climate.zone();
        // What the orthophoto actually shows beats a zoning polygon.
        if (gc != GroundClass.UNKNOWN) {
            switch (gc) {
                case GRASS -> {
                    return zone == BiomeClassifier.Zone.ARID && r < 40 ? Blocks.COARSE_DIRT.defaultBlockState() : GRASS;
                }
                case TREE_CANOPY -> {
                    return r < 75 ? GRASS : r < 92 ? Blocks.PODZOL.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState();
                }
                case PAVED_LIGHT -> {
                    return r < 80 ? Blocks.CONCRETE.lightGray().defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
                }
                case PAVED_DARK -> {
                    return Blocks.CONCRETE.gray().defaultBlockState();
                }
                case BARE_SOIL -> {
                    return r < 70 ? Blocks.COARSE_DIRT.defaultBlockState() : r < 90 ? DIRT : Blocks.ROOTED_DIRT.defaultBlockState();
                }
                case SAND -> {
                    return SAND;
                }
                case ROCK -> {
                    return r < 70 ? STONE : r < 90 ? Blocks.COBBLESTONE.defaultBlockState() : GRAVEL;
                }
                case SNOW -> {
                    return SNOW_BLOCK;
                }
                default -> { }
            }
        }
        switch (lc) {
            case NONE -> {
                return switch (zone) {
                    case ARID -> r < 88 ? SAND : r < 96 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
                    case ICE_CAP -> SNOW_BLOCK;
                    case ALPINE -> r < 55 ? STONE : r < 80 ? GRAVEL : r < 92 ? Blocks.COARSE_DIRT.defaultBlockState() : GRASS;
                    case TUNDRA -> r < 60 ? GRASS : r < 78 ? Blocks.COARSE_DIRT.defaultBlockState() : r < 90 ? GRAVEL : Blocks.PODZOL.defaultBlockState();
                    case SAVANNA -> r < 90 ? GRASS : Blocks.COARSE_DIRT.defaultBlockState();
                    default -> GRASS;
                };
            }
            case FOREST_CONIFER -> {
                return r < 55 ? GRASS : r < 88 ? Blocks.PODZOL.defaultBlockState() : r < 95 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
            }
            case FOREST, FOREST_BROADLEAF -> {
                return r < 80 ? GRASS : r < 92 ? Blocks.PODZOL.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState();
            }
            case SCRUB, HEATH -> {
                if (zone == BiomeClassifier.Zone.ARID) return r < 60 ? SAND : Blocks.COARSE_DIRT.defaultBlockState();
                return r < 68 ? GRASS : r < 85 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState();
            }
            case FARMLAND -> {
                return (z % 7 == 0) ? Blocks.DIRT_PATH.defaultBlockState() : FARMLAND;
            }
            case ORCHARD, VINEYARD, ALLOTMENTS, GRASS, MEADOW, PARK, GARDEN, RESIDENTIAL, COMMERCIAL, RETAIL, CEMETERY, GOLF,
                 SCHOOL, HOSPITAL, MILITARY, GRASS_SPORT, PITCH -> {
                if (zone == BiomeClassifier.Zone.ARID && lc != LandCover.PARK && lc != LandCover.GARDEN && lc != LandCover.PITCH
                        && lc != LandCover.GOLF && lc != LandCover.GRASS_SPORT) {
                    return r < 70 ? SAND : Blocks.COARSE_DIRT.defaultBlockState();
                }
                return GRASS;
            }
            case INDUSTRIAL, GRAVEL_AREA, RAILWAY_LAND -> {
                return r < 85 ? GRAVEL : Blocks.COARSE_DIRT.defaultBlockState();
            }
            case QUARRY -> {
                return r < 60 ? STONE : r < 85 ? GRAVEL : Blocks.COBBLESTONE.defaultBlockState();
            }
            case PARKING -> {
                boolean stripe = (x % 6 == 0) && ((Math.floorMod(z, 12)) < 5);
                return stripe ? Blocks.CONCRETE.white().defaultBlockState() : Blocks.CONCRETE.gray().defaultBlockState();
            }
            case PITCH_ARTIFICIAL -> {
                return Blocks.MOSS_BLOCK.defaultBlockState();
            }
            case PITCH_HARD -> {
                return Blocks.CONCRETE.gray().defaultBlockState();
            }
            case PLAYGROUND -> {
                return r < 55 ? SAND : GRASS;
            }
            case SAND, BEACH -> {
                return SAND;
            }
            case BARE_ROCK, ROCK_OUTCROP -> {
                return r < 85 ? STONE : Blocks.COBBLESTONE.defaultBlockState();
            }
            case SCREE -> {
                return r < 50 ? GRAVEL : r < 85 ? Blocks.COBBLESTONE.defaultBlockState() : STONE;
            }
            case MUD, SALT_MARSH -> {
                return r < 80 ? Blocks.MUD.defaultBlockState() : GRASS;
            }
            case WETLAND -> {
                return r < 45 ? Blocks.MUD.defaultBlockState() : r < 90 ? GRASS : Blocks.CLAY.defaultBlockState();
            }
            case GLACIER -> {
                return SNOW_BLOCK;
            }
            case CONSTRUCTION, LANDFILL -> {
                return r < 70 ? Blocks.COARSE_DIRT.defaultBlockState() : GRAVEL;
            }
            case PEDESTRIAN, SQUARE, MARKETPLACE -> {
                return r < 82 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
            }
            case PIER, MARINA -> {
                return Blocks.SPRUCE_PLANKS.defaultBlockState();
            }
            case AIRPORT_APRON, RUNWAY -> {
                return Blocks.CONCRETE.lightGray().defaultBlockState();
            }
            case REEF -> {
                return SAND;
            }
            default -> {
                return GRASS;
            }
        }
    }

    /** True when a 4-neighbour of the column has a different land cover (the outline of the polygon). */
    private static boolean coverEdge(RegionRaster r, LandCover lc, int x, int z) {
        byte code = lc.code();
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            int i = r.index(x + o[0], z + o[1]);
            if (i >= 0 && r.landCover[i] != code) return true;
        }
        return false;
    }

    private BlockState subBlock(BlockState top, LandCover lc, BiomeClassifier.Climate climate) {
        if (top.is(Blocks.SAND) || top.is(Blocks.SMOOTH_SANDSTONE)) return climate.isArid() ? SANDSTONE : SAND;
        if (top.is(Blocks.STONE) || top.is(Blocks.COBBLESTONE)) return STONE;
        if (top.is(Blocks.GRAVEL)) return GRAVEL;
        if (top.is(Blocks.SNOW_BLOCK)) return lc == LandCover.GLACIER ? Blocks.PACKED_ICE.defaultBlockState() : STONE;
        if (top.is(Blocks.MUD)) return Blocks.MUD.defaultBlockState();
        if (top.is(Blocks.CONCRETE.gray()) || top.is(Blocks.CONCRETE.lightGray()) || top.is(Blocks.STONE_BRICKS)) return GRAVEL;
        return DIRT;
    }

    private BlockState bedBlock(WaterFeature wf, boolean sea, int bedY, long h) {
        int r = (int) ((h >>> 8) % 100);
        if (wf != null) {
            switch (wf.kind) {
                case SWIMMING_POOL -> {
                    return Blocks.CONCRETE.lightBlue().defaultBlockState();
                }
                case FOUNTAIN_BASIN -> {
                    return Blocks.SMOOTH_STONE.defaultBlockState();
                }
                case RIVER, STREAM, CANAL, DITCH -> {
                    return r < 60 ? GRAVEL : r < 85 ? SAND : Blocks.CLAY.defaultBlockState();
                }
                case LAKE, POND, RESERVOIR, BASIN, WETLAND_WATER -> {
                    return r < 45 ? DIRT : r < 75 ? Blocks.CLAY.defaultBlockState() : r < 92 ? SAND : GRAVEL;
                }
                default -> { }
            }
        }
        if (bedY >= seaLevel - 10) return r < 88 ? SAND : GRAVEL;
        if (bedY >= seaLevel - 60) return r < 60 ? GRAVEL : r < 85 ? SAND : Blocks.CLAY.defaultBlockState();
        return r < 70 ? GRAVEL : STONE;
    }

    /**
     * Snow layers (eight to a block) for a real snow depth: none below 2 cm, at least one above, at most
     * {@link SnowCover#MAX_DEPTH_M}.
     */
    public static int snowLayers(double depthM, double metersPerBlock) {
        if (!(depthM >= 0.02)) return 0;
        return Math.max(1, (int) Math.round(Math.min(depthM, SnowCover.MAX_DEPTH_M) / metersPerBlock * 8));
    }

    /** Snow layers to lay on this column: today's real depth when known, else one layer where the climate is snowy. */
    private int snowLayers(BiomeClassifier.Climate climate) {
        if (Double.isNaN(climate.snowDepthM())) return climate.snowy() ? 1 : 0;
        int layers = snowLayers(climate.snowDepthM(), cfg.metersPerBlock);
        return climate.zone() == BiomeClassifier.Zone.ICE_CAP ? Math.max(1, layers) : layers;
    }

    /** Snow, plants and crops on the ground; on a doorstep only snow (no berry bush or tall flower in the way in). */
    private void decorateGround(Sink s, int x, int z, int groundTop, LandCover lc, BiomeClassifier.Climate climate, BlockState top, long h, boolean doorstep) {
        int y = groundTop + 1;
        int layers = snowLayers(climate);
        if (layers > 0) {
            if (top.is(Blocks.GRASS_BLOCK)) s.set(x, groundTop, z, GRASS_SNOWY);
            if (top.is(Blocks.CONCRETE.gray()) || top.is(Blocks.STONE_BRICKS) || top.is(Blocks.SPRUCE_PLANKS)) return;
            // Deep snow: whole snow blocks, then the remaining layers on top.
            int full = layers / 8;
            for (int i = 0; i < full && y + i < maxY - 1; i++) s.set(x, y + i, z, SNOW_BLOCK);
            int rest = layers % 8;
            if (rest > 0 && y + full < maxY - 1) s.set(x, y + full, z, SNOW_LAYER.setValue(SnowLayerBlock.LAYERS, rest));
            return;
        }
        if (doorstep) return;
        int r = (int) ((h >>> 16) % 1000);
        BiomeClassifier.Zone zone = climate.zone();
        if (lc == LandCover.FARMLAND && top.is(Blocks.FARMLAND)) {
            long field = Materials.mix(((long) Math.floorDiv(x, 40) << 32) ^ (Math.floorDiv(z, 40) & 0xffffffffL));
            int crop = (int) (field % 100);
            if (cfg.autumnColours && crop < 55) {
                // Autumn: the grain is in; stubble fields with a few hay bales left on them.
                if (r < 12) s.set(x, y, z, Blocks.HAY_BLOCK.defaultBlockState());
                return;
            }
            if (cfg.autumnColours && crop >= 90) {
                // Autumn: pumpkin patches where the beetroot would be.
                if (r < 140) s.set(x, y, z, Blocks.PUMPKIN.defaultBlockState());
                return;
            }
            BlockState plant = crop < 55 ? Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7)
                    : crop < 75 ? Blocks.POTATOES.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7)
                    : crop < 90 ? Blocks.CARROTS.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7)
                    : Blocks.BEETROOTS.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3);
            if (r < 920) s.set(x, y, z, plant);
            return;
        }
        if (lc == LandCover.VINEYARD && top.is(Blocks.GRASS_BLOCK)) {
            if (Math.floorMod(x, 3) == 0) s.set(x, y, z, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3));
            return;
        }
        if (!top.is(Blocks.GRASS_BLOCK) && !top.is(Blocks.PODZOL) && !top.is(Blocks.COARSE_DIRT) && !top.is(Blocks.SAND) && !top.is(Blocks.MUD) && !top.is(Blocks.MOSS_BLOCK)) {
            return;
        }
        if (top.is(Blocks.SAND)) {
            if (zone == BiomeClassifier.Zone.ARID || lc == LandCover.SAND) {
                if (r < 8) {
                    int height = 1 + (int) ((h >>> 40) % 3);
                    for (int i = 0; i < height && y + i < maxY; i++) s.set(x, y + i, z, Blocks.CACTUS.defaultBlockState());
                } else if (r < 30) {
                    s.set(x, y, z, Blocks.DEAD_BUSH.defaultBlockState());
                } else if (r < 60) {
                    s.set(x, y, z, Blocks.SHORT_DRY_GRASS.defaultBlockState());
                }
            }
            return;
        }
        if (zone == BiomeClassifier.Zone.ARID) {
            if (r < 30) s.set(x, y, z, Blocks.DEAD_BUSH.defaultBlockState());
            else if (r < 80) s.set(x, y, z, Blocks.SHORT_DRY_GRASS.defaultBlockState());
            return;
        }
        if (zone == BiomeClassifier.Zone.SAVANNA) {
            if (r < 220) s.set(x, y, z, Blocks.SHORT_DRY_GRASS.defaultBlockState());
            else if (r < 280) s.set(x, y, z, Blocks.TALL_DRY_GRASS.defaultBlockState());
            else if (r < 300) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
            return;
        }
        // Flower beds in parks and gardens: 6 x 6 patches, about a fifth of them planted, dense with flowers.
        if ((lc == LandCover.PARK || lc == LandCover.GARDEN) && top.is(Blocks.GRASS_BLOCK)) {
            long patch = Materials.mix(((long) Math.floorDiv(x, 6) << 32) ^ (Math.floorDiv(z, 6) & 0xffffffffL) ^ 0xF10E5L);
            if (patch % 100 < 22) {
                int kind = (int) ((patch >>> 8) % 4);
                if (r < 700) {
                    if (kind == 3 && y + 1 < maxY) {
                        BlockState tall = switch ((int) ((patch >>> 16) % 4)) {
                            case 0 -> Blocks.ROSE_BUSH.defaultBlockState();
                            case 1 -> Blocks.LILAC.defaultBlockState();
                            case 2 -> Blocks.PEONY.defaultBlockState();
                            default -> Blocks.SUNFLOWER.defaultBlockState();
                        };
                        s.set(x, y, z, tall.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
                        s.set(x, y + 1, z, tall.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
                    } else {
                        s.set(x, y, z, kind == 0 ? flower(h) : kind == 1 ? flower(patch) : Blocks.WILDFLOWERS.defaultBlockState());
                    }
                } else if (r < 760) {
                    s.set(x, y, z, Blocks.FLOWERING_AZALEA.defaultBlockState());
                }
                return;
            }
        }
        switch (lc) {
            case FOREST, FOREST_BROADLEAF, FOREST_CONIFER -> {
                boolean fall = cfg.autumnColours && lc != LandCover.FOREST_CONIFER;
                int brown = fall ? 190 : 176; // autumn is mushroom season
                if (r < 70) s.set(x, y, z, Blocks.FERN.defaultBlockState());
                else if (r < 170) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (r < brown) s.set(x, y, z, Blocks.BROWN_MUSHROOM.defaultBlockState());
                else if (r < brown + 3) s.set(x, y, z, Blocks.RED_MUSHROOM.defaultBlockState());
                else if (r < (fall ? 340 : 200) && lc != LandCover.FOREST_CONIFER) s.set(x, y, z, leafLitter(h, fall));
                else if (fall && NewBlocks.RED_SHRUB != null && r < 352) s.set(x, y, z, NewBlocks.RED_SHRUB);
            }
            case MEADOW, GRASS -> {
                if (r < 330) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (r < 390 && y + 1 < maxY) tallGrass(s, x, y, z);
                else if (r < 470) s.set(x, y, z, flower(h));
            }
            case PARK, GARDEN, CEMETERY, GOLF, RESIDENTIAL, SCHOOL, HOSPITAL, ALLOTMENTS, ORCHARD -> {
                if (r < 140) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (r < 175) s.set(x, y, z, flower(h));
            }
            case SCRUB, HEATH -> {
                if (r < 150) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (r < 210) s.set(x, y, z, Blocks.FERN.defaultBlockState());
                else if (r < 250) s.set(x, y, z, Blocks.BUSH.defaultBlockState());
                else if (r < 262) s.set(x, y, z, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(BlockStateProperties.AGE_3, 2));
                else if (r < 275) s.set(x, y, z, Blocks.DEAD_BUSH.defaultBlockState());
                else if (cfg.autumnColours && NewBlocks.RED_SHRUB != null && r < 320) s.set(x, y, z, NewBlocks.RED_SHRUB); // heather turning red
            }
            case WETLAND, SALT_MARSH, MUD -> {
                if (top.is(Blocks.MUD)) {
                    if (r < 60) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                } else if (r < 300) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (r < 380 && y + 1 < maxY) tallGrass(s, x, y, z);
                else if (r < 400) s.set(x, y, z, Blocks.FERN.defaultBlockState());
            }
            case NONE -> {
                switch (zone) {
                    case TUNDRA -> {
                        if (r < 60) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                    }
                    case ALPINE -> {
                        if (r < 30 && top.is(Blocks.GRASS_BLOCK)) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                    }
                    case TAIGA -> {
                        if (r < 120) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                        else if (r < 150) s.set(x, y, z, Blocks.FERN.defaultBlockState());
                    }
                    case TROPICAL_HUMID -> {
                        if (r < 200) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                        else if (r < 260) s.set(x, y, z, Blocks.FERN.defaultBlockState());
                    }
                    default -> {
                        if (r < 200) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
                        else if (r < 225) s.set(x, y, z, flower(h));
                    }
                }
            }
            default -> {
                if (top.is(Blocks.GRASS_BLOCK) && r < 80) s.set(x, y, z, Blocks.SHORT_GRASS.defaultBlockState());
            }
        }
    }

    private static void tallGrass(Sink s, int x, int y, int z) {
        s.set(x, y, z, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        s.set(x, y + 1, z, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BlockState flower(long h) {
        return switch ((int) ((h >>> 28) % 8)) {
            case 0 -> Blocks.DANDELION.defaultBlockState();
            case 1 -> Blocks.POPPY.defaultBlockState();
            case 2 -> Blocks.OXEYE_DAISY.defaultBlockState();
            case 3 -> Blocks.CORNFLOWER.defaultBlockState();
            case 4 -> Blocks.AZURE_BLUET.defaultBlockState();
            case 5 -> Blocks.ALLIUM.defaultBlockState();
            case 6 -> Blocks.WHITE_TULIP.defaultBlockState();
            default -> Blocks.DANDELION.defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ roads

    /** Surface of a road whose markings we no longer know (it runs under a bridge deck). */
    private static BlockState plainSurface(RoadFeature rf, int dist) {
        if (rf.sidewalk && dist > rf.halfWidth) return rf.sidewalkBlock;
        return rf.surface;
    }

    private BlockState roadSurface(RoadFeature rf, RegionRaster r, int idx, int x, int z) {
        int dist = r.roadDistAt(idx);
        if (rf.sidewalk && dist > rf.halfWidth) return rf.sidewalkBlock;
        if (rf.markingBlock != null) {
            if (rf.centreLine && dist == 0 && (r.roadTAt(idx) % 8) < 4) return rf.markingBlock;
            if (rf.edgeLines && dist == rf.halfWidth && rf.halfWidth > 1) return rf.markingBlock;
            if (rf.kind == RoadFeature.Kind.TAXIWAY && dist == 0) return rf.markingBlock;
        }
        return rf.surface;
    }

    public static BlockState railFor(int octant, int x, int z) {
        RailShape shape;
        switch (octant) {
            case 0, 4 -> shape = RailShape.EAST_WEST;
            case 2, 6 -> shape = RailShape.NORTH_SOUTH;
            default -> shape = ((x + z) & 1) == 0 ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        }
        return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
    }

    // ------------------------------------------------------------------ buildings

    private void paintBuilding(Sink s, int x, int z, BuildingFeature bf, RegionRaster r, int idx, BiomeClassifier.Climate climate) {
        int base = clampY(bf.baseY);
        int flags = r.buildingFlags[idx];
        boolean edge = (flags & RegionRaster.FLAG_EDGE) != 0;
        boolean corner = (flags & RegionRaster.FLAG_CORNER) != 0;
        boolean door = (flags & RegionRaster.FLAG_DOOR) != 0 && cfg.buildingDoors;
        boolean gableEnd = (flags & RegionRaster.FLAG_GABLE_END) != 0;
        boolean minaret = (flags & RegionRaster.FLAG_MINARET) != 0;
        boolean roofOnly = (flags & RegionRaster.FLAG_ROOF_ONLY) != 0;
        int extra = r.roofExtraAt(idx);
        // Pitched roofs are shingled with stairs and capped with a slab (RoofBlocks picks the family).
        RoofBlocks.Set3 roofSet = bf.roofShape.isPitched() ? RoofBlocks.of(bf.roof) : null;
        BlockState roof = roofSet != null ? roofSet.full() : bf.roof;

        int wallBottom = base + 1 + bf.minHeightBlocks;
        int wallTop = clampY(base + bf.heightBlocks);
        if (wallTop < wallBottom) wallTop = wallBottom;
        int storey = Math.max(2, bf.storeyBlocks);

        if (minaret) {
            int top = clampY(base + bf.heightBlocks + bf.roofHeightBlocks + Math.max(8, bf.heightBlocks));
            for (int y = base + 1; y <= top; y++) s.set(x, y, z, bf.wall);
            if (top + 1 < maxY) s.set(x, top + 1, z, bf.roof);
            if (top + 2 < maxY) s.set(x, top + 2, z, Blocks.CONCRETE.yellow().defaultBlockState()); // a gilded finial, not gold
            return;
        }

        if (roofOnly) {
            if (corner) {
                for (int y = base + 1; y < wallTop; y++) s.set(x, y, z, bf.wall);
            }
            s.set(x, wallTop, z, roof);
            for (int y = wallTop + 1; y <= Math.min(maxY - 1, wallTop + extra); y++) s.set(x, y, z, roof);
            if (roofSet != null && roofSet.shaped()) shapeRoofTop(s, x, z, r, idx, Math.min(maxY - 1, wallTop + extra), extra, roofSet);
            return;
        }

        boolean windowsOn = cfg.buildingWindows && !bf.wall.is(Blocks.GLASS);
        boolean tallWindows = storey >= 4 || "church".equals(bf.type) || "cathedral".equals(bf.type) || bf.glassCurtain;
        Direction outward = door ? outwardDirection(r, x, z, idx) : null;

        for (int y = wallBottom; y <= wallTop; y++) {
            int rel = y - (base + 1);
            int storeyIndex = rel / storey;
            int within = rel % storey;
            BlockState block;
            if (edge) {
                block = bf.wall;
                if (door && outward != null && (y == base + 1 || y == base + 2)) {
                    BlockState doorState = doorFor(bf).setValue(DoorBlock.FACING, outward)
                            .setValue(DoorBlock.HALF, y == base + 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
                    s.set(x, y, z, doorState);
                    continue;
                }
                if (corner) {
                    block = bf.wallAccent;
                } else if (bf.glassCurtain) {
                    block = within == 0 ? bf.wallAccent : bf.window;
                } else if (windowsOn && y < wallTop && rel > 0 && windowHere(x, z, storeyIndex)
                        && (within == 1 || (tallWindows && within == 2))
                        && !(door && (y == base + 1 || y == base + 2))) {
                    block = bf.window;
                } else if (y == wallTop && bf.roofShape == RoofShape.FLAT && bf.wall != bf.wallAccent && storey > 0 && bf.heightBlocks > 3) {
                    block = bf.wallAccent; // parapet band
                }
            } else {
                if (y == wallTop) {
                    block = roof;
                } else if (cfg.hollowBuildings) {
                    if (within == 0 && rel > 0) {
                        // Floor slab; every sixth block of it is a light, lighting the room
                        // below (its ceiling) and the room above, so nothing spawns indoors.
                        block = cfg.interiorLights && Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 3
                                ? Blocks.SEA_LANTERN.defaultBlockState() : bf.floor;
                    } else {
                        block = AIR;
                    }
                } else {
                    block = bf.wall;
                }
            }
            s.set(x, y, z, block);
        }

        for (int y = wallTop + 1; y <= Math.min(maxY - 1, wallTop + extra); y++) {
            s.set(x, y, z, gableEnd ? bf.wall : roof);
        }
        // The column's top is roof when the roof rises above the wall, or inside the footprint (where the wall top is roof).
        if (roofSet != null && roofSet.shaped() && !gableEnd && (extra > 0 || !edge)) {
            shapeRoofTop(s, x, z, r, idx, Math.min(maxY - 1, wallTop + extra), extra, roofSet);
        }
        if (extra > 0 && !gableEnd && bf.wall != bf.roof && edge && wallTop + extra + 1 < maxY
                && (bf.roofShape == RoofShape.DOME || bf.roofShape == RoofShape.ONION)) {
            // nothing extra at the dome rim
        }
        if (snowLayers(climate) > 0 && wallTop + extra + 1 < maxY && bf.roofShape == RoofShape.FLAT && !edge) {
            s.set(x, wallTop + 1, z, SNOW_LAYER);
        }
    }

    /**
     * Replaces a roof column's top block with a stair or slab, from the roof heights of its neighbours in the same
     * building: a stair faces the higher neighbour (inner corner where two meet at a right angle, a valley; outer
     * corner where only a diagonal is higher, a hip), and a column nothing rises above is capped with a slab (the
     * ridge, or a peak). Generation writes block states without neighbour updates, so the shape is set here.
     */
    private static void shapeRoofTop(Sink s, int x, int z, RegionRaster r, int idx, int top, int extra, RoofBlocks.Set3 set) {
        boolean n = roofHigher(r, idx, x, z - 1, extra), e = roofHigher(r, idx, x + 1, z, extra);
        boolean so = roofHigher(r, idx, x, z + 1, extra), w = roofHigher(r, idx, x - 1, z, extra);
        int count = (n ? 1 : 0) + (e ? 1 : 0) + (so ? 1 : 0) + (w ? 1 : 0);
        BlockState state = null;
        if (count == 1) {
            state = stair(set, n ? Direction.NORTH : e ? Direction.EAST : so ? Direction.SOUTH : Direction.WEST, StairsShape.STRAIGHT);
        } else if (count == 2 && !(n && so) && !(e && w)) {
            // Facing the first of the two, the second is on its clockwise side: an inner-right corner.
            Direction d = n && e ? Direction.NORTH : e && so ? Direction.EAST : so && w ? Direction.SOUTH : Direction.WEST;
            state = stair(set, d, StairsShape.INNER_RIGHT);
        } else if (count == 0) {
            boolean ne = roofHigher(r, idx, x + 1, z - 1, extra), se = roofHigher(r, idx, x + 1, z + 1, extra);
            boolean sw = roofHigher(r, idx, x - 1, z + 1, extra), nw = roofHigher(r, idx, x - 1, z - 1, extra);
            int diagonals = (ne ? 1 : 0) + (se ? 1 : 0) + (sw ? 1 : 0) + (nw ? 1 : 0);
            if (diagonals == 1) {
                Direction d = ne ? Direction.NORTH : se ? Direction.EAST : sw ? Direction.SOUTH : Direction.WEST;
                state = stair(set, d, StairsShape.OUTER_RIGHT);
            } else if (diagonals == 0 && extra > 0 && set.slab() != null) {
                state = set.slab();
            }
        }
        if (state != null) s.set(x, top, z, state);
    }

    private static boolean roofHigher(RegionRaster r, int idx, int x, int z, int extra) {
        int n = r.index(x, z);
        return n >= 0 && r.building[n] == r.building[idx] && r.roofExtraAt(n) > extra;
    }

    private static BlockState stair(RoofBlocks.Set3 set, Direction facing, StairsShape shape) {
        return set.stairs().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.SHAPE, shape);
    }

    /**
     * Fallen leaves: vanilla's single patch in summer, and in autumn a patch of one to four layers turned any way,
     * as Minecraft 26.3's autumn forest scatters them.
     */
    public static BlockState leafLitter(long h, boolean autumn) {
        BlockState s = Blocks.LEAF_LITTER.defaultBlockState();
        if (!autumn) return s;
        Direction facing = switch ((int) Math.floorMod(h >>> 40, 4)) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            default -> Direction.WEST;
        };
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        if (s.hasProperty(BlockStateProperties.SEGMENT_AMOUNT)) s = s.setValue(BlockStateProperties.SEGMENT_AMOUNT, 1 + (int) Math.floorMod(h >>> 43, 4));
        return s;
    }

    private static boolean windowHere(int x, int z, int storeyIndex) {
        if (((x + z) & 1) != 0) return false;
        return (Materials.mix((x * 31L) ^ (z * 17L) ^ (storeyIndex * 131L)) % 100) < 82;
    }

    /** Ground level for a cell within three of a door, on the door's outward side, or MIN_VALUE elsewhere. */
    private static int doorstepY(RegionRaster r, int x, int z, int terrainY) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= 3; step++) {
                int dx = x - d.getStepX() * step, dz = z - d.getStepZ() * step;
                int didx = r.index(dx, dz);
                if (didx < 0) break;
                if (r.building[didx] == 0) continue;
                if ((r.buildingFlags[didx] & RegionRaster.FLAG_DOOR) != 0 && outwardDirection(r, dx, dz, didx) == d) {
                    BuildingFeature bf = r.buildingAt(didx);
                    if (bf == null) break;
                    int lo = bf.baseY - (step - 1), hi = bf.baseY + (step - 1);
                    return Math.max(lo, Math.min(hi, terrainY));
                }
                break; // a wall without a door, or some other building, between here and any door
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Doors are always ones villagers can open (never iron): residents must be able to leave their houses. */
    private static BlockState doorFor(BuildingFeature bf) {
        if (bf.wall.is(Blocks.CONCRETE.lightGray()) || bf.wall.is(Blocks.CONCRETE.gray()) || bf.glassCurtain) {
            return Blocks.BIRCH_DOOR.defaultBlockState();
        }
        if (bf.wall.is(Blocks.SPRUCE_PLANKS) || bf.wall.is(Blocks.DYED_TERRACOTTA.red()) || bf.wall.is(Blocks.OAK_PLANKS)) {
            return Blocks.SPRUCE_DOOR.defaultBlockState();
        }
        return Blocks.DARK_OAK_DOOR.defaultBlockState();
    }

    private static final Direction[] DOOR_SIDES = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};

    /** The side a door opens to (as the rasterizer chose it), or for any other wall cell its first side facing out. */
    public static Direction outwardDirection(RegionRaster r, int x, int z, int idx) {
        int flags = r.buildingFlags[idx];
        if ((flags & RegionRaster.FLAG_DOOR) != 0) return DOOR_SIDES[(flags >> RegionRaster.DOOR_SIDE_SHIFT) & 3];
        short code = r.building[idx];
        if (!sameBuilding(r, x + 1, z, code)) return Direction.EAST;
        if (!sameBuilding(r, x - 1, z, code)) return Direction.WEST;
        if (!sameBuilding(r, x, z + 1, code)) return Direction.SOUTH;
        if (!sameBuilding(r, x, z - 1, code)) return Direction.NORTH;
        return null;
    }

    /** Cells kept clear straight out from a door (the doorstep) and straight in from it. */
    public static final int DOORSTEP_CELLS = 3, DOORWAY_CELLS = 2;

    /** Whether (x, z) is on a doorstep, the cells straight out from a door: no fence, tree or bench may stand there. */
    public static boolean inFrontOfDoor(RegionRaster r, int x, int z) {
        if (r == null) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= DOORSTEP_CELLS; step++) {
                int dx = x - d.getStepX() * step, dz = z - d.getStepZ() * step;
                int didx = r.index(dx, dz);
                if (didx < 0) break;
                if (r.building[didx] == 0) continue;
                if ((r.buildingFlags[didx] & RegionRaster.FLAG_DOOR) != 0 && outwardDirection(r, dx, dz, didx) == d) return true;
                break; // a wall without a door, or some other building, between here and any door
            }
        }
        return false;
    }

    /** Whether (x, z) is in a doorway, the cells straight in from a door of its building: no furniture or stairs there. */
    public static boolean behindDoor(RegionRaster r, int x, int z) {
        if (r == null) return false;
        int idx = r.index(x, z);
        if (idx < 0 || r.building[idx] == 0) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= DOORWAY_CELLS; step++) {
                int dx = x + d.getStepX() * step, dz = z + d.getStepZ() * step;
                int didx = r.index(dx, dz);
                if (didx < 0 || r.building[didx] != r.building[idx]) break;
                if ((r.buildingFlags[didx] & RegionRaster.FLAG_DOOR) != 0 && outwardDirection(r, dx, dz, didx) == d) return true;
            }
        }
        return false;
    }

    private static boolean sameBuilding(RegionRaster r, int x, int z, short code) {
        int i = r.index(x, z);
        return i >= 0 && r.building[i] == code;
    }

    // ------------------------------------------------------------------ helpers

    private int clampY(int y) {
        return Math.max(minY + 1, Math.min(maxY - 1, y));
    }

    public static long hash(int x, int z, long salt) {
        return Materials.mix((x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L));
    }

    public static BlockState persistentLeaves(BlockState leaves) {
        return leaves.hasProperty(LeavesBlock.PERSISTENT) ? leaves.setValue(LeavesBlock.PERSISTENT, true) : leaves;
    }
}
