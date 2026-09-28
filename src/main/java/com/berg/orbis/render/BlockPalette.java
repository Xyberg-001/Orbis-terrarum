package com.berg.orbis.render;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps real-world colours (OSM building:colour / roof:colour, hex or CSS
 * names) to the visually closest Minecraft block, using per-block average
 * texture colours. Two separate candidate lists exist because a good roof
 * block (clay tile, slate, copper) is not necessarily a good wall block and
 * vice versa.
 *
 * Colour distance uses the "redmean" weighted RGB metric, which tracks human
 * perception far better than plain Euclidean RGB at essentially zero cost.
 */
public final class BlockPalette {

    private BlockPalette() {}

    /**
     * @param penalty extra distance for blocks with a busy texture (raw stone,
     *                cobbles) so a smooth block wins when the colour is a tie --
     *                a painted facade should not turn into cobblestone.
     */
    public record Entry(String name, BlockState state, int rgb, double penalty, double[] lab, double hue, double sat) {
        Entry(String name, BlockState state, int rgb, double penalty) {
            this(name, state, rgb, penalty, toLab(rgb), hueDegrees(rgb), saturation(rgb));
        }
    }

    private static final List<Entry> WALLS = new ArrayList<>();
    private static final List<Entry> ROOFS = new ArrayList<>();
    private static final Map<String, Integer> NAMED_COLOURS = new HashMap<>();

    private static double penaltyFor(String name) {
        return switch (name) {
            case "stone", "cobblestone", "andesite", "diorite", "granite", "tuff", "cobbled_deepslate", "dripstone_block",
                 "packed_mud", "bone_block", "purpur_block", "lapis_block", "prismarine_bricks", "end_stone_bricks",
                 "warped_planks", "crimson_planks", "moss_block", "spruce_wood", "dark_oak_wood", "birch_wood", "gravel" -> 9.0;
            case "hay_block", "mud_bricks", "nether_bricks", "red_nether_bricks", "dark_prismarine", "polished_blackstone_bricks",
                 "iron_block", "gold_block" -> 4.0;
            default -> 0.0;
        };
    }

    private static void wall(String name, Block block, int rgb) {
        WALLS.add(new Entry(name, block.defaultBlockState(), rgb, penaltyFor(name)));
    }

    private static void roof(String name, Block block, int rgb) {
        ROOFS.add(new Entry(name, block.defaultBlockState(), rgb, penaltyFor(name)));
    }

    private static void both(String name, Block block, int rgb) {
        wall(name, block, rgb);
        roof(name, block, rgb);
    }

