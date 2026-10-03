package com.berg.orbis.render;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stairs and slab that shingle a pitched roof, so it slopes instead of stepping in whole blocks.
 *
 * <p>The stairs and ridge slab cover almost all of a roof you can see, so they are chosen by colour from every stair
 * block the running Minecraft has (26.3 added all sixteen concrete and wool colours), using the block colour table
 * the build extracts from that version's textures ({@code block_colours.json}); the roof's own stairs win when they
 * are close, busy textures and fabric-like wool count as a little further away, and the roof block itself stays as
 * it is underneath. Without the table (a build that did not make one) only the roof block's own stairs are used.
 */
public final class RoofBlocks {

    /** Roof blocks that stay as they are: their look is the point (gilded domes, thatch, glass, gravel). */
    private static final Set<String> KEEP = Set.of("gold_block", "hay_block", "glass", "gravel");
    /** Largest colour difference (CIE76, including penalties) for stairs on a roof; beyond it the roof stays blocky. */
    private static final double MAX_DISTANCE = 22.0;
    /** How much closer the roof block's own stairs count (a brick roof keeps brick stairs over a near-identical colour). */
    private static final double OWN_BONUS = 8.0;
    /** Extra distance for busy or animated textures (cobblestone, raw stone, prismarine) and for wool's fabric look. */
    private static final double BUSY = 6.0, FABRIC = 5.0;

    /** A roof's blocks; {@code stairs} and {@code slab} are null when there are none. */
    public record Set3(BlockState full, BlockState stairs, BlockState slab) {
        public boolean shaped() {
            return stairs != null;
        }
    }

    private record Stairs(BlockState stairs, BlockState slab, double[] lab, double penalty) {
    }

    private static final Map<BlockState, Set3> CACHE = new ConcurrentHashMap<>();
    private static volatile List<Stairs> allStairs;

    private RoofBlocks() {
    }

    public static Set3 of(BlockState roof) {
        return CACHE.computeIfAbsent(roof, RoofBlocks::resolve);
    }

    private static Set3 resolve(BlockState roof) {
        String name = name(roof.getBlock());
        BlockState ownStairs = find(name, "stairs", StairBlock.class);
        BlockState ownSlab = find(name, "slab", SlabBlock.class);
        if (KEEP.contains(name)) return new Set3(roof, ownStairs, ownSlab);
        Integer rgb = colours().get(name);
        if (rgb == null) rgb = BlockPalette.roofEntries().stream().filter(e -> e.state() == roof).map(BlockPalette.Entry::rgb).findFirst().orElse(null);
        if (rgb == null || stairs().isEmpty()) return new Set3(roof, ownStairs, ownSlab);
        double[] lab = BlockPalette.toLab(rgb);
        Stairs best = null;
        double bestScore = MAX_DISTANCE;
        for (Stairs s : stairs()) {
            double score = distance(lab, s.lab()) + s.penalty() - (s.stairs() == ownStairs ? OWN_BONUS : 0);
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        if (best == null) return new Set3(roof, null, null);
        return new Set3(roof, best.stairs(), best.slab());
    }

    /** A block's own slab, or the slab of the stairs closest in colour (road ramps); null when there is none. */
    public static BlockState slabFor(BlockState block) {
        BlockState own = find(name(block.getBlock()), "slab", SlabBlock.class);
        return own != null ? own : of(block).slab();
    }

    /** A block's own stairs, or the stairs closest in colour (outdoor stairways); null when there are none. */
    public static BlockState stairsFor(BlockState block) {
        BlockState own = find(name(block.getBlock()), "stairs", StairBlock.class);
        return own != null ? own : of(block).stairs();
    }

    /** Every stair block of this Minecraft with a known colour, with its slab (same family) when there is one. */
    private static List<Stairs> stairs() {
        List<Stairs> list = allStairs;
        if (list != null) return list;
        list = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!(b instanceof StairBlock)) continue;
            String n = name(b);
            if (n.contains("copper") && !n.startsWith("waxed_")) continue; // unwaxed copper turns green over time
            Integer rgb = colours().get(n);
            if (rgb == null) continue;
            String family = n.substring(0, n.length() - "_stairs".length());
            BlockState slab = find(family, "slab", SlabBlock.class);
            list.add(new Stairs(b.defaultBlockState(), slab, BlockPalette.toLab(rgb), penalty(n)));
        }
        allStairs = list;
        return list;
    }

    private static double penalty(String n) {
        if (n.contains("_wool_")) return FABRIC;
        if (n.contains("cobble") || n.contains("mossy") || n.startsWith("andesite") || n.startsWith("diorite") || n.startsWith("granite")
                || n.startsWith("tuff") || n.startsWith("blackstone") || n.startsWith("end_stone") || n.startsWith("purpur")
                || n.equals("prismarine_stairs") || n.contains("bamboo_mosaic")) return BUSY;
        return 0;
    }

    private static Map<String, Integer> colours() {
        return BlockColours.table();
    }

    /** A block's own stairs or slab, found by the vanilla naming patterns (bricks - brick_stairs, spruce_planks - spruce_stairs). */
    private static BlockState find(String n, String kind, Class<? extends Block> type) {
        String[] candidates = {
                n + "_" + kind,
                n.endsWith("s") ? n.substring(0, n.length() - 1) + "_" + kind : null,              // bricks, deepslate_tiles
                n.endsWith("_planks") ? n.substring(0, n.length() - 7) + "_" + kind : null,        // spruce_planks
                n.endsWith("_block") ? n.substring(0, n.length() - 6) + "_" + kind : null,         // quartz_block
                n.contains("copper") && !n.contains("cut_copper")                                  // copper sheets: cut copper
                        ? (n.endsWith("copper_block") ? n.replace("copper_block", "cut_copper") : n.replace("copper", "cut_copper")) + "_" + kind
                        : null};
        for (String c : candidates) {
            if (c == null) continue;
            Block b = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(c)).orElse(null);
            if (b != null && type.isInstance(b)) return b.defaultBlockState();
        }
        return null;
    }

    private static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static double distance(double[] a, double[] b) {
        double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** For tests and the palette overview: what a roof block becomes. */
    public static String describe(BlockState roof) {
        Set3 s = of(roof);
        return name(roof.getBlock()) + (s.shaped() ? " + " + name(s.stairs().getBlock()) + (s.slab() != null ? " + " + name(s.slab().getBlock()) : " (no slab)") : " (blocky)");
    }
}
