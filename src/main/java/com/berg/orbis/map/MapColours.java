package com.berg.orbis.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world map's colours: each block's real top-face colour, with grass, leaves and water coloured by their biome
 * the way the game draws them, from the table the build makes from each Minecraft version's own textures, colour
 * maps and biomes ({@code assets/orbisterrarum/map_colours.json}; a server has none of those to look at). Blocks
 * the table does not know (other mods') keep their vanilla map colour.
 */
public final class MapColours {

    private static final int GRASS = 0, FOLIAGE = 1, DRY_FOLIAGE = 2, WATER = 3, FIXED = 4, NONE = -1;
    private static final int[] PLAINS = {0x91BD59, 0x77AB2F, 0xA37546, 0x3F76E4};

    /** Per block: {top colour, tint kind, fixed tint}; top -1 when the table does not know the block. */
    private static final Map<Block, int[]> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<Holder<Biome>, int[]> BIOME_CACHE = new ConcurrentHashMap<>();
    private static Map<String, Integer> top = Map.of();
    private static Map<String, String> tint = Map.of();
    private static Map<String, int[]> biomes = Map.of();

    static {
        try (InputStream in = MapColours.class.getResourceAsStream("/assets/orbisterrarum/map_colours.json")) {
            if (in != null) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, Integer> t = new HashMap<>();
                for (var e : json.getAsJsonObject("top").entrySet()) t.put(e.getKey(), Integer.parseInt(e.getValue().getAsString(), 16));
                Map<String, String> k = new HashMap<>();
                for (var e : json.getAsJsonObject("tint").entrySet()) k.put(e.getKey(), e.getValue().getAsString());
                Map<String, int[]> b = new HashMap<>();
                for (var e : json.getAsJsonObject("biomes").entrySet()) {
                    JsonArray a = e.getValue().getAsJsonArray();
                    int[] c = new int[4];
                    for (int i = 0; i < 4; i++) c[i] = Integer.parseInt(a.get(i).getAsString(), 16);
                    b.put(e.getKey(), c);
                }
                top = t;
                tint = k;
                biomes = b;
            } else {
                System.out.println("[orbis] No map colour table in the mod jar: the world map uses vanilla map colours");
            }
        } catch (Exception e) {
            System.err.println("[orbis] Map colour table unreadable (" + e + "): the world map uses vanilla map colours");
        }
    }

    private MapColours() {
    }

    /** The colour of a block seen from above in a biome (0xRRGGBB); its vanilla map colour when the table has none. */
    static int colour(BlockState s, Holder<Biome> biome) {
        int[] b = BLOCKS.computeIfAbsent(s.getBlock(), MapColours::lookup);
        if (b[0] < 0) return s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        if (b[1] == NONE) return b[0];
        int t = b[1] == FIXED ? b[2] : biomeColours(biome)[b[1]];
        return multiply(b[0], t);
    }

    /**
     * This Minecraft version's biomes (as "minecraft:name") with their colours as the game draws them:
     * {grass, foliage, dry foliage, water}. Empty when the jar has no table.
     */
    public static Map<String, int[]> biomeColours() {
        return java.util.Collections.unmodifiableMap(biomes);
    }

    /** True for the blocks whose map colour is the water's (for the depth shading). */
    static boolean isWater(BlockState s) {
        return s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == MapColor.WATER;
    }

    private static int[] lookup(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (!"minecraft".equals(id.getNamespace())) return new int[]{-1, NONE, 0};
        Integer c = top.get(id.getPath());
        if (c == null) return new int[]{-1, NONE, 0};
        String k = tint.get(id.getPath());
        if (k == null) return new int[]{c, NONE, 0};
        if (k.startsWith("#")) return new int[]{c, FIXED, Integer.parseInt(k.substring(1), 16)};
        return new int[]{c, switch (k) {
            case "foliage" -> FOLIAGE;
            case "dry_foliage" -> DRY_FOLIAGE;
            case "water" -> WATER;
            default -> GRASS;
        }, 0};
    }

    private static int[] biomeColours(Holder<Biome> biome) {
        if (biome == null) return PLAINS;
        return BIOME_CACHE.computeIfAbsent(biome, h -> h.unwrapKey()
                .map(key -> biomes.getOrDefault(key.identifier().toString(), PLAINS))
                .orElse(PLAINS));
    }

    /** A grey texture coloured by a tint, as the game does it. */
    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return r << 16 | g << 8 | bl;
    }
}