    static {
        // ---- concrete: crisp, matte, the best generic "painted wall" ----
        both("white_concrete", Blocks.CONCRETE.white(), 0xCFD5D6);
        both("orange_concrete", Blocks.CONCRETE.orange(), 0xE06101);
        both("magenta_concrete", Blocks.CONCRETE.magenta(), 0xA9309F);
        both("light_blue_concrete", Blocks.CONCRETE.lightBlue(), 0x2389C7);
        both("yellow_concrete", Blocks.CONCRETE.yellow(), 0xF1AF15);
        both("lime_concrete", Blocks.CONCRETE.lime(), 0x5EA818);
        both("pink_concrete", Blocks.CONCRETE.pink(), 0xD6658F);
        both("gray_concrete", Blocks.CONCRETE.gray(), 0x373A3E);
        both("light_gray_concrete", Blocks.CONCRETE.lightGray(), 0x7D7D73);
        both("cyan_concrete", Blocks.CONCRETE.cyan(), 0x157788);
        both("purple_concrete", Blocks.CONCRETE.purple(), 0x64209C);
        both("blue_concrete", Blocks.CONCRETE.blue(), 0x2D2F8F);
        both("brown_concrete", Blocks.CONCRETE.brown(), 0x603C20);
        both("green_concrete", Blocks.CONCRETE.green(), 0x495B24);
        both("red_concrete", Blocks.CONCRETE.red(), 0x8E2121);
        both("black_concrete", Blocks.CONCRETE.black(), 0x080A0F);

        // ---- terracotta: muted earth tones, very common real facade colours ----
        both("terracotta", Blocks.TERRACOTTA, 0x985E43);
        both("white_terracotta", Blocks.DYED_TERRACOTTA.white(), 0xD1B2A1);
        both("orange_terracotta", Blocks.DYED_TERRACOTTA.orange(), 0xA15325);
        both("magenta_terracotta", Blocks.DYED_TERRACOTTA.magenta(), 0x96586D);
        both("light_blue_terracotta", Blocks.DYED_TERRACOTTA.lightBlue(), 0x716C89);
        both("yellow_terracotta", Blocks.DYED_TERRACOTTA.yellow(), 0xBA8523);
        both("lime_terracotta", Blocks.DYED_TERRACOTTA.lime(), 0x677535);
        both("pink_terracotta", Blocks.DYED_TERRACOTTA.pink(), 0xA24E4F);
        both("gray_terracotta", Blocks.DYED_TERRACOTTA.gray(), 0x392A23);
        both("light_gray_terracotta", Blocks.DYED_TERRACOTTA.lightGray(), 0x876B62);
        both("cyan_terracotta", Blocks.DYED_TERRACOTTA.cyan(), 0x575B5B);
        both("purple_terracotta", Blocks.DYED_TERRACOTTA.purple(), 0x764656);
        both("blue_terracotta", Blocks.DYED_TERRACOTTA.blue(), 0x4A3B5B);
        both("brown_terracotta", Blocks.DYED_TERRACOTTA.brown(), 0x4D3324);
        both("green_terracotta", Blocks.DYED_TERRACOTTA.green(), 0x4C532A);
        both("red_terracotta", Blocks.DYED_TERRACOTTA.red(), 0x8F3D2E);
        both("black_terracotta", Blocks.DYED_TERRACOTTA.black(), 0x251710);

        // ---- stone / masonry ----
        both("bricks", Blocks.BRICKS, 0x976153);
        both("stone_bricks", Blocks.STONE_BRICKS, 0x7A7A7A);
        wall("stone", Blocks.STONE, 0x7E7E7E);
        both("cobblestone", Blocks.COBBLESTONE, 0x807F7F);
        wall("andesite", Blocks.ANDESITE, 0x888889);
        wall("polished_andesite", Blocks.POLISHED_ANDESITE, 0x848685);
        wall("diorite", Blocks.DIORITE, 0xBDBDBE);
        wall("polished_diorite", Blocks.POLISHED_DIORITE, 0xC2C2C4);
        wall("granite", Blocks.GRANITE, 0x956755);
        wall("polished_granite", Blocks.POLISHED_GRANITE, 0x9B6B5A);
        both("sandstone", Blocks.SANDSTONE, 0xDACFA0);
        wall("smooth_sandstone", Blocks.SMOOTH_SANDSTONE, 0xE0D6A6);
        wall("cut_sandstone", Blocks.CUT_SANDSTONE, 0xDBD0A2);
        wall("red_sandstone", Blocks.RED_SANDSTONE, 0xA6531F);
        wall("smooth_red_sandstone", Blocks.SMOOTH_RED_SANDSTONE, 0xB4611F);
        both("quartz_block", Blocks.QUARTZ_BLOCK, 0xECE9E3);
        wall("smooth_quartz", Blocks.SMOOTH_QUARTZ, 0xEDEAE4);
        wall("quartz_bricks", Blocks.QUARTZ_BRICKS, 0xEAE6E0);
        both("iron_block", Blocks.IRON_BLOCK, 0xD8D8D8);
        both("deepslate_tiles", Blocks.DEEPSLATE_TILES, 0x363636);
        both("deepslate_bricks", Blocks.DEEPSLATE_BRICKS, 0x474747);
        wall("polished_deepslate", Blocks.POLISHED_DEEPSLATE, 0x4A4A4A);
        wall("cobbled_deepslate", Blocks.COBBLED_DEEPSLATE, 0x4D4D4F);
        wall("tuff", Blocks.TUFF, 0x6C6E64);
        wall("polished_tuff", Blocks.POLISHED_TUFF, 0x63665C);
        wall("tuff_bricks", Blocks.TUFF_BRICKS, 0x686B60);
        wall("calcite", Blocks.CALCITE, 0xDFE0DC);
        both("blackstone", Blocks.BLACKSTONE, 0x2F2A2F);
        both("polished_blackstone", Blocks.POLISHED_BLACKSTONE, 0x35313A);
        both("polished_blackstone_bricks", Blocks.POLISHED_BLACKSTONE_BRICKS, 0x322D33);
        both("mud_bricks", Blocks.MUD_BRICKS, 0x8D6B50);
        wall("packed_mud", Blocks.PACKED_MUD, 0x8F6D50);
        both("nether_bricks", Blocks.NETHER_BRICKS, 0x2C1620);
        both("red_nether_bricks", Blocks.RED_NETHER_BRICKS, 0x45080A);
        both("dark_prismarine", Blocks.DARK_PRISMARINE, 0x365F4E);
        wall("prismarine_bricks", Blocks.PRISMARINE_BRICKS, 0x63AC9C);
        wall("end_stone_bricks", Blocks.END_STONE_BRICKS, 0xD9DEA3);
        both("smooth_stone", Blocks.SMOOTH_STONE, 0xA0A0A0);
        wall("smooth_basalt", Blocks.SMOOTH_BASALT, 0x4A4A50);
        wall("polished_basalt", Blocks.POLISHED_BASALT, 0x5A5A5C);
        wall("dripstone_block", Blocks.DRIPSTONE_BLOCK, 0x866B5C);
        both("resin_bricks", Blocks.RESIN_BRICKS, 0xCF6D2A);
        wall("bone_block", Blocks.BONE_BLOCK, 0xE1DDC9);
        wall("purpur_block", Blocks.PURPUR_BLOCK, 0xA87BA8);
        wall("lapis_block", Blocks.LAPIS_BLOCK, 0x1F4A9E);
        roof("gold_block", Blocks.GOLD_BLOCK, 0xF8D33E);
        both("moss_block", Blocks.MOSS_BLOCK, 0x5B6F2C);
        roof("hay_block", Blocks.HAY_BLOCK, 0xB89C3B);

        // ---- wood ----
        both("oak_planks", Blocks.OAK_PLANKS, 0xB8945F);
        both("spruce_planks", Blocks.SPRUCE_PLANKS, 0x735531);
        both("birch_planks", Blocks.BIRCH_PLANKS, 0xC8B77A);
        both("jungle_planks", Blocks.JUNGLE_PLANKS, 0xB88764);
        both("acacia_planks", Blocks.ACACIA_PLANKS, 0xBA6337);
        both("dark_oak_planks", Blocks.DARK_OAK_PLANKS, 0x4A2F17);
        both("cherry_planks", Blocks.CHERRY_PLANKS, 0xE3B4AF);
        both("mangrove_planks", Blocks.MANGROVE_PLANKS, 0x763735);
        wall("bamboo_planks", Blocks.BAMBOO_PLANKS, 0xE3D583);
        wall("pale_oak_planks", Blocks.PALE_OAK_PLANKS, 0xE0DAD3);
        wall("warped_planks", Blocks.WARPED_PLANKS, 0x2B6963);
        wall("crimson_planks", Blocks.CRIMSON_PLANKS, 0x653147);
        wall("stripped_oak_wood", Blocks.STRIPPED_OAK_WOOD, 0xB39B65);
        wall("stripped_spruce_wood", Blocks.STRIPPED_SPRUCE_WOOD, 0x745A36);
        wall("stripped_birch_wood", Blocks.STRIPPED_BIRCH_WOOD, 0xC7B57A);
        wall("stripped_dark_oak_wood", Blocks.STRIPPED_DARK_OAK_WOOD, 0x4F3A1D);
        wall("stripped_acacia_wood", Blocks.STRIPPED_ACACIA_WOOD, 0xAF5D3C);
        wall("spruce_wood", Blocks.SPRUCE_WOOD, 0x3B2611);
        wall("dark_oak_wood", Blocks.DARK_OAK_WOOD, 0x3D2B16);
        wall("birch_wood", Blocks.BIRCH_WOOD, 0xD7D3CF);

        // ---- metals (copper family gives the classic green/brown roof range) ----
        both("copper_block", Blocks.COPPER_BLOCK.waxed().unaffected(), 0xC1704F);
        both("exposed_copper", Blocks.COPPER_BLOCK.waxed().exposed(), 0xA18468);
        both("weathered_copper", Blocks.COPPER_BLOCK.waxed().weathered(), 0x6EA067);
        both("oxidized_copper", Blocks.COPPER_BLOCK.waxed().oxidized(), 0x54A587);
        roof("cut_copper", Blocks.CUT_COPPER.waxed().unaffected(), 0xBF6B4D);

        // ---- CSS colour names + common OSM spellings ----
        // The basic names are deliberately NOT the pure CSS primaries: a
        // building tagged colour=red is a red-painted facade, not #FF0000.
        String[][] named = {
                {"white", "F5F5F5"}, {"black", "202020"}, {"gray", "8C8C8C"}, {"grey", "8C8C8C"}, {"silver", "C0C0C0"},
                {"red", "B8302A"}, {"maroon", "800000"}, {"brown", "7B5233"}, {"orange", "E07A2A"}, {"yellow", "F0D050"},
                {"gold", "FFD700"}, {"beige", "E8DCC5"}, {"tan", "D2B48C"}, {"cream", "F3E9D2"}, {"ivory", "FFFFF0"},
                {"khaki", "F0E68C"}, {"olive", "808000"}, {"green", "3E8E41"}, {"lime", "8DD35F"}, {"darkgreen", "2E5E2E"},
                {"lightgreen", "A8DCA0"}, {"teal", "2A7F7F"}, {"cyan", "3FB8C8"}, {"aqua", "3FB8C8"}, {"turquoise", "40E0D0"},
                {"blue", "3F6FB5"}, {"navy", "1F2F6F"}, {"lightblue", "A9CCE3"}, {"skyblue", "87CEEB"}, {"steelblue", "4682B4"},
                {"royalblue", "4169E1"}, {"purple", "7D3C98"}, {"violet", "B07CC6"}, {"magenta", "C040B0"}, {"fuchsia", "C040B0"},
                {"pink", "EAA8C0"}, {"lightpink", "F2C4D0"}, {"salmon", "FA8072"}, {"coral", "FF7F50"}, {"tomato", "FF6347"},
                {"crimson", "DC143C"}, {"indianred", "CD5C5C"}, {"firebrick", "B22222"}, {"darkred", "8B0000"},
                {"chocolate", "D2691E"}, {"sienna", "A0522D"}, {"peru", "CD853F"}, {"sandybrown", "F4A460"}, {"burlywood", "DEB887"},
                {"wheat", "F5DEB3"}, {"goldenrod", "DAA520"}, {"darkgoldenrod", "B8860B"}, {"lightgray", "D3D3D3"}, {"lightgrey", "D3D3D3"},
                {"darkgray", "A9A9A9"}, {"darkgrey", "A9A9A9"}, {"dimgray", "696969"}, {"dimgrey", "696969"}, {"gainsboro", "DCDCDC"},
                {"whitesmoke", "F5F5F5"}, {"snow", "FFFAFA"}, {"linen", "FAF0E6"}, {"antiquewhite", "FAEBD7"}, {"bisque", "FFE4C4"},
                {"blanchedalmond", "FFEBCD"}, {"cornsilk", "FFF8DC"}, {"lemonchiffon", "FFFACD"}, {"lightyellow", "FFFFE0"},
                {"palegoldenrod", "EEE8AA"}, {"mintcream", "F5FFFA"}, {"honeydew", "F0FFF0"}, {"aliceblue", "F0F8FF"},
                {"lavender", "E6E6FA"}, {"lavenderblush", "FFF0F5"}, {"mistyrose", "FFE4E1"}, {"seashell", "FFF5EE"},
                {"oldlace", "FDF5E6"}, {"floralwhite", "FFFAF0"}, {"ghostwhite", "F8F8FF"}, {"azure", "F0FFFF"}, {"lightcyan", "E0FFFF"},
                {"paleturquoise", "AFEEEE"}, {"aquamarine", "7FFFD4"}, {"mediumaquamarine", "66CDAA"}, {"cadetblue", "5F9EA0"},
                {"powderblue", "B0E0E6"}, {"lightsteelblue", "B0C4DE"}, {"cornflowerblue", "6495ED"}, {"dodgerblue", "1E90FF"},
                {"deepskyblue", "00BFFF"}, {"midnightblue", "191970"}, {"darkblue", "00008B"}, {"mediumblue", "0000CD"},
                {"slateblue", "6A5ACD"}, {"darkslateblue", "483D8B"}, {"mediumslateblue", "7B68EE"}, {"indigo", "4B0082"},
                {"darkviolet", "9400D3"}, {"darkorchid", "9932CC"}, {"mediumorchid", "BA55D3"}, {"orchid", "DA70D6"}, {"plum", "DDA0DD"},
                {"thistle", "D8BFD8"}, {"mediumpurple", "9370DB"}, {"blueviolet", "8A2BE2"}, {"darkmagenta", "8B008B"},
                {"mediumvioletred", "C71585"}, {"palevioletred", "DB7093"}, {"deeppink", "FF1493"}, {"hotpink", "FF69B4"},
                {"orangered", "FF4500"}, {"darkorange", "FF8C00"}, {"peachpuff", "FFDAB9"}, {"moccasin", "FFE4B5"},
                {"navajowhite", "FFDEAD"}, {"papayawhip", "FFEFD5"}, {"rosybrown", "BC8F8F"}, {"saddlebrown", "8B4513"},
                {"darkolivegreen", "556B2F"}, {"olivedrab", "6B8E23"}, {"yellowgreen", "9ACD32"}, {"greenyellow", "ADFF2F"},
                {"chartreuse", "7FFF00"}, {"lawngreen", "7CFC00"}, {"springgreen", "00FF7F"}, {"mediumspringgreen", "00FA9A"},
                {"lightseagreen", "20B2AA"}, {"seagreen", "2E8B57"}, {"mediumseagreen", "3CB371"}, {"darkseagreen", "8FBC8F"},
                {"forestgreen", "228B22"}, {"palegreen", "98FB98"}, {"darkcyan", "008B8B"}, {"darkturquoise", "00CED1"},
                {"mediumturquoise", "48D1CC"}, {"lightslategray", "778899"}, {"slategray", "708090"}, {"darkslategray", "2F4F4F"},
                {"lightslategrey", "778899"}, {"slategrey", "708090"}, {"darkslategrey", "2F4F4F"}, {"lightcoral", "F08080"},
                {"darksalmon", "E9967A"}, {"lightsalmon", "FFA07A"}, {"darkkhaki", "BDB76B"}, {"lightgoldenrodyellow", "FAFAD2"},
                {"mediumseagreen", "3CB371"}, {"lightskyblue", "87CEFA"}, {"cornflower", "6495ED"},
                // OSM-specific common spellings
                {"light_grey", "D3D3D3"}, {"light_gray", "D3D3D3"}, {"dark_grey", "A9A9A9"}, {"dark_gray", "A9A9A9"},
                {"dark_green", "006400"}, {"light_green", "90EE90"}, {"dark_red", "8B0000"}, {"light_brown", "B5651D"},
                {"dark_brown", "5C4033"}, {"off_white", "F2F0E6"}, {"off-white", "F2F0E6"}, {"offwhite", "F2F0E6"},
                {"light_blue", "ADD8E6"}, {"dark_blue", "00008B"}, {"terracotta", "E2725B"}, {"ochre", "CC7722"},
                {"ocher", "CC7722"}, {"sand", "C2B280"}, {"copper", "B87333"}, {"bronze", "CD7F32"}, {"rust", "B7410E"},
                {"anthracite", "293133"}, {"slate", "708090"}, {"stone", "888C8D"}, {"concrete", "A4A5A7"},
                {"brick", "AA4A44"}, {"wood", "BA8C63"}, {"yellowish", "F3E5AB"}, {"dark_yellow", "B8860B"},
                {"light_yellow", "FFFFE0"}, {"pale_yellow", "FFFFA7"}, {"peach", "FFE5B4"}, {"apricot", "FBCEB1"},
                {"mustard", "E1AD01"}, {"burgundy", "800020"}, {"bordeaux", "5F021F"}, {"rose", "FF007F"}, {"pastel_pink", "F8C8DC"},
                {"pale_pink", "FADADD"}, {"mint", "98FF98"}, {"turquoise_blue", "00FFEF"}, {"lilac", "C8A2C8"}, {"lavender_blue", "CCCCFF"},
                {"charcoal", "36454F"}, {"graphite", "383838"}, {"platinum", "E5E4E2"}, {"pearl", "EAE0C8"},
                {"eggshell", "F0EAD6"}, {"vanilla", "F3E5AB"}, {"champagne", "F7E7CE"}, {"caramel", "AF6E4D"},
                {"chestnut", "954535"}, {"mahogany", "C04000"}, {"walnut", "5D432C"}, {"oak", "C19A6B"}, {"pine", "01796F"},
                {"forest_green", "228B22"}, {"olive_green", "BAB86C"}, {"sea_green", "2E8B57"}, {"sky_blue", "87CEEB"},
                {"navy_blue", "000080"}, {"royal_blue", "4169E1"}, {"steel_blue", "4682B4"}, {"powder_blue", "B0E0E6"},
                {"baby_blue", "89CFF0"}, {"cobalt", "0047AB"}, {"azure_blue", "007FFF"}, {"petrol", "005F6A"},
        };
        for (String[] n : named) {
            NAMED_COLOURS.put(n[0], Integer.parseInt(n[1], 16));
        }
    }

