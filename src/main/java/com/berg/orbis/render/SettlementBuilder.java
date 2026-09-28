package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.LandCover;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Towns at vanilla scale for coarse worlds.
 *
 * At 8 m per block or coarser a real house is smaller than a block, so real
 * footprints cannot be drawn. Instead every built-up area of the map
 * (residential, commercial, retail, industrial land use) is filled with
 * vanilla-sized buildings on an 8-block lot grid: wooden and painted houses
 * with pitched roofs in residential areas, apartment and office blocks of
 * three to eight storeys in commercial and retail areas, low halls on
 * industrial land. Lots are deterministic (hashed from their position), keep
 * off roads and water, sit on foundations on gentle slopes and skip steep
 * ones, and every storey has a sea lantern so nothing spawns inside. So a
 * city is where it really is, as large as it really is, and looks like a
 * city -- just not made of its real buildings.
 */
public final class SettlementBuilder {

    private enum Kind { HOUSE, BLOCK, HALL }

    /** Entity tag on city residents spawned as nitwits; their children inherit it (see VillagerMixin). */
    public static final String RESIDENT_TAG = "orbis_resident";

    /** Block access (world coordinates); a WorldGenLevel in game, a plain array in offline tests. */
    public interface Grid {
        /** Y of the highest motion-blocking block in the column. */
        int surface(int x, int z);

        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);

        /**
         * Spawns a persistent villager standing at this position (no-op in offline tests). A nitwit never takes a
         * job, so city residents cannot become traders and finding a real village keeps its value.
         */
        default void villager(double x, double y, double z, boolean nitwit) {
            villager(x, y, z, nitwit, null);
        }

