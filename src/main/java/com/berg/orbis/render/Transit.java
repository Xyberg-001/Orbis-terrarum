package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.DecorType;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Makes the real railway network rideable. The column painter lays plain and powered rails along every
 * line; this pass, which can see neighbouring columns, turns the rails into a connected track: corners
 * become curves, one-block climbs become ascending rails, and diagonal runs (drawn as a staircase of
 * cells) get a corner rail at each step, chosen so that the line's own cells stay straight (a rail can only
 * climb in a straight line) and only the added corners curve. At every station or tram stop node a minecart
 * waits on the nearest unpowered rail and a post carries the station's name.
 *
 * The rail work goes through {@link RailGrid}, so the same code runs against an in-memory grid in the
 * offline rail simulator.
 */
public final class Transit {

    private Transit() {
    }

    /** The few block operations the rail joining needs, so it can run outside a world. */
    public interface RailGrid {
        /** Vanilla OCEAN_FLOOR heightmap: Y of the first block above the highest motion-blocking one. */
        int surface(int x, int z);

        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);
    }

    public static RailGrid of(WorldGenLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new RailGrid() {
            @Override
            public int surface(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return level.getBlockState(pos.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                level.setBlock(pos.set(x, y, z), state, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
            }
        };
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.transitLines || !cfg.generateRoads) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx >= 0 && r.decorAt(idx) == DecorType.TRAIN_STOP) station(cfg, level, r, pos, x, z, idx);
            }
        }
        joinRails(cfg, of(level), r, minX, minZ);
    }

    /** Joins up the rails of one chunk: corner rails on diagonal steps, then shapes for every touched rail. */
    public static void joinRails(OrbisConfig cfg, RailGrid g, RegionRaster r, int minX, int minZ) {
        List<int[]> rails = new ArrayList<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0 || !isRailCell(r, idx)) continue;
                int y = railY(g, x, z, cfg.minY);
                if (y != Integer.MIN_VALUE) rails.add(new int[]{x, y, z});
            }
        }
        // A 45-degree line that runs between two cell columns gets two centreline cells per row: a two-wide
        // zig-zag in which every rail has a neighbour to the west and one to the north or south, so every
        // rail is a curve and none can climb. Drop the eastern of the two diagonals; the corners below then
        // rebuild a one-wide staircase whose own cells stay straight.
        List<int[]> kept = new ArrayList<>();
        for (int[] c : rails) {
            if (redundantZigzag(g, r, c[0], c[1], c[2])) removeRail(g, c[0], c[1], c[2]);
            else kept.add(c);
        }
        List<int[]> touched = new ArrayList<>(kept);
        for (int[] c : kept) fillDiagonals(g, r, c[0], c[1], c[2], cfg.minY, touched);
        levelTrack(g, touched, cfg.minY);
        List<int[]> demoted = new ArrayList<>();
        for (int[] c : touched) {
            if (connect(g, c[0], c[1], c[2])) demoted.add(c);
        }
        // A powered-rail spot that landed on a curve moves to the nearest straight rail beside it, so the
        // spacing the painter chose (every 10 m along the line) survives the corners.
        for (int[] c : demoted) {
            boolean moved = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 0; dy >= -1 && !moved; dy--) {
                    int nx = c[0] + d.getStepX(), ny = c[1] + dy, nz = c[2] + d.getStepZ();
                    for (int yy = ny + 1; yy >= ny; yy--) {
                        BlockState n = g.get(nx, yy, nz);
                        if (!(n.getBlock() instanceof RailBlock)) continue;
                        RailShape ns = n.getValue(RailBlock.SHAPE);
                        if (ns != RailShape.NORTH_SOUTH && ns != RailShape.EAST_WEST && !ns.isSlope()) break;
                        power(g, nx, yy, nz, ns);
                        moved = true;
                        break;
                    }
                }
                if (moved) break;
            }
        }
    }

    /** Turns the rail at (x, y, z) into a powered rail of this shape over a redstone block. */
    private static void power(RailGrid g, int x, int y, int z, RailShape shape) {
        g.set(x, y - 1, z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        g.set(x, y, z, Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, shape).setValue(PoweredRailBlock.POWERED, true));
    }

    /** A centreline rail whose only track neighbours are a centreline rail to the west and one to the north or south. */
    private static boolean redundantZigzag(RailGrid g, RegionRaster r, int x, int y, int z) {
        int count = 0;
        boolean west = false, northOrSouth = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!hasRailNear(g, nx, y, nz)) continue;
            count++;
            int idx = r.index(nx, nz);
            boolean centre = idx >= 0 && isRailCell(r, idx);
            if (d == Direction.WEST && centre) west = true;
            if ((d == Direction.NORTH || d == Direction.SOUTH) && centre) northOrSouth = true;
        }
        return count == 2 && west && northOrSouth;
    }

    private static void removeRail(RailGrid g, int x, int y, int z) {
        g.set(x, y, z, Blocks.AIR.defaultBlockState());
        if (g.get(x, y - 1, z).getBlock() == Blocks.REDSTONE_BLOCK) {
            BlockState under = g.get(x, y - 2, z);
            g.set(x, y - 1, z, under.isSolid() ? under : Blocks.GRAVEL.defaultBlockState());
        }
    }

    /**
     * Vanilla rails only join on one level, except that a straight rail may climb one block to the next.
     * The painter puts every rail on its own column's ground, so on a slope a curve ends up a block below a
     * neighbour, and a rail can sit in a one-block dip; neither ever connects. This pass raises such rails
     * (up to two blocks, on fill copied from the block under them) until every curve is level with its
     * neighbours and every climb happens on a straight rail. Raising one rail can make its neighbour the
     * odd one out, so it runs until nothing moves.
     */
    private static void levelTrack(RailGrid g, List<int[]> touched, int minY) {
        for (int pass = 0; pass < 16; pass++) {
            boolean changed = false;
            for (int i = 0; i < touched.size(); i++) {
                int[] c = touched.get(i);
                int y = railY(g, c[0], c[2], minY);
                if (y == Integer.MIN_VALUE) continue;
                c[1] = y;
                int target = neededHeight(g, c[0], y, c[2]);
                if (target <= y || target - y > 2) continue;
                raise(g, c[0], y, c[2], target);
                c[1] = target;
                changed = true;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int nx = c[0] + d.getStepX(), nz = c[2] + d.getStepZ();
                    int ny = railY(g, nx, nz, minY);
                    if (ny != Integer.MIN_VALUE) touched.add(new int[]{nx, ny, nz});
                }
            }
            if (!changed) break;
        }
    }

    /**
     * The height this rail needs for its two track neighbours to connect: a curve rises to its highest
     * neighbour; a straight rail with both neighbours above it rises to the lower of them; anything else
     * stays (a straight rail one below a neighbour simply ascends to it).
     */
    private static int neededHeight(RailGrid g, int x, int y, int z) {
        Direction[] dirs = new Direction[2];
        int[] ys = new int[2];
        int found = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            for (int dy = 1; dy >= -1; dy--) {
                if (!isRail(g.get(nx, y + dy, nz))) continue;
                if (found < 2) {
                    dirs[found] = d;
                    ys[found] = y + dy;
                }
                found++;
                break;
            }
        }
        if (found != 2) return y;
        if (dirs[0].getAxis() == dirs[1].getAxis()) {
            return ys[0] > y && ys[1] > y ? Math.min(ys[0], ys[1]) : y;
        }
        return Math.max(y, Math.max(ys[0], ys[1]));
    }

    /** Moves the rail at (x, y, z) up to Y {@code to}, filling under it with the block it stood on. */
    private static void raise(RailGrid g, int x, int y, int z, int to) {
        BlockState rail = g.get(x, y, z);
        BlockState fill = g.get(x, y - 1, z);
        if (!fill.isSolid() || fill.getBlock() == Blocks.REDSTONE_BLOCK) fill = Blocks.STONE_BRICKS.defaultBlockState();
        boolean powered = rail.getBlock() instanceof PoweredRailBlock;
        for (int yy = y; yy < to; yy++) {
            g.set(x, yy, z, powered && yy == to - 1 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : fill);
        }
        g.set(x, to, z, rail);
        for (int yy = to + 1; yy <= to + 2; yy++) {
            BlockState s = g.get(x, yy, z);
            if (!s.isAir() && !isRail(s)) g.set(x, yy, z, Blocks.AIR.defaultBlockState());
        }
    }

    /** A rail centreline cell: on its own bed, or embedded in a street (a tram line the street was drawn over). */
    public static boolean isRailCell(RegionRaster r, int idx) {
        if (r.decorAt(idx) == DecorType.EMBEDDED_RAIL) return true;
        if (r.road[idx] == 0 || r.roadDistAt(idx) != 0) return false;
        RoadFeature rf = r.roadAt(idx);
        return rf != null && rf.isRail();
    }

    /**
     * For each diagonal rail neighbour with no side rail in between, a corner rail on the better of the two
     * corner cells. The corner sits at the higher of the two ends, so the lower end climbs to it as a straight
     * ascending rail; the corner is chosen on the side that keeps each end straight (its other rail opposite
     * the corner), so along a staircase the line's cells alternate straight and the corners curve.
     */
    private static void fillDiagonals(RailGrid g, RegionRaster r, int x, int y, int z, int minY, List<int[]> touched) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                int px = x + dx, pz = z + dz;
                int py = railY(g, px, pz, minY);
                if (py == Integer.MIN_VALUE || Math.abs(py - y) > 1) continue;                // no diagonal partner
                if (railY(g, px, z, minY) != Integer.MIN_VALUE || railY(g, x, pz, minY) != Integer.MIN_VALUE) continue; // already joined
                int cy = Math.max(y, py);
                // corner 0 = (px, z): beside this cell along x, beside the partner along z; corner 1 the other way round
                int[][] corners = {{px, z, dx, 0, 0, -dz}, {x, pz, 0, dz, -dx, 0}};
                int[] best = null;
                int bestScore = Integer.MIN_VALUE;
                for (int[] c : corners) {
                    int cx = c[0], cz = c[1];
                    int idx = r.index(cx, cz);
                    if (idx < 0 || r.water[idx] != 0) continue;
                    RoadFeature rf = r.roadAt(idx);
                    boolean onTrack = rf != null && rf.isRail();
                    if (r.building[idx] != 0 && !onTrack) continue;       // inside a building only along the track (a station hall)
                    int ground = g.surface(cx, cz) - 1;
                    if (r.building[idx] == 0 && Math.abs(cy - (ground + 1)) > 2) continue;   // more than a block or two of fill / cut
                    int score = (onTrack ? 4 : 0) + (cy == ground + 1 ? 2 : 0);
                    // Keep the ends straight: a big bonus when the end's other rail lies opposite the corner, a
                    // penalty when it lies to the side (the end would have to curve). The lower end must be
                    // straight to climb at all.
                    score += straightness(g, x, y, z, c[2], c[3]) * (y < py ? 3 : 1);
                    score += straightness(g, px, py, pz, c[4], c[5]) * (py < y ? 3 : 1);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[]{cx, cy, cz};
                    }
                }
                if (best == null) continue;
                int cx = best[0], cz = best[2];
                // Room for the rail: air on it and above, something solid under it (fill up to two blocks).
                for (int yy = cy; yy <= cy + 2; yy++) {
                    BlockState s = g.get(cx, yy, cz);
                    if (!s.isAir() && !(s.getBlock() instanceof BaseRailBlock)) g.set(cx, yy, cz, Blocks.AIR.defaultBlockState());
                }
                for (int yy = cy - 1; yy >= cy - 2; yy--) {
                    if (g.get(cx, yy, cz).isSolid()) break;
                    g.set(cx, yy, cz, Blocks.STONE_BRICKS.defaultBlockState());
                }
                g.set(cx, cy, cz, Blocks.RAIL.defaultBlockState());
                touched.add(best);
                touched.add(new int[]{px, py, pz}); // the partner may already be shaped; it gets a second look
            }
        }
    }

    /**
     * How well a corner in direction (ddx, ddz) suits the rail at (x, y, z): +3 when its other rail lies
     * opposite (the cell stays straight), -3 when its other rail lies to the side (the cell would have to
     * curve, and could not climb), 0 when it has no other rail yet.
     */
    private static int straightness(RailGrid g, int x, int y, int z, int ddx, int ddz) {
        int score = 0;
        if (hasRailNear(g, x - ddx, y, z - ddz)) score += 3;
        if (hasRailNear(g, x + ddz, y, z + ddx) || hasRailNear(g, x - ddz, y, z - ddx)) score -= 3;
        return score;
    }

    private static boolean hasRailNear(RailGrid g, int x, int y, int z) {
        return isRail(g.get(x, y, z)) || isRail(g.get(x, y - 1, z)) || isRail(g.get(x, y + 1, z));
    }

    /** Y of the rail block in this column: just above the ground/deck, or, for a tunnel, below the surface. */
    private static int railY(RailGrid g, int x, int z, int minY) {
        int surface = g.surface(x, z); // first non-motion-blocking block above the ground
        if (isRail(g.get(x, surface, z))) return surface;
        for (int y = surface - 1; y >= Math.max(minY + 1, surface - 96); y--) {
            if (isRail(g.get(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isRail(BlockState s) {
        return s.getBlock() instanceof BaseRailBlock;
    }

    /**
     * Sets this rail's shape from the rails around it: ascending toward a neighbour one block up, a curve at
     * a corner, straight through when rails lie on both sides (with three or four neighbours the straight
     * pair wins).
     */
    private static boolean connect(RailGrid g, int x, int y, int z) {
        BlockState here = g.get(x, y, z);
        if (!isRail(here)) return false;
        EnumSet<Direction> level0 = EnumSet.noneOf(Direction.class);
        Direction up = null;
        int ups = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (isRail(g.get(nx, y + 1, nz))) {
                up = d;
                ups++;
            } else if (isRail(g.get(nx, y, nz)) || isRail(g.get(nx, y - 1, nz))) {
                level0.add(d);
            }
        }
        RailShape shape = null;
        boolean n = level0.contains(Direction.NORTH), s = level0.contains(Direction.SOUTH);
        boolean e = level0.contains(Direction.EAST), w = level0.contains(Direction.WEST);
        if (ups == 1) {
            shape = switch (up) {
                case EAST -> RailShape.ASCENDING_EAST;
                case WEST -> RailShape.ASCENDING_WEST;
                case SOUTH -> RailShape.ASCENDING_SOUTH;
                default -> RailShape.ASCENDING_NORTH;
            };
        } else if (level0.size() >= 2) {
            if (n && s) shape = RailShape.NORTH_SOUTH;
            else if (e && w) shape = RailShape.EAST_WEST;
            else if (s && e) shape = RailShape.SOUTH_EAST;
            else if (s && w) shape = RailShape.SOUTH_WEST;
            else if (n && w) shape = RailShape.NORTH_WEST;
            else if (n && e) shape = RailShape.NORTH_EAST;
        } else if (level0.size() == 1) {
            Direction d = level0.iterator().next();
            shape = d.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        }
        if (shape == null) return false;
        boolean demoted = false;
        BlockState next;
        if (here.getBlock() instanceof PoweredRailBlock) {
            if (shape.isSlope() || shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST) {
                next = here.setValue(PoweredRailBlock.SHAPE, shape);
            } else {
                next = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape); // powered rails cannot curve
                demoted = true;
            }
        } else if (here.getBlock() instanceof RailBlock) {
            if (shape.isSlope()) {
                // Every climb is powered: an occupied cart loses its speed on an unpowered slope within a few blocks.
                power(g, x, y, z, shape);
                return false;
            }
            next = here.setValue(RailBlock.SHAPE, shape);
        } else {
            return false;
        }
        if (next != here) g.set(x, y, z, next);
        return demoted;
    }

    /** A waiting minecart on the nearest plain rail, and a named post at the stop. */
    private static void station(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, BlockPos.MutableBlockPos pos, int x, int z, int idx) {
        RailGrid g = of(level);
        int[] rail = null;
        int railYFound = Integer.MIN_VALUE;
        search:
        for (int d = 0; d <= 8; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    int cx = x + dx, cz = z + dz;
                    int ci = r.index(cx, cz);
                    if (ci < 0 || !isRailCell(r, ci)) continue;
                    int y = railY(g, cx, cz, cfg.minY);
                    if (y == Integer.MIN_VALUE) continue;
                    if (!(level.getBlockState(pos.set(cx, y, cz)).getBlock() instanceof RailBlock)) continue; // not on a powered rail
                    rail = new int[]{cx, cz};
                    railYFound = y;
                    break search;
                }
            }
        }
        if (rail != null) {
            boolean taken = !level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(rail[0], railYFound, rail[1], rail[0] + 1, railYFound + 1, rail[1] + 1),
                    e -> e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart).isEmpty();
            if (!taken) {
                Entity cart = EntityTypes.MINECART.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
                if (cart != null) {
                    cart.snapTo(rail[0] + 0.5, railYFound, rail[1] + 0.5, 0f, 0f);
                    level.addFreshEntity(cart);
                }
            }
        }

        // The stop itself: a stone post with a lantern and the station name, if the node is on open ground.
        if (r.building[idx] != 0 || r.water[idx] != 0) return;
        RoadFeature here = r.roadAt(idx);
        if (here != null && r.roadDistAt(idx) <= here.halfWidth) return; // on the track / the road itself
        int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        if (surface <= cfg.minY || surface + 4 >= cfg.maxY()) return;
        if (!level.getBlockState(pos.set(x, surface, z)).isSolid()) return;
        level.setBlock(pos.set(x, surface + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
        level.setBlock(pos.set(x, surface + 2, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
        level.setBlock(pos.set(x, surface + 3, z), Blocks.LANTERN.defaultBlockState(), 3);
        String name = r.labels.get(idx);
        if (name == null) return;
        Direction facing = rail != null ? Direction.getNearest(x - rail[0], 0, z - rail[1], Direction.EAST) : Direction.EAST;
        if (facing.getAxis().isVertical()) facing = Direction.EAST;
        int sx = x + facing.getStepX(), sz = z + facing.getStepZ();
        if (!level.getBlockState(pos.set(sx, surface + 2, sz)).isAir()) return;
        level.setBlock(pos, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity be) Signage.writeSign(be, name);
    }
}
