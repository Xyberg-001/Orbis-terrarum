package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Furniture by what a building is used for, so that walking into it is worth it. The use comes from the
 * building's own tags or, far more often, from the business mapped as a point inside it (OpenStreetMap tags
 * the shop, not the house). Libraries get bookshelves and a lectern; schools desks facing a lectern;
 * fishmongers and market halls barrels with fish on the walls; pharmacies brewing stands; hospitals white
 * beds on every floor; hotels beds on every floor; cafés and restaurants tables with chairs; bars and pubs a
 * counter with stools; nightclubs a dance floor, lamps, a jukebox and a bar; theatres and cinemas rows of
 * seats facing a screen; museums pedestals with exhibits and framed art; supermarkets aisles of shelves;
 * gyms weights, anvils and targets; banks a vault of iron bars with a chest; churches pews and a bell; shops
 * shelves with their goods framed and, in one shop in four, a chest with the matching vanilla village loot;
 * offices desks.
 *
 * Runs after the residents, keeps clear of their beds and staircase, and only touches the building's own
 * interior cells. Each building is furnished once, by the chunk holding its anchor cell.
 */
public final class Interiors {

    enum Kind { NONE, LIBRARY, SCHOOL, FISH, PHARMACY, HOSPITAL, HOTEL, FOOD, BAR, NIGHTCLUB, THEATRE, MUSEUM, SUPERMARKET, GYM, BANK, CHURCH, SHOP, OFFICE }

    private static final BlockState[] HOTEL_BEDS = {
            Blocks.BED.white().defaultBlockState(), Blocks.BED.lightBlue().defaultBlockState(), Blocks.BED.lightGray().defaultBlockState()};
    private static final Item[] EXHIBITS = {
            Items.NAUTILUS_SHELL, Items.BONE, Items.ANCIENT_DEBRIS, Items.AMETHYST_SHARD, Items.PRISMARINE_SHARD, Items.HEART_OF_THE_SEA,
            Items.GOLDEN_HELMET, Items.IRON_SWORD, Items.BOW, Items.TRIDENT, Items.ENCHANTED_BOOK, Items.GOAT_HORN, Items.ECHO_SHARD,
            Items.ANGLER_POTTERY_SHERD, Items.ARCHER_POTTERY_SHERD, Items.SKULL_POTTERY_SHERD, Items.TURTLE_SCUTE, Items.WITHER_SKELETON_SKULL,
            Items.CREEPER_HEAD, Items.NETHER_STAR, Items.CHAINMAIL_CHESTPLATE, Items.GOLDEN_SWORD, Items.LAPIS_LAZULI, Items.MUSIC_DISC_13,
            Items.SHIELD, Items.CROSSBOW, Items.SPYGLASS, Items.COMPASS, Items.CLOCK, Items.FILLED_MAP};
    private static final Item[] ART = {Items.PAINTING, Items.MUSIC_DISC_CAT, Items.GLOW_INK_SAC, Items.POPPY, Items.EMERALD, Items.DIAMOND, Items.BOOK};

    private Interiors() {
    }

    /** Shop goods worth taking: an older world's frames holding these are locked if they are this mod's. */
    private static final java.util.Set<Item> VALUABLE_GOODS = java.util.Set.of(
            Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.IRON_PICKAXE, Items.REDSTONE);

