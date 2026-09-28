package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * People in the city: villager households in real buildings.
 *
 * A vanilla village is villagers plus beds plus workstations, so that is what
 * a hollow building gets. Some buildings of each chunk (a target number of
 * residents per chunk, spread over the buildings present) receive a bed on
 * the ground floor and, in taller buildings, one more upstairs, and one
 * unemployed villager per bed. They claim the beds, sleep at night, wander by
 * day, flee zombies and breed when a bed is free; enough of them together
 * makes a vanilla village with iron golems. They have nothing to sell.
 *
 * By default the residents are nitwits (the vanilla profession that never
 * takes a job) and no workstations are placed, so trading remains something
 * found in real vanilla villages. With {@code residentJobs} on, a workstation
 * is placed only where OpenStreetMap says there is a business of that kind
 * (a bakery gets a smoker, a library a lectern, a tailor a loom, a church a
 * bell, ...), and such a building always has a household.
 *
 * Everything is vanilla blocks and vanilla villagers; nothing is required on
 * a client. Each building is handled by the one chunk that contains its
 * anchor cell (the interior cell nearest its centre), so a building spanning
 * several chunks gets one household.
 */
public final class Residents {

    private static final int MAX_BEDS_PER_BUILDING = 2;
    private static final BlockState[] BEDS = {
            Blocks.BED.red().defaultBlockState(), Blocks.BED.white().defaultBlockState(), Blocks.BED.blue().defaultBlockState(),
            Blocks.BED.lime().defaultBlockState(), Blocks.BED.yellow().defaultBlockState(), Blocks.BED.lightBlue().defaultBlockState(),
            Blocks.BED.orange().defaultBlockState(), Blocks.BED.gray().defaultBlockState()};

    private Residents() {
    }