        /**
         * As above, with the head block of the villager's bed: it is written into the brain as the home, so the
         * villager never runs the bed search (a scan of every section of every chunk within 48 blocks, which
         * with hundreds of residents in a 254-section world brought the server thread to its knees).
         */
        default void villager(double x, double y, double z, boolean nitwit, BlockPos bedHead) {
        }
    }

    public static Grid of(WorldGenLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new Grid() {
            @Override
            public int surface(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return level.getBlockState(pos.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                level.setBlock(pos.set(x, y, z), state, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
            }

            @Override
            public void villager(double x, double y, double z, boolean nitwit, BlockPos bedHead) {
                net.minecraft.world.entity.npc.villager.Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(
                        level.getLevel(), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
                if (v == null) return;
                v.snapTo(x, y, z, (float) (ColumnPainter.hash((int) x, (int) z, 0x7A11) % 360L), 0.0f);
                BlockPos at = BlockPos.containing(x, y, z);
                v.finalizeSpawn(level, level.getCurrentDifficultyAt(at), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE, null);
                if (bedHead != null) {
                    v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,
                            net.minecraft.core.GlobalPos.of(level.getLevel().dimension(), bedHead));
                }
                if (nitwit) {
                    v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(),
                            net.minecraft.world.entity.npc.villager.VillagerProfession.NITWIT));
                    v.setVillagerDataFinalized(true);
                    // Saved with the entity; VillagerMixin makes every child of a marked nitwit a nitwit as well.
                    v.addTag(RESIDENT_TAG);
                }
                v.setPersistenceRequired();
                level.addFreshEntityWithPassengers(v);
            }
        };
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS_PANE.defaultBlockState();
    private static final BlockState LANTERN = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState FOUNDATION = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState FLOOR = Blocks.SPRUCE_PLANKS.defaultBlockState();
    private static final BlockState[] HOUSE_WALLS = {
            Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.CONCRETE.white().defaultBlockState(),
            Blocks.BRICKS.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.CONCRETE.yellow().defaultBlockState()};
    private static final BlockState[] HOUSE_ROOFS = {
            Blocks.DEEPSLATE_TILES.defaultBlockState(), Blocks.BRICKS.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState(),
            Blocks.STONE_BRICKS.defaultBlockState()};
    private static final BlockState[] BLOCK_WALLS = {
            Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CONCRETE.gray().defaultBlockState(), Blocks.CONCRETE.white().defaultBlockState(),
            Blocks.BRICKS.defaultBlockState()};
    private static final BlockState[] HALL_WALLS = {
            Blocks.CONCRETE.gray().defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CONCRETE.white().defaultBlockState()};
    private static final BlockState FLAT_ROOF = Blocks.CONCRETE.gray().defaultBlockState();

    private SettlementBuilder() {
    }

    /** True when this world draws towns this way (coarse scale, feature on). */
    public static boolean active(OrbisConfig cfg) {
        return cfg.settlementBuildings && cfg.metersPerBlock >= 8.0;
    }

    /**
     * Builds the lots of one chunk. Returns the chunk cells (lx * 16 + lz) a
     * building or its yard occupies, so the decorator keeps trees off them.
     */
    public static boolean[] place(OrbisConfig cfg, Grid level, RegionRaster r, int minX, int minZ) {
        boolean[] built = new boolean[256];
        if (r == null || !active(cfg)) return built;
        for (int lotX = 0; lotX < 2; lotX++) {
            for (int lotZ = 0; lotZ < 2; lotZ++) {
                int ox = minX + lotX * 8, oz = minZ + lotZ * 8;
                long hl = ColumnPainter.hash(ox, oz, 0x5E77);
                int h = (int) (hl ^ (hl >>> 32));
                int cIdx = r.index(ox + 4, oz + 4);
                if (cIdx < 0) continue;
                Kind kind = switch (r.landCoverAt(cIdx)) {
                    case RESIDENTIAL -> Kind.HOUSE;
                    case COMMERCIAL, RETAIL -> Kind.BLOCK;
                    case INDUSTRIAL -> Kind.HALL;
                    default -> null;
                };
                if (kind == null) continue;
                double chance = kind == Kind.HOUSE ? 0.55 : kind == Kind.BLOCK ? 0.7 : 0.4;
                if (((h >>> 8) & 0xFFFF) / 65536.0 > chance) continue;

                int w = kind == Kind.HALL ? 7 : 5 + (h & 1) + ((h >> 1) & 1);
                int d = kind == Kind.HALL ? 6 + ((h >> 2) & 1) : 5 + ((h >> 2) & 1) + ((h >> 3) & 1);
                int x0 = ox + 1 + Math.floorMod(h >> 4, 8 - w), z0 = oz + 1 + Math.floorMod(h >> 7, 8 - d);
                if (!lotFree(r, x0, z0, w, d)) continue;

                // Ground under the footprint: must be solid land, not too steep, not under leaves or water.
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                boolean ok = true;
                for (int x = x0 - 1; x <= x0 + w && ok; x++) {
                    for (int z = z0 - 1; z <= z0 + d; z++) {
                        int surface = level.surface(x, z);
                        if (surface <= cfg.minY + 2 || surface >= cfg.maxY() - 40) {
                            ok = false;
                            break;
                        }
                        BlockState ground = level.get(x, surface, z);
                        if (!ground.getFluidState().isEmpty() || !level.get(x, surface + 1, z).getFluidState().isEmpty()
                                || ground.getBlock() instanceof LeavesBlock || ground.is(Blocks.SEA_LANTERN) || !ground.isSolid()) {
                            ok = false;
                            break;
                        }
                        minY = Math.min(minY, surface);
                        maxY = Math.max(maxY, surface);
                    }
                }
                if (!ok || maxY - minY > 3) continue;

                int base = maxY;
                int storeys = kind == Kind.HOUSE ? 1 + ((h >> 10) & 1) : kind == Kind.BLOCK ? 3 + Math.floorMod(h >> 10, 6) : 1;
                int storeyHeight = kind == Kind.HALL ? 6 : 4;
                BlockState wall = kind == Kind.HOUSE ? HOUSE_WALLS[Math.floorMod(h >> 13, HOUSE_WALLS.length)]
                        : kind == Kind.BLOCK ? BLOCK_WALLS[Math.floorMod(h >> 13, BLOCK_WALLS.length)]
                        : HALL_WALLS[Math.floorMod(h >> 13, HALL_WALLS.length)];
                BlockState roof = kind == Kind.HOUSE ? HOUSE_ROOFS[Math.floorMod(h >> 16, HOUSE_ROOFS.length)] : FLAT_ROOF;
                build(level, x0, z0, w, d, minY, base, storeys, storeyHeight, wall, roof, kind);

                // A resident: a bed against the north wall of the ground floor and a villager beside it.
                if (cfg.villagerResidents && kind != Kind.HALL && ((h >>> 20) & 0xFF) / 256.0 < Math.min(1.0, cfg.residentsPerChunk / 2.0)) {
                    int bx = x0 + 1, bz = z0 + 1;
                    BlockState bed = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                    level.set(bx, base + 1, bz, bed.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
                    level.set(bx + 1, base + 1, bz, bed.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
                    level.villager(bx + 0.5, base + 1, bz + 1.5, !cfg.residentJobs, new BlockPos(bx + 1, base + 1, bz));
                }

                for (int x = x0 - 1; x <= x0 + w; x++) {
                    for (int z = z0 - 1; z <= z0 + d; z++) {
                        int lx = x - minX, lz = z - minZ;
                        if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) built[lx * 16 + lz] = true;
                    }
                }
            }
        }
        return built;
    }

    private static boolean lotFree(RegionRaster r, int x0, int z0, int w, int d) {
        for (int x = x0 - 1; x <= x0 + w; x++) {
            for (int z = z0 - 1; z <= z0 + d; z++) {
                int idx = r.index(x, z);
                if (idx < 0) return false;
                if (r.road[idx] != 0 || r.water[idx] != 0 || r.building[idx] != 0 || (r.hasCoastline && r.isSea(idx))) return false;
                LandCover lc = r.landCoverAt(idx);
                if (lc == LandCover.GLACIER || lc == LandCover.WETLAND) return false;
            }
        }
        return true;
    }

    private static void build(Grid level, int x0, int z0, int w, int d, int minY, int base,
                              int storeys, int storeyHeight, BlockState wall, BlockState roof, Kind kind) {
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        int wallTop = base + storeys * storeyHeight; // last wall block; ceiling/roof starts above
        // Foundation and interior floor.
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = minY; y < base; y++) set(level, x, y, z, FOUNDATION);
                set(level, x, base, z, FLOOR);
            }
        }
        // Walls, interior air, floors per storey, windows, lanterns.
        for (int y = base + 1; y <= wallTop; y++) {
            int storeyBase = base + ((y - base - 1) / storeyHeight) * storeyHeight; // floor block of this storey
            int rel = y - storeyBase;                                                // 1..storeyHeight
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    BlockState state;
                    if (edge) {
                        boolean windowRow = rel == 2 || (storeyHeight == 6 && rel == 3);
                        boolean windowCol = !corner && ((x == x0 || x == x1) ? Math.floorMod(z - z0, 2) == 1 : Math.floorMod(x - x0, 2) == 1);
                        state = windowRow && windowCol ? GLASS : wall;
                    } else if (rel == storeyHeight && y != wallTop) {
                        // slab between storeys, lit from below
                        state = x == (x0 + x1) / 2 && z == (z0 + z1) / 2 ? LANTERN : FLOOR;
                    } else {
                        state = AIR;
                    }
                    set(level, x, y, z, state);
                }
            }
        }
        // Door on the south side.
        int doorX = (x0 + x1) / 2;
        set(level, doorX, base + 1, z1, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        set(level, doorX, base + 2, z1, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        // Roof: ceiling with a lantern, then either a stepped pitched roof (houses) or a flat slab with a parapet.
        int ceiling = wallTop + 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(level, x, ceiling, z, x == (x0 + x1) / 2 && z == (z0 + z1) / 2 ? LANTERN : roof);
            }
        }
        if (kind == Kind.HOUSE) {
            boolean alongX = w >= d; // ridge runs along the longer side
            for (int step = 1; ; step++) {
                int ax = alongX ? x0 : x0 + step, bx = alongX ? x1 : x1 - step;
                int az = alongX ? z0 + step : z0, bz = alongX ? z1 - step : z1;
                if (ax > bx || az > bz) break;
                for (int x = ax; x <= bx; x++) {
                    for (int z = az; z <= bz; z++) set(level, x, ceiling + step, z, roof);
                }
            }
        } else {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (edge) set(level, x, ceiling + 1, z, wall);
                }
            }
        }
    }

    private static void set(Grid level, int x, int y, int z, BlockState state) {
        level.set(x, y, z, state);
    }
}
