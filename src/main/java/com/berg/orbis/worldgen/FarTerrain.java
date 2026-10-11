package com.berg.orbis.worldgen;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.render.ColumnPainter;
import com.berg.orbis.render.Decorator;
import com.berg.orbis.render.TreeBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Distant terrain without chunks: one column of an area nobody has generated, for far-view mods (Distant Horizons),
 * painted the way the world generator will paint it later (the same elevation, height mapping, climate, snow and
 * painter, with the map data when its region is already loaded), so the far view meets the real chunks when they
 * come. Close by (fine detail) the full terrain tiles are read; farther out the coarse ones, a few dozen times fewer
 * downloads for a view that cannot show the difference. Trees, buildings and other decoration are the generator's
 * later work and are not here.
 *
 * <p>Thread-safe: the painter and the model are shared by the world generator's threads already.
 */
public final class FarTerrain {

    /** A run of one block, both ends inclusive (absolute Y). */
    public record Run(BlockState state, int y0, int y1) {}

    /** Detail levels (2^d blocks per column) up to this one read the full terrain tiles; coarser, the coarse ones. */
    public static final int FINE_DETAIL = 2;
    /** Blocks under the surface kept block by block; below, one run down to the floor (never seen from afar). */
    private static final int DEPTH = 24;
    /**
     * How deep the sea is where the far view knows no sea floor (the coarse tiles give the sea as 0 m): deep enough that
     * the bed does not show through (4 blocks showed as a pale band in Voxy), and as deep as the far view's floor
     * under the deep water of saved chunks (Voxy's WATER_FED), so the two seas look alike side by side.
     */
    private static final int SEA_DEPTH = 32;

    private final WorldModel model;
    private final int minY, maxY;

    public FarTerrain(WorldModel model, int minY, int height) {
        this.model = model;
        this.minY = minY;
        this.maxY = minY + height - 1;
    }

    /**
     * The column at a block, top down as runs from the surface (and any water over it) to the world's floor, or null
     * when the hard limit keeps that chunk empty. {@code detail} is the far view's detail level of the column.
     */
    public List<Run> column(int x, int z, int detail) {
        return column(x, z, detail, false);
    }