    /** Parses "#rrggbb", "#rgb", "rrggbb", or a colour name. Returns null if unrecognised. */
    public static Integer parseColour(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        // Values like "white;red" or "#ffffff (cream)" -> take first token.
        int cut = s.indexOf(';');
        if (cut > 0) s = s.substring(0, cut).trim();
        cut = s.indexOf(' ');
        if (cut > 0 && !s.startsWith("#")) {
            String joined = s.replace(' ', '_');
            if (NAMED_COLOURS.containsKey(joined)) return NAMED_COLOURS.get(joined);
            s = s.substring(0, cut);
        }
        if (s.startsWith("#")) s = s.substring(1);
        if (s.matches("[0-9a-f]{6}")) return Integer.parseInt(s, 16);
        if (s.matches("[0-9a-f]{3}")) {
            int r = Integer.parseInt(s.substring(0, 1), 16) * 17;
            int g = Integer.parseInt(s.substring(1, 2), 16) * 17;
            int b = Integer.parseInt(s.substring(2, 3), 16) * 17;
            return (r << 16) | (g << 8) | b;
        }
        Integer named = NAMED_COLOURS.get(s);
        if (named != null) return named;
        named = NAMED_COLOURS.get(s.replace('-', '_'));
        if (named != null) return named;
        named = NAMED_COLOURS.get(s.replace("_", "").replace("-", ""));
        return named;
    }