    public static void place(OrbisConfig cfg, SettlementBuilder.Grid level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.villagerResidents || cfg.residentsPerChunk <= 0 || !cfg.hollowBuildings) return;
        Map<Long, BuildingFeature> buildings = new LinkedHashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int idx = r.index(minX + lx, minZ + lz);
                if (idx < 0) continue;
                BuildingFeature bf = r.buildingAt(idx);
                if (bf != null && !bf.part && bf.minHeightBlocks == 0 && bf.heightBlocks >= 3) buildings.putIfAbsent(bf.id, bf);
            }
        }
        if (buildings.isEmpty()) return;
        // residentsPerChunk counts villagers, and a household is up to two of them (one bed per storey), so the
        // household chance is halved: at 0.3 a 625-chunk simulation area holds roughly 190 villagers, which is
        // already six large vanilla villages' worth of AI.
        double chance = Math.min(1.0, cfg.residentsPerChunk / (buildings.size() * MAX_BEDS_PER_BUILDING));
        for (BuildingFeature bf : buildings.values()) {
            int[] anchor = anchor(r, bf);
            if (anchor == null) continue;
            if (anchor[0] < minX || anchor[0] >= minX + 16 || anchor[1] < minZ || anchor[1] >= minZ + 16) continue; // another chunk's
            // Without residentJobs the residents are nitwits (never take a job) and no workstation is placed, so the
            // only traders in the world are the ones vanilla villages provide.
            BlockState workstation = null;
            if (cfg.residentJobs) {
                workstation = workstationFor(bf.tags);
                for (int i = 0; workstation == null && i < bf.pois.size(); i++) workstation = workstationFor(bf.pois.get(i));
            }
            long h = ColumnPainter.hash(anchor[0], anchor[1], bf.id ^ 0x51D3L);
            boolean household = workstation != null || ((h >>> 8) & 0xFFFF) / 65536.0 < chance;
            if (!household) continue;

            int storey = Math.max(2, bf.storeyBlocks);
            int base = Math.max(cfg.minY + 1, Math.min(cfg.maxY() - 1, bf.baseY));
            int wallTop = Math.min(cfg.maxY() - 1, base + bf.heightBlocks);
            // A staircase links the storeys; without one only the ground floor is lived in, so that nobody
            // is shut in upstairs.
            int[] stairs = storey >= 3 ? stairStrip(r, bf, anchor[0], anchor[1], storey + 1, h) : null;
            if (stairs != null) placeStairs(level, bf, stairs, base, storey, wallTop);
            for (int k = 0; k < MAX_BEDS_PER_BUILDING; k++) {
                int floorY = k == 0 ? base : base + 1 + k * storey;   // the block the bed stands on
                if (floorY + 3 > wallTop) break;                       // no headroom under the roof
                if (k > 0 && stairs == null) break;
                int[] bed = placeBed(level, r, bf, stairs, anchor[0], anchor[1], floorY, h + k);
                if (bed == null) continue;
                // The resident stands beside the bed, not in it (a villager spawned on a bed block sits there).
                int[] stand = standCell(level, r, bf, stairs, bed, floorY);
                level.villager(stand[0] + 0.5, floorY + 1, stand[1] + 0.5, !cfg.residentJobs,
                        new net.minecraft.core.BlockPos(bed[2], floorY + 1, bed[3]));
                if (k == 0 && workstation != null) placeWorkstation(level, r, bf, stairs, anchor[0], anchor[1], floorY, workstation, h);
            }
        }
    }

    // ------------------------------------------------------------------ stairs

    /**
     * Finds room for a switchback staircase near the anchor: two lanes side by side, {@code len + 1} cells
     * long, all interior. Returns {x0, z0, dirX, dirZ, laneX, laneZ} or null when the building is too small.
     */
    /** Flights are this many blocks wide where the building has room (four cells across for the two lanes). */
    private static final int STAIR_WIDTH = 2;

    private static int[] stairStrip(RegionRaster r, BuildingFeature bf, int ax, int az, int len, long h) {
        Direction[] dirs = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        int start = (int) Math.floorMod(h >> 9, 4L);
        for (int width = STAIR_WIDTH; width >= 1; width--) {
            for (int i = 0; i < 4; i++) {
                Direction d = dirs[(start + i) & 3];
                Direction p = d.getClockWise();
                for (int side = 1; side >= -1; side -= 2) {
                    for (int back = 0; back <= len; back++) {
                        int x0 = ax - d.getStepX() * back, z0 = az - d.getStepZ() * back;
                        boolean ok = true;
                        for (int c = 0; c <= len && ok; c++) {
                            for (int off = 0; off < 2 * width && ok; off++) {
                                int x = x0 + d.getStepX() * c + p.getStepX() * side * off;
                                int z = z0 + d.getStepZ() * c + p.getStepZ() * side * off;
                                ok = interior(r, x, z, bf);
                            }
                        }
                        if (ok) return new int[]{x0, z0, d.getStepX(), d.getStepZ(), p.getStepX() * side, p.getStepZ() * side, len, width};
                    }
                }
            }
        }
        return null;
    }

    private static boolean onStrip(int[] st, int x, int z) {
        if (st == null) return false;
        for (int c = 0; c <= st[6]; c++) {
            for (int off = 0; off < 2 * st[7]; off++) {
                if (x == st[0] + st[2] * c + st[4] * off && z == st[1] + st[3] * c + st[5] * off) return true;
            }
        }
        return false;
    }

    /**
     * One flight per storey, flights alternating between the two lanes and directions so that each flight
     * starts where the previous one landed. The flight for room k rises from its floor F to the slab S above
     * (stair blocks at F+1..S-1, holes cut in the slab over them for headroom); the flight above runs back
     * along the other lane, so nothing ever stands over a hole of the flight below. Each lane is
     * {@code st[7]} blocks wide.
     */
    private static void placeStairs(SettlementBuilder.Grid level, BuildingFeature bf, int[] st, int base, int storey, int wallTop) {
        int len = st[6], width = st[7];
        Direction dir = st[2] == 1 ? Direction.EAST : st[2] == -1 ? Direction.WEST : st[3] == 1 ? Direction.SOUTH : Direction.NORTH;
        BlockState stair = bf.wall.is(Blocks.SPRUCE_PLANKS) || bf.wall.is(Blocks.OAK_PLANKS) || bf.wall.is(Blocks.DARK_OAK_PLANKS)
                ? Blocks.SPRUCE_STAIRS.defaultBlockState() : Blocks.STONE_BRICK_STAIRS.defaultBlockState();
        BlockState fill = bf.floor;
        for (int k = 0; ; k++) {
            int floorY = k == 0 ? base : base + 1 + k * storey;
            int slabY = k == 0 ? base + 1 + storey : floorY + storey;
            if (slabY + 3 > wallTop) break; // nothing to live in above the slab
            int rise = slabY - floorY;
            int lane = k & 1;
            int from = k == 0 ? 0 : (lane == 1 ? len : 1);
            int sign = lane == 1 ? -1 : 1;
            Direction facing = sign > 0 ? dir : dir.getOpposite();
            // Vanilla's path finder lets a mob step up one block only when the cell it leaves is clear for the
            // mob's full height above the NEW floor: three blocks of air over every step, including the floor
            // cell at the foot of the flight. Upstairs rooms are only two blocks high, so the ceiling above the
            // foot of each flight and above each step is opened up (a hole in the slab where needed).
            int top = wallTop - 1;
            for (int i = 0; i < rise; i++) {
                int c = from + sign * i;
                for (int off = lane * width; off < (lane + 1) * width; off++) {
                    int x = st[0] + st[2] * c + st[4] * off, z = st[1] + st[3] * c + st[5] * off;
                    if (i > 0) {
                        for (int y = floorY + 1; y < floorY + i; y++) level.set(x, y, z, fill);
                        level.set(x, floorY + i, z, stair.setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
                    }
                    for (int y = floorY + i + 1; y <= Math.min(floorY + i + 3, top); y++) level.set(x, y, z, AIR);
                }
            }
        }
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** A free floor cell next to the foot of the bed (falls back to the bed itself). */
    private static int[] standCell(SettlementBuilder.Grid level, RegionRaster r, BuildingFeature bf, int[] stairs, int[] bed, int floorY) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = bed[0] + d.getStepX(), z = bed[1] + d.getStepZ();
            if (roomFor(level, r, bf, stairs, x, z, floorY)) return new int[]{x, z};
        }
        return bed;
    }

    /** Interior cell nearest the building's centre (spiral search), or null. */
    static int[] anchor(RegionRaster r, BuildingFeature bf) {
        int cx = (int) Math.floor(bf.cx), cz = (int) Math.floor(bf.cz);
        for (int d = 0; d <= 8; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    if (interior(r, cx + dx, cz + dz, bf)) return new int[]{cx + dx, cz + dz};
                }
            }
        }
        return null;
    }

    static boolean interior(RegionRaster r, int x, int z, BuildingFeature bf) {
        int idx = r.index(x, z);
        if (idx < 0) return false;
        BuildingFeature here = r.buildingAt(idx);
        if (here == null || here.id != bf.id) return false;
        int flags = r.buildingFlags[idx];
        return (flags & (RegionRaster.FLAG_EDGE | RegionRaster.FLAG_ROOF_ONLY | RegionRaster.FLAG_MINARET)) == 0;
    }

    /** A bed with its foot at the first free interior spot near the anchor; returns the foot cell or null. */
    private static int[] placeBed(SettlementBuilder.Grid level, RegionRaster r, BuildingFeature bf, int[] stairs, int ax, int az, int floorY, long h) {
        Direction[] dirs = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        int start = (int) Math.floorMod(h >> 3, 4L);
        for (int d = 0; d <= 5; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    int fx = ax + dx, fz = az + dz;
                    if (!roomFor(level, r, bf, stairs, fx, fz, floorY)) continue;
                    for (int i = 0; i < 4; i++) {
                        Direction dir = dirs[(start + i) & 3];
                        int hx = fx + dir.getStepX(), hz = fz + dir.getStepZ();
                        if (!roomFor(level, r, bf, stairs, hx, hz, floorY)) continue;
                        BlockState bed = BEDS[(int) Math.floorMod(h >> 16, (long) BEDS.length)]
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
                        level.set(fx, floorY + 1, fz, bed.setValue(BedBlock_PART, BedPart.FOOT));
                        level.set(hx, floorY + 1, hz, bed.setValue(BedBlock_PART, BedPart.HEAD));
                        return new int[]{fx, fz, hx, hz}; // foot, then head (the bed's point of interest)
                    }
                }
            }
        }
        return null;
    }

    private static final net.minecraft.world.level.block.state.properties.EnumProperty<BedPart> BedBlock_PART = BlockStateProperties.BED_PART;

    /** Interior cell off the staircase with a solid floor and two blocks of air above it. */
    private static boolean roomFor(SettlementBuilder.Grid level, RegionRaster r, BuildingFeature bf, int[] stairs, int x, int z, int floorY) {
        if (!interior(r, x, z, bf) || onStrip(stairs, x, z)) return false;
        BlockState floor = level.get(x, floorY, z);
        if (!floor.isSolid() || floor.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                || floor.getBlock() instanceof net.minecraft.world.level.block.StairBlock) return false;
        return level.get(x, floorY + 1, z).isAir() && level.get(x, floorY + 2, z).isAir();
    }

    private static void placeWorkstation(SettlementBuilder.Grid level, RegionRaster r, BuildingFeature bf, int[] stairs, int ax, int az, int floorY,
                                         BlockState workstation, long h) {
        int start = (int) Math.floorMod(h >> 5, 4L);
        Direction[] dirs = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        for (int d = 2; d <= 4; d++) {
            for (int i = 0; i < 4; i++) {
                Direction dir = dirs[(start + i) & 3];
                int x = ax + dir.getStepX() * d, z = az + dir.getStepZ() * d;
                if (!roomFor(level, r, bf, stairs, x, z, floorY)) continue;
                level.set(x, floorY + 1, z, workstation);
                return;
            }
        }
    }

    /** The vanilla job block for a real business, or null when the building is not one. */
    static BlockState workstationFor(Map<String, String> t) {
        if (t == null) return null;
        String shop = t.getOrDefault("shop", ""), amenity = t.getOrDefault("amenity", ""), craft = t.getOrDefault("craft", "");
        String building = t.getOrDefault("building", ""), tourism = t.getOrDefault("tourism", "");
        switch (shop) {
            case "bakery", "pastry", "butcher", "deli": return Blocks.SMOKER.defaultBlockState();
            case "books": return Blocks.LECTERN.defaultBlockState();
            case "hardware", "doityourself", "trade": return Blocks.SMITHING_TABLE.defaultBlockState();
            case "clothes", "tailor", "fabric", "wool", "sewing": return Blocks.LOOM.defaultBlockState();
            case "seafood", "fishmonger", "fishing": return Blocks.BARREL.defaultBlockState();
            case "chemist", "pharmacy", "herbalist": return Blocks.BREWING_STAND.defaultBlockState();
            case "leather", "shoes", "bag": return Blocks.CAULDRON.defaultBlockState();
            case "hunting", "archery": return Blocks.FLETCHING_TABLE.defaultBlockState();
            case "stone", "tiles": return Blocks.STONECUTTER.defaultBlockState();
            case "weapons", "knives": return Blocks.GRINDSTONE.defaultBlockState();
            default: break;
        }
        switch (craft) {
            case "blacksmith", "metal_construction", "locksmith": return Blocks.SMITHING_TABLE.defaultBlockState();
            case "tailor", "dressmaker": return Blocks.LOOM.defaultBlockState();
            case "stonemason": return Blocks.STONECUTTER.defaultBlockState();
            case "shoemaker", "saddler", "leather": return Blocks.CAULDRON.defaultBlockState();
            case "armourer", "armorer": return Blocks.BLAST_FURNACE.defaultBlockState();
            case "bowmaker": return Blocks.FLETCHING_TABLE.defaultBlockState();
            default: break;
        }
        switch (amenity) {
            case "library", "archive": return Blocks.LECTERN.defaultBlockState();
            case "place_of_worship": return Blocks.BELL.defaultBlockState();
            case "pharmacy": return Blocks.BREWING_STAND.defaultBlockState();
            default: break;
        }
        switch (building) {
            case "farm", "barn", "cowshed", "stable", "greenhouse", "farm_auxiliary", "sty": return Blocks.COMPOSTER.defaultBlockState();
            case "church", "chapel", "cathedral", "mosque", "temple", "synagogue": return Blocks.BELL.defaultBlockState();
            default: break;
        }
        if ("information".equals(tourism)) return Blocks.CARTOGRAPHY_TABLE.defaultBlockState();
        return null;
    }
}