    /**
     * Only the ground's height at a block, as {@link #column} would put it (the sea or lake bed under water), or
     * MIN_VALUE where the hard limit keeps the chunk empty: the elevation and the land cover's water, without the
     * climate, the painter and the trees. The far view needs no more from the columns around a chunk (they only say how
     * deep its cliffs go), and painting those in full was most of its work on the scenery (each column painted again
     * by every chunk next to it).
     */
    public int groundY(int x, int z, int detail, boolean pastBorder) {
        if (!pastBorder && HardLimit.blocks(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        double e = detail <= FINE_DETAIL ? model.elevation(x, z) : model.coarseElevation(x, z);
        if (Double.isNaN(e)) e = 0;
        int terrainY = model.blockY(e, x, z);
        RegionRaster raster = model.rasterIfLoaded(x, z);
        boolean water = raster == null && e > 0 && model.worldCoverCodeAt(x, z) == com.berg.orbis.landcover.WorldCoverProvider.WATER;
        if ((raster == null || !raster.hasCoastline) && (e <= 0 || (water && e < SEA_WATER_M)) && model.cfg().seaFromElevation) {
            return Math.max(minY + 1, Math.min(terrainY, model.cfg().waterLevelY() - SEA_DEPTH));
        }
        if (water && terrainY >= model.cfg().waterLevelY() && model.cfg().generateWater) return terrainY - LAKE_DEPTH;
        return terrainY;
    }

    /**
     * The column at a block; with {@code pastBorder} also outside the hard limit's border, as scenery nobody can walk
     * into (the terrain alone there: no map data is downloaded for land outside the world).
     */
    public List<Run> column(int x, int z, int detail, boolean pastBorder) {
        if (!pastBorder && HardLimit.blocks(x >> 4, z >> 4)) return null;
        double e = detail <= FINE_DETAIL ? model.elevation(x, z) : model.coarseElevation(x, z);
        if (Double.isNaN(e)) e = 0;
        int terrainY = model.blockY(e, x, z);
        BiomeClassifier.Climate climate = model.climateWithSnow(x, z, e);
        RegionRaster raster = model.rasterIfLoaded(x, z);
        int idx = raster == null ? -1 : raster.index(x, z);
        // Past the map data the land cover comes from ESA WorldCover: forest floor, fields, rock and heath instead of
        // grass everywhere, the forest with its trees, towns and lakes.
        int code = raster == null && e > 0 ? model.worldCoverCodeAt(x, z) : -1;
        boolean water = code == com.berg.orbis.landcover.WorldCoverProvider.WATER;
        if ((raster == null || !raster.hasCoastline) && (e <= 0 || (water && e < SEA_WATER_M)) && model.cfg().seaFromElevation) {
            // Without the map's coastline (past the border, or before the region's map data is in) the painter takes
            // only ground below sea level as sea, and the coarse tiles give the sea as 0 m: exactly sea level, so the
            // fjords around Bergen came out as one flat meadow. At 0 m or below it is sea, and so is water a few metres
            // up (the coarse tiles round the shore of a sound upwards: lakes there stepped up from the sea).
            terrainY = Math.min(terrainY, model.cfg().waterLevelY() - SEA_DEPTH);
            water = false;
            code = -1;
        }
        if (water && terrainY >= model.cfg().waterLevelY() && model.cfg().generateWater) {
            // A lake (or river) above the sea: the elevation over it is its surface, the bed a few blocks down.
            int bed = terrainY - LAKE_DEPTH;
            Capture sink = new Capture(Math.max(minY, bed - DEPTH));
            model.painter().paint(sink, x, z, bed, climate, null, -1, model.cfg().waterLevelY() - 60, LandCover.NONE);
            int g = sink.groundTop();
            sink.set(x, g, z, Blocks.GRAVEL.defaultBlockState());
            sink.fill(x, g + 1, terrainY, z, Blocks.WATER.defaultBlockState());
            return sink.runs();
        }
        LandCover cover = model.worldCoverLand(code, x, z);
        Capture sink = new Capture(Math.max(minY, terrainY - DEPTH));
        model.painter().paint(sink, x, z, terrainY, climate, raster, idx, model.cfg().waterLevelY() - 60, cover);
        if (cover != null && cover.isForest() && model.cfg().generateTrees) trees(sink, x, z, cover, climate, detail);
        if (cover == LandCover.RESIDENTIAL && model.cfg().generateBuildings) town(sink, x, z);
        return sink.runs();
    }

    /** WorldCover water lower than this (metres) is the sea, not a lake. */
    private static final double SEA_WATER_M = 10;

    /** How deep a lake past the map data is (its real depth is unknown there): deep enough to look like water. */
    private static final int LAKE_DEPTH = 6;

    /** Town lots: one house or a yard to a square of this many blocks, with a street on one edge of each. */
    private static final int LOT = 4;
    private static final BlockState[] TOWN_WALLS = {Blocks.CONCRETE.white().defaultBlockState(), Blocks.CONCRETE.lightGray().defaultBlockState(),
            Blocks.DYED_TERRACOTTA.white().defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState()};
    private static final BlockState[] TOWN_ROOFS = {Blocks.DEEPSLATE_TILES.defaultBlockState(), Blocks.DYED_TERRACOTTA.red().defaultBlockState(),
            Blocks.BRICKS.defaultBlockState(), Blocks.CONCRETE.gray().defaultBlockState(), Blocks.DEEPSLATE_TILES.defaultBlockState()};

    /**
     * A built-up column past the map data (ESA WorldCover says built-up, but no buildings are known there): grey
     * streets on the lot edges, and on half of the lots a low house of the usual wall and roof colours, two to four
     * blocks high. Up close it is a town of boxes; from afar grey ground speckled with roofs, as a town looks.
     */
    private void town(Capture sink, int x, int z) {
        int g = sink.groundTop();
        if (g < model.cfg().waterLevelY() || g + 6 > maxY) return;
        if (!sink.isAir(g + 1)) sink.set(x, g + 1, z, Blocks.AIR.defaultBlockState()); // grass or flowers on top
        int lx = Math.floorDiv(x, LOT), lz = Math.floorDiv(z, LOT);
        if (Math.floorMod(x, LOT) == 0 || Math.floorMod(z, LOT) == 0) {
            sink.set(x, g, z, Blocks.CONCRETE.gray().defaultBlockState()); // street
            return;
        }
        long h = ColumnPainter.hash(lx, lz, 0x70E1);
        int roll = (int) Math.floorMod(h, 100);
        if (roll < 50) {
            int walls = 1 + (int) Math.floorMod(h >>> 8, 3);
            sink.fill(x, g + 1, g + walls, z, TOWN_WALLS[(int) Math.floorMod(h >>> 12, TOWN_WALLS.length)]);
            sink.set(x, g + walls + 1, z, TOWN_ROOFS[(int) Math.floorMod(h >>> 16, TOWN_ROOFS.length)]);
        } else if (roll < 75) {
            sink.set(x, g, z, Blocks.CONCRETE.lightGray().defaultBlockState()); // yards, car parks
        }
    }

    /** One tree to a cell of this many blocks, at a spot of its own in the cell. */
    private static final int TREE_CELL = 5;

    /**
     * Trees over a forest column, seen from afar. Full detail: the crowns (and trunks) of the trees of the cells around,
     * each tree on its own spot, shaped by kind (cones for spruce and pine, rounded crowns for the rest), coloured as
     * the world's trees are (autumn included). Coarser, where a column stands for 2 to 8 blocks and single crowns would
     * only flicker: a closed canopy of the column's own kind at tree height, a little uneven.
     */
    private void trees(Capture sink, int x, int z, LandCover cover, BiomeClassifier.Climate climate, int detail) {
        int ground = sink.groundTop();
        if (ground < model.cfg().waterLevelY() || ground + 16 > maxY) return;
        boolean autumn = model.cfg().autumnColours;
        if (detail > 0) {
            TreeBuilder.Species s = Decorator.pickSpecies(cover, climate, x, z);
            if (s == null) return;
            long h = ColumnPainter.hash(x >> 3, z >> 3, 0xCA70);
            int top = ground + (conifer(s) ? 9 : 7) + (int) Math.floorMod(h, 3);
            sink.fill(x, ground + 2, top, z, TreeBuilder.crownLeaves(s, autumn, ColumnPainter.hash(x >> 2, z >> 2, 0x7EE5)));
            return;
        }
        int cx0 = Math.floorDiv(x, TREE_CELL), cz0 = Math.floorDiv(z, TREE_CELL);
        for (int cx = cx0 - 1; cx <= cx0 + 1; cx++) {
            for (int cz = cz0 - 1; cz <= cz0 + 1; cz++) {
                long h = ColumnPainter.hash(cx, cz, 0x7A33);
                int tx = cx * TREE_CELL + (int) Math.floorMod(h, TREE_CELL), tz = cz * TREE_CELL + (int) Math.floorMod(h >>> 8, TREE_CELL);
                TreeBuilder.Species s = Decorator.pickSpecies(cover, climate, tx, tz);
                if (s == null) continue;
                boolean cone = conifer(s);
                int height = (cone ? 9 : 6) + (int) Math.floorMod(h >>> 16, 4);
                int dx = Math.abs(x - tx), dz = Math.abs(z - tz);
                BlockState leaf = TreeBuilder.crownLeaves(s, autumn, h);
                for (int y = ground + 1; y <= ground + height; y++) {
                    int up = y - ground; // 1 .. height
                    int radius = cone ? (up < 3 ? -1 : Math.min(2, (height - up + 2) / 3)) : (up < height - 3 ? -1 : up == height ? 1 : 2);
                    if (dx == 0 && dz == 0 && up < height) {
                        sink.set(x, y, z, TreeBuilder.logOf(s));
                    } else if (radius >= 0 && dx <= radius && dz <= radius && !(dx == 2 && dz == 2)) {
                        if (sink.isAir(y)) sink.set(x, y, z, leaf);
                    }
                }
            }
        }
    }

    private static boolean conifer(TreeBuilder.Species s) {
        return s == TreeBuilder.Species.SPRUCE || s == TreeBuilder.Species.PINE;
    }

    /** Surface Y of a column's runs (the top of the highest non-air run), for the biome lookup. */
    public static int surface(List<Run> runs, int fallback) {
        for (Run r : runs) if (!r.state().isAir()) return r.y1();
        return fallback;
    }

    /** Top of the ground of a column's runs, under any water (the sea or lake bed), for how deep the far view goes. */
    public static int ground(List<Run> runs, int fallback) {
        for (Run r : runs) if (!r.state().isAir() && r.state().getFluidState().isEmpty()) return r.y1();
        return fallback;
    }

    /** The painter's blocks from {@code floor} up, as runs; everything below joins the lowest run. */
    private final class Capture implements ColumnPainter.Sink {
        private final int floor;
        private final BlockState[] cells;
        private int top;

        Capture(int floor) {
            this.floor = floor;
            this.cells = new BlockState[maxY - floor + 1];
            this.top = floor - 1;
        }

        @Override
        public void set(int x, int y, int z, BlockState state) {
            if (y < floor || y > maxY) return;
            cells[y - floor] = state;
            if (!state.isAir() && y > top) top = y;
        }

        /** The highest block a tree can stand on (under any grass or flower on top), or the floor. */
        int groundTop() {
            for (int y = top; y > floor; y--) {
                BlockState s = cells[y - floor];
                if (s != null && s.isSolid()) return y;
            }
            return floor;
        }

        boolean isAir(int y) {
            if (y < floor || y > maxY) return false;
            BlockState s = cells[y - floor];
            return s == null || s.isAir();
        }

        @Override
        public void fill(int x, int y0, int y1, int z, BlockState state) {
            int a = Math.max(y0, floor), b = Math.min(y1, maxY);
            for (int y = a; y <= b; y++) cells[y - floor] = state;
            if (!state.isAir() && b >= a && b > top) top = b;
        }

        List<Run> runs() {
            List<Run> out = new ArrayList<>();
            BlockState air = Blocks.AIR.defaultBlockState();
            if (top < maxY) out.add(new Run(air, top + 1, maxY));
            BlockState run = null;
            int runTop = top;
            for (int y = top; y >= floor; y--) {
                BlockState s = cells[y - floor];
                if (s == null) s = air;
                if (s != run) {
                    if (run != null) out.add(new Run(run, y + 1, runTop));
                    run = s;
                    runTop = y;
                }
            }
            // The lowest run reaches down to the world's floor.
            out.add(new Run(run == null ? Blocks.STONE.defaultBlockState() : run, minY, runTop));
            return out;
        }
    }
}