    public static BlockState nearestWall(int rgb) {
        return nearest(WALLS, rgb).state();
    }

    public static BlockState nearestRoof(int rgb) {
        return nearest(ROOFS, rgb).state();
    }

    public static Entry nearest(List<Entry> candidates, int rgb) {
        double[] lab = toLab(rgb);
        double hue = hueDegrees(rgb);
        double sat = saturation(rgb);
        Entry best = candidates.get(0);
        double bestD = Double.MAX_VALUE;
        for (Entry e : candidates) {
            double d = distance(lab, hue, sat, e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Perceptual distance: CIE76 Lab difference plus a hue term that only
     * matters when both colours are saturated. Pure Lab alone would turn a
     * bright red facade into orange concrete (closest in lightness); people
     * read hue first, so it is weighted up.
     */
    public static double distance(double[] lab, double hue, double sat, Entry e) {
        double dl = lab[0] - e.lab()[0], da = lab[1] - e.lab()[1], db = lab[2] - e.lab()[2];
        double d = Math.sqrt(dl * dl + da * da + db * db);
        double dh = Math.abs(hue - e.hue());
        if (dh > 180) dh = 360 - dh;
        d += 1.4 * dh * Math.min(sat, e.sat());
        return d + e.penalty();
    }

    /** Convenience for tests / diagnostics: distance between two RGB colours (no penalty). */
    public static double distance(int c1, int c2) {
        return distance(toLab(c1), hueDegrees(c1), saturation(c1), new Entry("", null, c2, 0));
    }

    static double[] toLab(int rgb) {
        double r = srgbToLinear(((rgb >> 16) & 0xFF) / 255.0);
        double g = srgbToLinear(((rgb >> 8) & 0xFF) / 255.0);
        double b = srgbToLinear((rgb & 0xFF) / 255.0);
        double x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
        double y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.00000;
        double z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
        double fx = labF(x), fy = labF(y), fz = labF(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t) + (16.0 / 116.0);
    }

    static double hueDegrees(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        if (delta < 1e-6) return 0;
        double h;
        if (max == r) h = 60 * (((g - b) / delta) % 6);
        else if (max == g) h = 60 * (((b - r) / delta) + 2);
        else h = 60 * (((r - g) / delta) + 4);
        return h < 0 ? h + 360 : h;
    }

    static double saturation(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max < 1e-6 ? 0 : (max - min) / max;
    }

    public static List<Entry> wallEntries() {
        return WALLS;
    }

    public static List<Entry> roofEntries() {
        return ROOFS;
    }
}