    /**
     * Worlds furnished before display frames were generated fixed: when a frame loads, it is locked if it is exactly
     * one this mod made. A museum exhibit and a wall picture are recognised by position, facing and item (each spot
     * always gets the same item, from a hash of its position; an exhibit stands on a quartz pillar); shop goods by
     * the building's mapped shop matching the item. A player's own frames do not match.
     */
    public static void lockOldDisplay(ItemFrame frame, com.berg.orbis.worldgen.WorldModel model, net.minecraft.server.MinecraftServer server) {
        com.berg.orbis.mixin.ItemFrameAccessor acc = (com.berg.orbis.mixin.ItemFrameAccessor) frame;
        if (acc.orbis$isFixed()) return;
        ItemStack stack = frame.getItem();
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        BlockPos p = frame.getPos();
        int x = p.getX(), z = p.getZ();
        Direction facing = frame.getDirection();
        boolean exhibit = facing == Direction.UP
                && item == EXHIBITS[(int) Math.floorMod(ColumnPainter.hash(x, z, 0xE7B), (long) EXHIBITS.length)]
                && frame.level().getBlockState(p.below()).is(Blocks.QUARTZ_PILLAR);
        boolean picture = facing.getAxis().isHorizontal()
                && item == ART[(int) Math.floorMod(ColumnPainter.hash(x, z, 0xA27), (long) ART.length)];
        if (exhibit || picture) {
            acc.orbis$setFixed(true);
            return;
        }
        if (!facing.getAxis().isHorizontal() || !VALUABLE_GOODS.contains(item) || model == null || model.regions() == null) return;
        model.regions().futureForBlock(x, z).thenAccept(r -> server.execute(() -> {
            if (frame.isRemoved() || r == null) return;
            int idx = r.index(x, z);
            BuildingFeature bf = idx < 0 ? null : r.buildingAt(idx);
            if (bf != null && frame.getItem().is(item) && goodsFor(useOf(bf)) == item) acc.orbis$setFixed(true);
        }));
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.furnishInteriors || !cfg.hollowBuildings || !cfg.generateBuildings || cfg.metersPerBlock > 2.0) return;
        Map<Long, BuildingFeature> buildings = new LinkedHashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int idx = r.index(minX + lx, minZ + lz);
                if (idx < 0) continue;
                BuildingFeature bf = r.buildingAt(idx);
                if (bf != null && !bf.part && bf.minHeightBlocks == 0 && bf.heightBlocks >= 3) buildings.putIfAbsent(bf.id, bf);
            }
        }
        for (BuildingFeature bf : buildings.values()) {
            Map<String, String> use = useOf(bf);
            Kind kind = use == null ? Kind.NONE : classify(use);
            if (kind == Kind.NONE) continue;
            int[] anchor = Residents.anchor(r, bf);
            if (anchor == null) continue;
            if (anchor[0] < minX || anchor[0] >= minX + 16 || anchor[1] < minZ || anchor[1] >= minZ + 16) continue;
            try {
                new Room(cfg, level, r, bf, anchor, minX, minZ).furnish(kind, use);
            } catch (RuntimeException e) {
                System.err.println("[orbis] Interior failed for building " + bf.id + ": " + e);
            }
        }
    }

    /** The tag set that decides the furniture: the building's own tags if they say a use, else the first business inside. */
    static Map<String, String> useOf(BuildingFeature bf) {
        if (bf.tags != null && classify(bf.tags) != Kind.NONE) return bf.tags;
        for (Map<String, String> poi : bf.pois) {
            if (classify(poi) != Kind.NONE) return poi;
        }
        return null;
    }

    static Kind classify(Map<String, String> t) {
        if (t == null) return Kind.NONE;
        String shop = t.getOrDefault("shop", ""), amenity = t.getOrDefault("amenity", ""), building = t.getOrDefault("building", "");
        String tourism = t.getOrDefault("tourism", ""), office = t.getOrDefault("office", ""), healthcare = t.getOrDefault("healthcare", "");
        String leisure = t.getOrDefault("leisure", ""), club = t.getOrDefault("club", "");
        switch (amenity) {
            case "library", "archive": return Kind.LIBRARY;
            case "school", "university", "college", "kindergarten", "language_school", "music_school", "driving_school": return Kind.SCHOOL;
            case "marketplace": return Kind.FISH;
            case "pharmacy": return Kind.PHARMACY;
            case "hospital", "clinic", "doctors", "dentist", "nursing_home", "veterinary": return Kind.HOSPITAL;
            case "cafe", "restaurant", "fast_food", "food_court", "ice_cream", "canteen": return Kind.FOOD;
            case "bar", "pub", "biergarten": return Kind.BAR;
            case "nightclub", "stripclub", "casino", "gambling": return Kind.NIGHTCLUB;
            case "theatre", "cinema", "events_venue", "concert_hall", "conference_centre", "exhibition_centre", "community_centre": return Kind.THEATRE;
            case "arts_centre": return Kind.MUSEUM;
            case "place_of_worship", "monastery": return Kind.CHURCH;
            case "bank", "bureau_de_change", "money_transfer": return Kind.BANK;
            case "gym", "dojo": return Kind.GYM;
            case "post_office", "townhall", "courthouse", "police", "coworking_space", "social_facility", "public_building", "embassy": return Kind.OFFICE;
            default: break;
        }
        switch (shop) {
            case "": break;
            case "books", "stationery", "newsagent": return Kind.LIBRARY;
            case "seafood", "fishmonger", "fishing": return Kind.FISH;
            case "chemist", "pharmacy", "medical_supply", "herbalist", "optician", "hearing_aids": return Kind.PHARMACY;
            case "coffee", "tea", "bakery", "pastry", "deli", "confectionery", "chocolate": return Kind.FOOD;
            case "supermarket", "convenience", "grocery", "general", "department_store", "mall", "variety_store", "wholesale", "greengrocer", "frozen_food": return Kind.SUPERMARKET;
            case "alcohol", "wine", "beverages": return Kind.BAR;
            case "fitness", "sports": return Kind.GYM;
            default: return Kind.SHOP;
        }
        switch (tourism) {
            case "hotel", "hostel", "guest_house", "motel", "apartment", "chalet": return Kind.HOTEL;
            case "museum", "gallery", "aquarium": return Kind.MUSEUM; // not "attraction": that is the whole of Bryggen
            default: break;
        }
        switch (leisure) {
            case "fitness_centre", "sports_centre", "sports_hall", "fitness_station", "bowling_alley", "ice_rink", "swimming_pool", "climbing": return Kind.GYM;
            case "dance", "adult_gaming_centre", "amusement_arcade", "escape_game": return Kind.NIGHTCLUB;
            default: break;
        }
        if (!club.isEmpty()) return Kind.BAR;
        if (!healthcare.isEmpty()) return Kind.HOSPITAL;
        switch (building) {
            case "library", "museum": return Kind.LIBRARY;
            case "school", "university", "college", "kindergarten": return Kind.SCHOOL;
            case "hospital": return Kind.HOSPITAL;
            case "hotel", "dormitory": return Kind.HOTEL;
            case "church", "chapel", "cathedral", "mosque", "temple", "synagogue", "monastery": return Kind.CHURCH;
            case "retail", "supermarket", "kiosk", "mall": return Kind.SHOP;
            case "office", "commercial", "government", "public", "civic": return Kind.OFFICE;
            case "sports_hall", "sports_centre", "stadium", "grandstand": return Kind.GYM;
            default: break;
        }
        if (!office.isEmpty()) return Kind.OFFICE;
        return Kind.NONE;
    }

    /** One building's interior, with the cell lists the furnishing needs. */
    private static final class Room {
        final OrbisConfig cfg;
        final WorldGenLevel level;
        final RegionRaster r;
        final BuildingFeature bf;
        final int ax, az;
        final int loX, hiX, loZ, hiZ; // writable window of this chunk's decoration pass
        final int base, storey, wallTop;
        final long h;
        final boolean alongX;         // the footprint's long axis
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        /** Interior cells against a wall: {x, z, dirX, dirZ} with the direction pointing at the wall. */
        final List<int[]> wallCells = new ArrayList<>();
        /** Interior cells with no wall on any side. */
        final List<int[]> openCells = new ArrayList<>();

        Room(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, BuildingFeature bf, int[] anchor, int minX, int minZ) {
            this.cfg = cfg;
            this.level = level;
            this.r = r;
            this.bf = bf;
            this.ax = anchor[0];
            this.az = anchor[1];
            this.loX = minX - 14;
            this.hiX = minX + 29;
            this.loZ = minZ - 14;
            this.hiZ = minZ + 29;
            this.base = Math.max(cfg.minY + 1, Math.min(cfg.maxY() - 1, bf.baseY));
            this.storey = Math.max(2, bf.storeyBlocks);
            this.wallTop = Math.min(cfg.maxY() - 1, base + bf.heightBlocks);
            this.h = ColumnPainter.hash(ax, az, bf.id ^ 0xF00DL);
            this.alongX = Math.abs(bf.axisX) >= Math.abs(bf.axisZ);
            for (int x = Math.max(loX, ax - 24); x <= Math.min(hiX, ax + 24); x++) {
                for (int z = Math.max(loZ, az - 24); z <= Math.min(hiZ, az + 24); z++) {
                    if (!Residents.interior(r, x, z, bf)) continue;
                    int[] wall = null;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int i = r.index(x + d.getStepX(), z + d.getStepZ());
                        if (i < 0) continue;
                        BuildingFeature nb = r.buildingAt(i);
                        if (nb != null && nb.id == bf.id && (r.buildingFlags[i] & RegionRaster.FLAG_EDGE) != 0) {
                            wall = new int[]{x, z, d.getStepX(), d.getStepZ()};
                            break;
                        }
                    }
                    if (wall != null) wallCells.add(wall);
                    else openCells.add(new int[]{x, z});
                }
            }
        }

        int floorY(int k) {
            return k == 0 ? base : base + 1 + k * storey;
        }

        boolean hasRoom(int k) {
            return floorY(k) + 3 <= wallTop;
        }

        /** The floor slab over room k (the floor of the room above), or -1 when the roof is next. */
        int ceilingY(int k) {
            int s = k == 0 ? base + 1 + storey : floorY(k) + storey;
            return s < wallTop ? s : -1;
        }

        BlockState get(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        void set(int x, int y, int z, BlockState s) {
            if (y <= cfg.minY || y >= cfg.maxY()) return;
            level.setBlock(pos.set(x, y, z), s, 3);
        }

        /** Solid floor, two blocks of air, and not next to the staircase, a hole in the floor or a bed. */
        boolean free(int x, int z, int floorY) {
            BlockState floor = get(x, floorY, z);
            if (!floor.isSolid() || floor.getBlock() instanceof BedBlock || floor.getBlock() instanceof StairBlock) return false;
            if (!get(x, floorY + 1, z).isAir() || !get(x, floorY + 2, z).isAir()) return false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockState a = get(x + dx, floorY + 1, z + dz);
                    if (a.getBlock() instanceof StairBlock || a.getBlock() instanceof BedBlock) return false;
                    if (get(x + dx, floorY, z + dz).isAir()) return false; // stair hole
                }
            }
            return true;
        }

        void furnish(Kind kind, Map<String, String> use) {
            switch (kind) {
                case LIBRARY -> {
                    lineWalls(0, 1, 2, Blocks.BOOKSHELF.defaultBlockState(), null, false);
                    if (hasRoom(1)) lineWalls(1, 2, 1, Blocks.BOOKSHELF.defaultBlockState(), null, false);
                    placeNearAnchor(0, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.from2DDataValue((int) (h % 4))));
                }
                case SCHOOL -> {
                    for (int k = 0; k < 2 && hasRoom(k); k++) {
                        classroom(k);
                        lineWalls(k, 3, 1, Blocks.BOOKSHELF.defaultBlockState(), null, false);
                    }
                }
                case FISH -> {
                    lineWalls(0, 1, 1, Blocks.BARREL.defaultBlockState(), Items.COD, true);
                    placeNearAnchor(0, Blocks.SMOKER.defaultBlockState());
                }
                case PHARMACY -> {
                    lineWalls(0, 2, 1, Blocks.BREWING_STAND.defaultBlockState(), Items.POTION, false);
                    placeNearAnchor(0, Blocks.CAULDRON.defaultBlockState());
                }
                case HOSPITAL -> {
                    for (int k = 0; k < 3 && hasRoom(k); k++) {
                        beds(k, 2, Blocks.BED.white().defaultBlockState());
                        lineWalls(k, 7, 1, Blocks.BREWING_STAND.defaultBlockState(), null, false);
                    }
                    placeNearAnchor(0, Blocks.CAULDRON.defaultBlockState());
                }
                case HOTEL -> {
                    for (int k = 0; k < 4 && hasRoom(k); k++) beds(k, 3, null);
                }
                case FOOD -> {
                    tables(0, 3);
                    lineWalls(0, 3, 1, Blocks.BARREL.defaultBlockState(), Items.BREAD, false);
                    placeNearAnchor(0, Blocks.SMOKER.defaultBlockState());
                }
                case BAR -> {
                    counter(0);
                    tables(0, 4);
                    placeNearAnchor(0, Blocks.BREWING_STAND.defaultBlockState());
                }
                case NIGHTCLUB -> {
                    danceFloor(0);
                    counter(0);
                    lineWalls(0, 2, 1, Blocks.NOTE_BLOCK.defaultBlockState(), null, false);
                }
                case THEATRE -> theatre(0);
                case MUSEUM -> {
                    exhibits(0);
                    lineWalls(0, 2, 0, null, null, true);
                    if (hasRoom(1)) {
                        exhibits(1);
                        lineWalls(1, 2, 0, null, null, true);
                    }
                }
                case SUPERMARKET -> {
                    aisles(0);
                    lineWalls(0, 2, 2, Blocks.BARREL.defaultBlockState(), Items.APPLE, false);
                    lootChest(0, BuiltInLootTables.VILLAGE_PLAINS_HOUSE);
                }
                case GYM -> gym(0);
                case BANK -> {
                    vault(0);
                    tables(0, 4);
                }
                case CHURCH -> {
                    pews(0);
                    placeNearAnchor(0, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR));
                }
                case SHOP -> {
                    Item goods = goodsFor(use);
                    lineWalls(0, 1, 1, Blocks.BARREL.defaultBlockState(), goods, true);
                    if (((h >>> 20) & 3) == 0) lootChest(0, lootFor(use));
                }
                case OFFICE -> {
                    tables(0, 4);
                    lineWalls(0, 4, 1, Blocks.BOOKSHELF.defaultBlockState(), null, false);
                }
                default -> { }
            }
        }

        /**
         * Furniture on every {@code spacing}th wall cell of floor k, {@code height} blocks high (0 = nothing but the
         * frame), with an item frame above it: on every one, or on a third of them.
         */
        void lineWalls(int k, int spacing, int height, BlockState block, Item framed, boolean everyFrame) {
            int floorY = floorY(k);
            int n = 0;
            for (int[] c : wallCells) {
                n++;
                if (n % spacing != 0) continue;
                if (!free(c[0], c[1], floorY)) continue;
                for (int i = 1; i <= height && floorY + i + 1 < wallTop; i++) set(c[0], floorY + i, c[1], block);
                Item item = framed;
                if (item == null && height == 0) item = ART[(int) Math.floorMod(ColumnPainter.hash(c[0], c[1], 0xA27), (long) ART.length)];
                if (item != null && (everyFrame || (ColumnPainter.hash(c[0], c[1], 0xF7A) % 3) == 0)) {
                    frame(c[0], floorY + height + 1, c[1], Direction.getNearest(-c[2], 0, -c[3], Direction.EAST), item);
                }
            }
        }

        /** An item frame at (x, y, z) facing {@code facing}, hanging on the block behind it. */
        void frame(int x, int y, int z, Direction facing, Item item) {
            if (y + 1 >= wallTop) return;
            if (!get(x, y, z).isAir() || !get(x - facing.getStepX(), y - facing.getStepY(), z - facing.getStepZ()).isSolid()) return;
            ItemFrame frame = new ItemFrame(level.getLevel(), new BlockPos(x, y, z), facing);
            // Never touch the real ServerLevel from a worldgen thread (frame.survives(), the comparator update of
            // setItem): both wait on the server thread for the chunk being generated. Silent, no neighbour update.
            frame.setSilent(true);
            frame.setItem(new ItemStack(item), false);
            frame.setSilent(false);
            // A display, like the frames in vanilla's structures: it cannot be emptied, turned or broken in survival
            // (otherwise every museum hands out nether stars and every jeweller diamonds).
            ((com.berg.orbis.mixin.ItemFrameAccessor) frame).orbis$setFixed(true);
            level.addFreshEntity(frame);
        }

        /** Tables (fence + pressure plate) with a chair on two sides, on a grid over the open floor. */
        void tables(int k, int pitch) {
            int floorY = floorY(k);
            for (int[] c : openCells) {
                if (Math.floorMod(c[0], pitch) != 1 || Math.floorMod(c[1], pitch) != 1) continue;
                if (!free(c[0], c[1], floorY) || !free(c[0] + 1, c[1], floorY) || !free(c[0] - 1, c[1], floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.OAK_FENCE.defaultBlockState());
                set(c[0], floorY + 2, c[1], Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
                set(c[0] + 1, floorY + 1, c[1], Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                set(c[0] - 1, floorY + 1, c[1], Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            }
        }

        /** Desks in rows, all facing the lectern at the front. */
        void classroom(int k) {
            int floorY = floorY(k);
            Direction front = alongX ? Direction.EAST : Direction.SOUTH;
            for (int[] c : openCells) {
                int along = alongX ? c[0] : c[1], across = alongX ? c[1] : c[0];
                if (Math.floorMod(along, 3) != 0 || Math.floorMod(across, 2) != 0) continue;
                int sx = c[0] - front.getStepX(), sz = c[1] - front.getStepZ();
                if (!free(c[0], c[1], floorY) || !free(sx, sz, floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.OAK_FENCE.defaultBlockState());
                set(c[0], floorY + 2, c[1], Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
                set(sx, floorY + 1, sz, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, front));
            }
            placeNearAnchor(k, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, front.getOpposite()));
        }

        /** A bar: barrels along the longest run of wall with stools in front, and bottles in frames above. */
        void counter(int k) {
            int floorY = floorY(k);
            int[] best = null;
            int[] counts = new int[4];
            for (int[] c : wallCells) counts[Direction.getNearest(c[2], 0, c[3], Direction.EAST).get2DDataValue()]++;
            int dir = 0;
            for (int i = 1; i < 4; i++) if (counts[i] > counts[dir]) dir = i;
            Direction wall = Direction.from2DDataValue(dir);
            int n = 0;
            for (int[] c : wallCells) {
                if (c[2] != wall.getStepX() || c[3] != wall.getStepZ()) continue;
                n++;
                if (!free(c[0], c[1], floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.BARREL.defaultBlockState());
                if (n % 2 == 0) frame(c[0], floorY + 2, c[1], wall.getOpposite(), Items.GLASS_BOTTLE);
                int sx = c[0] - 2 * wall.getStepX(), sz = c[1] - 2 * wall.getStepZ();
                if (n % 2 == 1 && free(sx, sz, floorY)) {
                    set(sx, floorY + 1, sz, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, wall));
                }
            }
        }

        /** A chequered dance floor around the anchor with lamps in the ceiling above it, and a jukebox. */
        void danceFloor(int k) {
            int floorY = floorY(k);
            int ceiling = ceilingY(k);
            BlockState a = Blocks.GLAZED_TERRACOTTA.magenta().defaultBlockState(), b = Blocks.GLAZED_TERRACOTTA.black().defaultBlockState();
            BlockState c2 = Blocks.GLAZED_TERRACOTTA.purple().defaultBlockState();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int x = ax + dx, z = az + dz;
                    if (x < loX || x > hiX || z < loZ || z > hiZ || !Residents.interior(r, x, z, bf)) continue;
                    if (!get(x, floorY, z).isSolid() || get(x, floorY, z).getBlock() instanceof StairBlock) continue;
                    set(x, floorY, z, ((dx + dz) & 1) == 0 ? a : (((dx * 3 + dz) % 5 + 5) % 5 == 0 ? c2 : b));
                    if (ceiling > 0 && (Math.floorMod(dx, 3) == 0 && Math.floorMod(dz, 3) == 0)) {
                        set(x, ceiling, z, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
                    }
                }
            }
            for (int d = 4; d <= 6; d++) {
                int x = ax + d, z = az;
                if (x <= hiX && Residents.interior(r, x, z, bf) && free(x, z, floorY)) {
                    set(x, floorY + 1, z, Blocks.JUKEBOX.defaultBlockState());
                    break;
                }
            }
        }

        /** Rows of seats facing a black screen on the far wall, with a red carpet down the aisle. */
        void theatre(int k) {
            int floorY = floorY(k);
            Direction front = alongX ? ((h & 2) == 0 ? Direction.EAST : Direction.WEST) : ((h & 2) == 0 ? Direction.SOUTH : Direction.NORTH);
            for (int[] c : wallCells) {
                if (c[2] != front.getStepX() || c[3] != front.getStepZ()) continue;
                if (!free(c[0], c[1], floorY)) continue;
                for (int i = 1; i <= 3 && floorY + i < wallTop; i++) set(c[0], floorY + i, c[1], Blocks.CONCRETE.black().defaultBlockState());
            }
            int aisle = alongX ? az : ax;
            for (int[] c : openCells) {
                int along = alongX ? c[0] : c[1], across = alongX ? c[1] : c[0];
                if (!free(c[0], c[1], floorY)) continue;
                if (across == aisle) {
                    set(c[0], floorY + 1, c[1], Blocks.CARPET.red().defaultBlockState());
                } else if (Math.floorMod(along, 2) == 0) {
                    set(c[0], floorY + 1, c[1], Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, front));
                }
            }
        }

        /** Exhibits on quartz pedestals under a frame, spaced over the floor. */
        void exhibits(int k) {
            int floorY = floorY(k);
            for (int[] c : openCells) {
                if (Math.floorMod(c[0], 4) != 2 || Math.floorMod(c[1], 4) != 2) continue;
                if (!free(c[0], c[1], floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.QUARTZ_PILLAR.defaultBlockState());
                Item item = EXHIBITS[(int) Math.floorMod(ColumnPainter.hash(c[0], c[1], 0xE7B), (long) EXHIBITS.length)];
                frame(c[0], floorY + 2, c[1], Direction.UP, item);
            }
        }

        /** Shelves in rows across the shop with an aisle between each pair. */
        void aisles(int k) {
            int floorY = floorY(k);
            for (int[] c : openCells) {
                int across = alongX ? c[1] : c[0];
                if (Math.floorMod(across, 3) != 1) continue;
                if (!free(c[0], c[1], floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.BARREL.defaultBlockState());
                set(c[0], floorY + 2, c[1], Blocks.BARREL.defaultBlockState());
            }
        }

        /** Weights on the floor, punching bags and targets along the walls, an anvil or two. */
        void gym(int k) {
            int floorY = floorY(k);
            for (int[] c : openCells) {
                if (Math.floorMod(c[0], 4) != 1 || Math.floorMod(c[1], 4) != 1) continue;
                if (!free(c[0], c[1], floorY)) continue;
                long hh = ColumnPainter.hash(c[0], c[1], 0x6E3);
                set(c[0], floorY + 1, c[1], hh % 4 == 0 ? Blocks.ANVIL.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState());
            }
            int n = 0;
            for (int[] c : wallCells) {
                n++;
                if (n % 3 != 0 || !free(c[0], c[1], floorY)) continue;
                if (n % 6 == 0) {
                    set(c[0], floorY + 2, c[1], Blocks.HAY_BLOCK.defaultBlockState());
                } else {
                    set(c[0], floorY + 2, c[1], Blocks.TARGET.defaultBlockState());
                }
            }
        }

        /** A vault of iron bars round a chest, with one way in. */
        void vault(int k) {
            int floorY = floorY(k);
            int[] centre = null;
            for (int d = 2; d <= 6 && centre == null; d++) {
                for (int[] c : openCells) {
                    if (Math.abs(c[0] - ax) + Math.abs(c[1] - az) != d) continue;
                    boolean ok = true;
                    for (int dx = -1; dx <= 1 && ok; dx++) {
                        for (int dz = -1; dz <= 1 && ok; dz++) ok = free(c[0] + dx, c[1] + dz, floorY) && Residents.interior(r, c[0] + dx, c[1] + dz, bf);
                    }
                    if (ok) {
                        centre = c;
                        break;
                    }
                }
            }
            if (centre == null) return;
            Direction gap = Direction.from2DDataValue((int) (h % 4));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (dx == gap.getStepX() && dz == gap.getStepZ()) continue;
                    for (int y = 1; y <= 2 && floorY + y < wallTop; y++) set(centre[0] + dx, floorY + y, centre[1] + dz, Blocks.IRON_BARS.defaultBlockState());
                }
            }
            set(centre[0], floorY + 1, centre[1], Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, gap));
            if (level.getBlockEntity(pos.set(centre[0], floorY + 1, centre[1])) instanceof RandomizableContainerBlockEntity chest) {
                chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
                chest.setLootTableSeed(h ^ 0xBA4CL);
                chest.setChanged();
            }
        }

        /** Rows of pews across the nave, every other row, all facing the same way. */
        void pews(int k) {
            int floorY = floorY(k);
            Direction facing = alongX ? Direction.EAST : Direction.SOUTH;
            for (int[] c : openCells) {
                int along = alongX ? c[0] : c[1], across = alongX ? c[1] : c[0];
                if (Math.floorMod(along, 2) != 0 || Math.floorMod(across, 5) == 2) continue; // an aisle every fifth cell
                if (Math.abs(c[0] - ax) + Math.abs(c[1] - az) < 3) continue;                // the bell's space
                if (!free(c[0], c[1], floorY)) continue;
                set(c[0], floorY + 1, c[1], Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
            }
        }

        /** Beds against the wall every {@code spacing}th wall cell, head toward the wall; null colour = hotel colours. */
        void beds(int k, int spacing, BlockState colour) {
            int floorY = floorY(k);
            int n = 0;
            for (int[] c : wallCells) {
                n++;
                if (n % spacing != 0) continue;
                int hx = c[0], hz = c[1];                        // head cell against the wall
                int fx = hx - c[2], fz = hz - c[3];              // foot cell one step into the room
                if (!free(hx, hz, floorY) || !free(fx, fz, floorY)) continue;
                if (!Residents.interior(r, fx, fz, bf)) continue;
                Direction facing = Direction.getNearest(c[2], 0, c[3], Direction.EAST);
                BlockState bed = (colour != null ? colour : HOTEL_BEDS[(int) Math.floorMod(ColumnPainter.hash(hx, hz, 0xBED), (long) HOTEL_BEDS.length)])
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                set(fx, floorY + 1, fz, bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
                set(hx, floorY + 1, hz, bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
            }
        }

        void placeNearAnchor(int k, BlockState block) {
            int floorY = floorY(k);
            for (int d = 0; d <= 4; d++) {
                for (int dx = -d; dx <= d; dx++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        int x = ax + dx, z = az + dz;
                        if (x < loX || x > hiX || z < loZ || z > hiZ) continue;
                        if (!Residents.interior(r, x, z, bf) || !free(x, z, floorY)) continue;
                        set(x, floorY + 1, z, block);
                        return;
                    }
                }
            }
        }

        void lootChest(int k, ResourceKey<LootTable> table) {
            int floorY = floorY(k);
            if (wallCells.isEmpty()) return;
            int start = (int) ((h >>> 12) % wallCells.size());
            for (int i = 0; i < wallCells.size(); i++) {
                int[] c = wallCells.get((start + i) % wallCells.size());
                if (!free(c[0], c[1], floorY)) continue;
                Direction facing = Direction.getNearest(-c[2], 0, -c[3], Direction.EAST);
                set(c[0], floorY + 1, c[1], Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
                if (level.getBlockEntity(pos.set(c[0], floorY + 1, c[1])) instanceof RandomizableContainerBlockEntity chest) {
                    chest.setLootTable(table);
                    chest.setLootTableSeed(h ^ (c[0] * 31L) ^ (c[1] * 17L));
                    chest.setChanged();
                }
                return;
            }
        }
    }

    /** What a shop of this kind shows in its item frames. */
    static Item goodsFor(Map<String, String> t) {
        String shop = t == null ? "" : t.getOrDefault("shop", "");
        String amenity = t == null ? "" : t.getOrDefault("amenity", "");
        if ("bank".equals(amenity)) return Items.GOLD_INGOT;
        return switch (shop) {
            case "supermarket", "convenience", "greengrocer", "grocery", "general" -> Items.APPLE;
            case "bakery", "pastry" -> Items.BREAD;
            case "butcher", "deli" -> Items.COOKED_BEEF;
            case "clothes", "fabric", "tailor", "boutique", "sewing" -> Items.LEATHER;
            case "shoes", "leather", "bag" -> Items.LEATHER_BOOTS;
            case "hardware", "doityourself", "trade", "tools" -> Items.IRON_PICKAXE;
            case "electronics", "computer", "mobile_phone", "hifi" -> Items.REDSTONE;
            case "jewelry", "watches" -> Items.DIAMOND;
            case "florist", "garden_centre" -> Items.POPPY;
            case "books", "stationery", "newsagent" -> Items.BOOK;
            case "toys", "games", "video_games" -> Items.CAKE;
            case "bicycle" -> Items.IRON_INGOT;
            case "sports", "outdoor" -> Items.LEATHER_CHESTPLATE;
            case "furniture", "interior_decoration" -> Items.OAK_PLANKS;
            case "art", "frame", "gift" -> Items.PAINTING;
            case "alcohol", "wine", "beverages" -> Items.GLASS_BOTTLE;
            case "chemist", "cosmetics", "beauty", "hairdresser" -> Items.HONEY_BOTTLE;
            case "music", "musical_instrument" -> Items.NOTE_BLOCK;
            case "travel_agency" -> Items.MAP;
            default -> Items.EMERALD;
        };
    }

    /** Vanilla village loot by trade, so a shop chest holds what that kind of shop would. */
    static ResourceKey<LootTable> lootFor(Map<String, String> t) {
        String shop = t == null ? "" : t.getOrDefault("shop", "");
        String amenity = t == null ? "" : t.getOrDefault("amenity", "");
        if ("bank".equals(amenity)) return BuiltInLootTables.SIMPLE_DUNGEON;
        return switch (shop) {
            case "hardware", "doityourself", "trade", "tools", "bicycle" -> BuiltInLootTables.VILLAGE_TOOLSMITH;
            case "weapons", "hunting", "knives" -> BuiltInLootTables.VILLAGE_WEAPONSMITH;
            case "sports", "outdoor" -> BuiltInLootTables.VILLAGE_ARMORER;
            case "clothes", "fabric", "tailor", "boutique", "wool", "sewing" -> BuiltInLootTables.VILLAGE_SHEPHERD;
            case "shoes", "leather", "bag" -> BuiltInLootTables.VILLAGE_TANNERY;
            case "butcher", "deli" -> BuiltInLootTables.VILLAGE_BUTCHER;
            case "seafood", "fishmonger", "fishing" -> BuiltInLootTables.VILLAGE_FISHER;
            case "books", "stationery", "travel_agency", "newsagent" -> BuiltInLootTables.VILLAGE_CARTOGRAPHER;
            case "stone", "tiles", "florist", "garden_centre" -> BuiltInLootTables.VILLAGE_MASON;
            case "archery" -> BuiltInLootTables.VILLAGE_FLETCHER;
            default -> BuiltInLootTables.VILLAGE_PLAINS_HOUSE;
        };
    }
}
