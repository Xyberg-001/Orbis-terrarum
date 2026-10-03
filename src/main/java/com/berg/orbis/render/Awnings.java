package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Map;
import java.util.Set;

/**
 * Fabric awnings over the doors of shops, cafés, restaurants and bars: a row of wool slabs (Minecraft 26.3 and later)
 * hanging from the wall just above the door and its sign, up to two blocks to each side along the shop front. Striped
 * in the shop's colour and white, or plain in one colour, picked from the building so a street is not all one look.
 * Minecraft versions without wool slabs get no awnings.
 */
public final class Awnings {

    /** Kinds of place that usually have an awning (shops of any kind count too). */
    private static final Set<String> AMENITIES = Set.of("cafe", "restaurant", "bar", "pub", "fast_food", "ice_cream", "pharmacy",
            "marketplace", "bakery", "biergarten", "food_court");
    /** Awning colours seen on real shop fronts, as dye names. */
    private static final String[] COLOURS = {"red", "green", "blue", "black", "orange", "brown", "cyan", "yellow", "gray"};
    private static final int HALF_WIDTH = 2;

    private Awnings() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.shopAwnings || !cfg.buildingDoors || !cfg.generateBuildings) return;
        if (slab("white") == null) return; // no wool slabs before 26.3
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0 || (r.buildingFlags[idx] & RegionRaster.FLAG_DOOR) == 0) continue;
                BuildingFeature bf = r.buildingAt(idx);
                if (bf == null || bf.part || !isShopfront(bf)) continue;
                Direction out = ColumnPainter.outwardDirection(r, x, z, idx);
                if (out == null) continue;
                int base = Math.max(cfg.minY + 1, Math.min(cfg.maxY() - 1, bf.baseY));
                int y = base + 4; // above the two-high door and the name sign at base + 3
                if (y + 1 >= cfg.maxY() || bf.heightBlocks < 5) continue;
                long h = Materials.mix(bf.id ^ 0xA3E1);
                String colour = COLOURS[(int) Math.floorMod(h, COLOURS.length)];
                boolean striped = Math.floorMod(h >>> 8, 3) != 0;
                BlockState main = slab(colour), white = slab("white");
                if (main == null) continue;
                Direction along = out.getClockWise();
                for (int k = -HALF_WIDTH; k <= HALF_WIDTH; k++) {
                    int wx = x + along.getStepX() * k, wz = z + along.getStepZ() * k;
                    int widx = r.index(wx, wz);
                    // Only along this building's own wall, where the wall is there to hang it on.
                    if (widx < 0 || r.building[widx] != r.building[idx] || (r.buildingFlags[widx] & RegionRaster.FLAG_EDGE) == 0) continue;
                    int ox = wx + out.getStepX(), oz = wz + out.getStepZ();
                    int oidx = r.index(ox, oz);
                    if (oidx >= 0 && r.building[oidx] != 0) continue; // the next building, not the street
                    pos.set(wx, y, wz);
                    if (!level.getBlockState(pos).isSolid()) continue;
                    pos.set(ox, y, oz);
                    if (!level.getBlockState(pos).isAir()) continue;
                    BlockState state = striped && Math.floorMod(k, 2) != 0 ? white : main;
                    level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean isShopfront(BuildingFeature bf) {
        if (shopLike(bf.tags)) return true;
        for (Map<String, String> poi : bf.pois) {
            if (shopLike(poi)) return true;
        }
        return false;
    }

    private static boolean shopLike(Map<String, String> t) {
        if (t == null) return false;
        String shop = t.get("shop");
        if (shop != null && !shop.isBlank() && !"no".equals(shop) && !"vacant".equals(shop)) return true;
        String amenity = t.get("amenity");
        return amenity != null && AMENITIES.contains(amenity);
    }

    /** An upper-half wool slab of one dye colour, or null when this Minecraft has no wool slabs. */
    private static BlockState slab(String dye) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(dye + "_wool_slab"))
                .filter(b -> b instanceof SlabBlock)
                .map(b -> b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP))
                .orElse(null);
    }
}
