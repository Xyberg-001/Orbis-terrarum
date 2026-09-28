package com.berg.orbis.render;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The building's real name on a sign beside its door, so the city can be navigated by the names people
 * actually use. Buildings without a name but with a shop or amenity tag get that instead ("Bakery",
 * "Pharmacy"). Vanilla wall signs, waxed so nobody rewrites them by accident.
 */
public final class Signage {

    private static final int LINE_CHARS = 15;

    private Signage() {
    }

    public static void place(OrbisConfig cfg, WorldGenLevel level, RegionRaster r, int minX, int minZ) {
        if (r == null || !cfg.buildingSigns || !cfg.buildingDoors || !cfg.generateBuildings) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx, z = minZ + lz;
                int idx = r.index(x, z);
                if (idx < 0 || (r.buildingFlags[idx] & RegionRaster.FLAG_DOOR) == 0) continue;
                BuildingFeature bf = r.buildingAt(idx);
                if (bf == null) continue;
                String label = labelFor(bf);
                if (label == null) continue;
                Direction out = ColumnPainter.outwardDirection(r, x, z, idx);
                if (out == null) continue;
                int base = Math.max(cfg.minY + 1, Math.min(cfg.maxY() - 1, bf.baseY));
                int y = base + 3; // above the two-high door
                if (y + 1 >= cfg.maxY()) continue;
                pos.set(x, y, z);
                if (!level.getBlockState(pos).isSolid()) continue; // no wall to hang it on
                int sx = x + out.getStepX(), sz = z + out.getStepZ();
                pos.set(sx, y, sz);
                if (!level.getBlockState(pos).isAir()) continue;
                Block sign = bf.wall.is(Blocks.SPRUCE_PLANKS) || bf.wall.is(Blocks.OAK_PLANKS) || bf.wall.is(Blocks.DARK_OAK_PLANKS)
                        ? Blocks.SPRUCE_WALL_SIGN : Blocks.OAK_WALL_SIGN;
                BlockState state = sign.defaultBlockState().setValue(WallSignBlock.FACING, out);
                level.setBlock(pos, state, Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
                if (level.getBlockEntity(pos) instanceof SignBlockEntity be) writeSign(be, label);
            }
        }
    }

    /**
     * Puts the text on a sign during world generation. The block entity has no level yet, and vanilla's
     * setters send a block update through the level after storing the text, so that update is caught: the
     * text and the wax are already in place when it fails.
     */
    public static void writeSign(SignBlockEntity be, String label) {
        SignText text = new SignText();
        List<String> lines = wrap(label);
        for (int i = 0; i < lines.size() && i < 4; i++) text = text.setMessage(i, Component.literal(lines.get(i)));
        try {
            be.setText(text, true);
        } catch (NullPointerException noLevelYet) {
            // stored before the update
        }
        try {
            be.setWaxed(true);
        } catch (NullPointerException noLevelYet) {
            // stored before the update
        }
        be.setChanged();
    }

    /** The name from the map (the building's, else its first named business), else a readable kind, else null. */
    static String labelFor(BuildingFeature bf) {
        String name = nameOf(bf.tags, bf.name);
        if (name == null) {
            for (Map<String, String> poi : bf.pois) {
                name = nameOf(poi, null);
                if (name != null) break;
            }
        }
        if (name != null) return name;
        String kind = kindOf(bf.tags);
        if (kind == null) {
            for (Map<String, String> poi : bf.pois) {
                kind = kindOf(poi);
                if (kind != null) break;
            }
        }
        return kind;
    }

    private static String nameOf(Map<String, String> t, String given) {
        String name = given;
        if ((name == null || name.isBlank()) && t != null) {
            name = t.get("name");
            if (name == null || name.isBlank()) name = t.get("brand");
        }
        return name == null || name.isBlank() ? null : name.trim();
    }

    private static String kindOf(Map<String, String> t) {
        if (t == null) return null;
        for (String key : new String[]{"shop", "amenity", "tourism", "craft", "office", "leisure", "healthcare"}) {
            String v = t.get(key);
            if (v == null || v.isBlank()) continue;
            if ("yes".equals(v)) return "shop".equals(key) ? "Shop" : "office".equals(key) ? "Office" : null;
            return readable(v);
        }
        return null;
    }

    private static String readable(String tag) {
        String s = tag.replace('_', ' ');
        int semi = s.indexOf(';');
        if (semi > 0) s = s.substring(0, semi);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Word-wraps a name onto sign lines of at most 15 characters; over-long words are cut. */
    public static List<String> wrap(String label) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : label.trim().split("\\s+")) {
            if (word.length() > LINE_CHARS) word = word.substring(0, LINE_CHARS);
            if (cur.length() == 0) {
                cur.append(word);
            } else if (cur.length() + 1 + word.length() <= LINE_CHARS) {
                cur.append(' ').append(word);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
                if (lines.size() == 4) break;
            }
        }
        if (cur.length() > 0 && lines.size() < 4) lines.add(cur.toString());
        return lines;
    }
}
