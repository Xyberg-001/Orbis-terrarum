package com.berg.orbis.render;

import com.berg.orbis.feature.RoadFeature;
import com.berg.orbis.feature.RoofShape;
import com.berg.orbis.osm.OsmTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Map;

/**
 * Turns OSM tags into concrete block choices. Explicit colour / material
 * tags always win; otherwise a plausible default is chosen from the building
 * type, size and a regional style derived from latitude, with a hash of the
 * element id giving street-level variety instead of identical clones.
 */
public final class Materials {

    private Materials() {}

    public record BuildingMaterials(BlockState wall, BlockState wallAccent, BlockState roof, BlockState window,
                                    BlockState floor, boolean glassCurtain) {}

    public enum Style { NORDIC, TEMPERATE, MEDITERRANEAN, TROPICAL }

    public static Style styleForLatitude(double lat) {
        double a = Math.abs(lat);
        if (a >= 55) return Style.NORDIC;
        if (a >= 42) return Style.TEMPERATE;
        if (a >= 25) return Style.MEDITERRANEAN;
        return Style.TROPICAL;
    }

    // ------------------------------------------------------------------ buildings

    public static String buildingType(Map<String, String> tags) {
        String b = tags.get("building");
        if (b == null || "yes".equals(b)) {
            String p = tags.get("building:part");
            if (p != null && !"yes".equals(p) && !"no".equals(p)) return p;
            if (tags.containsKey("amenity")) {
                String a = tags.get("amenity");
                if ("place_of_worship".equals(a)) {
                    String rel = tags.get("religion");
                    return "muslim".equals(rel) ? "mosque" : "christian".equals(rel) ? "church" : "temple";
                }
                if ("school".equals(a) || "kindergarten".equals(a) || "college".equals(a)) return "school";
                if ("university".equals(a)) return "university";
                if ("hospital".equals(a)) return "hospital";
                if ("parking".equals(a)) return "parking";
            }
            if (tags.containsKey("shop")) return "retail";
            if (tags.containsKey("office")) return "office";
            if (tags.containsKey("industrial")) return "industrial";
            if ("hotel".equals(tags.get("tourism"))) return "hotel";
            if ("museum".equals(tags.get("tourism")) || tags.containsKey("historic")) return "museum";
            return b == null ? "yes" : b;
        }
        return b.toLowerCase(Locale.ROOT);
    }

    public static boolean isResidentialType(String t) {
        return switch (t) {
            case "house", "detached", "semidetached_house", "terrace", "residential", "bungalow", "farm", "cabin",
                 "hut", "villa", "dormitory", "houseboat", "static_caravan", "ger", "yes" -> true;
            default -> false;
        };
    }

    public static boolean isFlatRoofType(String t) {
        return switch (t) {
            case "apartments", "commercial", "office", "industrial", "retail", "warehouse", "parking", "hospital", "school",
                 "university", "college", "hotel", "public", "civic", "government", "supermarket", "kiosk", "service",
                 "transportation", "train_station", "sports_hall", "stadium", "factory", "hangar", "water_tower",
                 "tower", "bunker", "garages", "data_center", "manufacture", "depot", "fire_station", "police",
                 "transformer_tower", "multi-storey", "mall", "storage_tank", "silo", "container" -> true;
            default -> false;
        };
    }

    public static BuildingMaterials building(Map<String, String> tags, String type, long id, int levels, Style style,
                                             RoofShape shape, boolean domed) {
        long h = mix(id);
        boolean glassCurtain = false;

        BlockState wall = null;
        String colourRaw = firstTag(tags, "building:colour", "building:color", "colour", "color");
        // Mappers sometimes put a material word in the colour field ("brick", "slate", "wood").
        if (colourRaw != null) wall = wallForMaterial(lower(colourRaw), h);
        Integer colour = wall == null ? BlockPalette.parseColour(colourRaw) : null;
        if (colour != null) {
            wall = BlockPalette.nearestWall(colour);
        }
        String material = lower(firstTag(tags, "building:material", "material", "building:facade:material", "building:cladding"));
        if (wall == null && material != null) {
            wall = wallForMaterial(material, h);
            if ("glass".equals(material) || "mirror".equals(material)) glassCurtain = true;
        }
        if (wall == null) {
            wall = defaultWall(type, levels, style, h);
            if (("office".equals(type) || "commercial".equals(type) || "hotel".equals(type)) && levels >= 6 && (h & 1) == 0) {
                glassCurtain = true;
            }
        }

        BlockState roof = null;
        String roofColourRaw = firstTag(tags, "roof:colour", "roof:color");
        String roofMaterial = lower(firstTag(tags, "roof:material"));
        if (isFabric(roofMaterial) || "tent".equals(type) || "marquee".equals(type)) {
            // Canvas, tents and membrane canopies: wool in the mapped colour (white when none is mapped).
            roof = woolFor(BlockPalette.parseColour(roofColourRaw));
        }
        if (roof == null && roofColourRaw != null) roof = roofForMaterial(lower(roofColourRaw), h);
        Integer roofColour = roof == null ? BlockPalette.parseColour(roofColourRaw) : null;
        if (roofColour != null) roof = BlockPalette.nearestRoof(roofColour);
        if (roof == null && roofMaterial != null) roof = roofForMaterial(roofMaterial, h);
        if (roof == null) roof = defaultRoof(type, shape, style, domed, h >>> 8);

        if ("greenhouse".equals(type)) {
            wall = Blocks.GLASS.defaultBlockState();
            roof = Blocks.GLASS.defaultBlockState();
        }

        BlockState window = glassCurtain
                ? Blocks.STAINED_GLASS.lightBlue().defaultBlockState()
                : Blocks.GLASS.defaultBlockState();
        if ("church".equals(type) || "cathedral".equals(type) || "chapel".equals(type)) {
            window = ((h >>> 3) & 1) == 0 ? Blocks.STAINED_GLASS.purple().defaultBlockState() : Blocks.STAINED_GLASS.blue().defaultBlockState();
        }

        BlockState accent = accentFor(wall, type, h);
        BlockState floor = floorFor(wall, type);
        if (glassCurtain) {
            // Glass curtain walls: light structural frame with tinted glazing between floors.
            accent = Blocks.CONCRETE.lightGray().defaultBlockState();
        }
        return new BuildingMaterials(wall, accent, roof, window, floor, glassCurtain);
    }

    private static BlockState wallForMaterial(String m, long h) {
        return switch (m) {
            case "brick", "bricks", "clinker" -> Blocks.BRICKS.defaultBlockState();
            case "stone", "rubble", "masonry" -> (h & 1) == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            case "concrete", "cement", "concrete_block", "cement_block", "reinforced_concrete" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case "wood", "timber", "log", "logs", "timber_framing", "wood_siding", "siding" -> switch ((int) (h % 5)) {
                case 0 -> Blocks.OAK_PLANKS.defaultBlockState();
                case 1 -> Blocks.SPRUCE_PLANKS.defaultBlockState();
                case 2 -> (h >>> 9 & 1) == 0 ? NewBlocks.or(NewBlocks.POPLAR_PLANKS, Blocks.BIRCH_PLANKS.defaultBlockState())   // weathered grey boards
                        : Blocks.BIRCH_PLANKS.defaultBlockState();
                case 3 -> Blocks.DYED_TERRACOTTA.red().defaultBlockState();   // falu-red painted wood
                default -> Blocks.CONCRETE.white().defaultBlockState();       // white painted wood
            };
            case "plaster", "stucco", "render", "rendered", "plastered" -> Blocks.CONCRETE.white().defaultBlockState();
            case "glass", "mirror" -> Blocks.STAINED_GLASS.lightBlue().defaultBlockState();
            // Metal cladding: light grey concrete like metal roofs, never iron blocks (nine free ingots each).
            case "metal", "steel", "aluminium", "aluminum", "corrugated_metal", "sheet_metal", "tin" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case "sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "limestone" -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
            case "marble" -> Blocks.QUARTZ_BLOCK.defaultBlockState();
            case "granite" -> Blocks.POLISHED_GRANITE.defaultBlockState();
            case "slate" -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case "adobe", "mud", "earth", "loam", "clay" -> Blocks.PACKED_MUD.defaultBlockState();
            case "vinyl", "plastic" -> Blocks.CONCRETE.white().defaultBlockState();
            case "tile", "tiles", "ceramic" -> Blocks.DYED_TERRACOTTA.white().defaultBlockState();
            case "copper" -> Blocks.CUT_COPPER.waxed().exposed().defaultBlockState();
            case "basalt" -> Blocks.SMOOTH_BASALT.defaultBlockState();
            case "tuff" -> Blocks.TUFF.defaultBlockState();
            case "terracotta" -> Blocks.TERRACOTTA.defaultBlockState();
            default -> null;
        };
    }

    private static boolean isFabric(String m) {
        return m != null && switch (m) {
            case "fabric", "canvas", "tent", "membrane", "textile", "tarpaulin", "tarp", "ptfe", "etfe", "pvc", "cloth" -> true;
            default -> false;
        };
    }

    /** The wool of the dye colour closest to a mapped colour (via the concrete of the same dye), white without one. */
    static BlockState woolFor(Integer rgb) {
        BlockState white = Blocks.WOOL.white().defaultBlockState();
        if (rgb == null) return white;
        double[] lab = BlockPalette.toLab(rgb);
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPalette.Entry e : BlockPalette.roofEntries()) {
            if (!e.name().endsWith("_concrete")) continue;
            double dl = lab[0] - e.lab()[0], da = lab[1] - e.lab()[1], db = lab[2] - e.lab()[2];
            double d = dl * dl + da * da + db * db;
            if (d < bestD) {
                bestD = d;
                best = e.name();
            }
        }
        if (best == null) return white;
        String wool = best.substring(0, best.length() - "_concrete".length()) + "_wool";
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(wool))
                .map(net.minecraft.world.level.block.Block::defaultBlockState).orElse(white);
    }

    private static BlockState roofForMaterial(String m, long h) {
        return switch (m) {
            case "roof_tiles", "tiles", "tile", "clay_tiles", "ceramic" -> (h & 2) == 0 ? Blocks.BRICKS.defaultBlockState() : Blocks.DYED_TERRACOTTA.red().defaultBlockState();
            case "slate" -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case "metal", "steel", "tin", "zinc", "corrugated", "corrugated_metal", "aluminium", "aluminum", "sheet_metal" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case "copper" -> Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState();
            case "tar_paper", "asphalt", "bitumen", "roofing_felt", "felt", "rubber", "epdm" -> Blocks.CONCRETE.gray().defaultBlockState();
            case "concrete", "cement" -> Blocks.CONCRETE.gray().defaultBlockState();
            case "gravel", "stone_gravel" -> Blocks.GRAVEL.defaultBlockState();
            case "grass", "green", "plants", "vegetation", "sod", "turf" -> Blocks.MOSS_BLOCK.defaultBlockState();
            case "glass" -> Blocks.GLASS.defaultBlockState();
            case "thatch", "straw", "reed" -> Blocks.HAY_BLOCK.defaultBlockState();
            case "wood", "shingles", "wood_shingles", "shakes", "timber" -> (h & 2) == 0 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "stone" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "eternit", "fibre_cement", "fiber_cement", "asbestos" -> Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState();
            case "plastic", "pvc", "polycarbonate" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case "lead" -> Blocks.CONCRETE.gray().defaultBlockState();
            case "gold", "gilded" -> Blocks.CONCRETE.yellow().defaultBlockState();
            case "sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "solar_panels", "solar" -> Blocks.CONCRETE.blue().defaultBlockState();
            default -> null;
        };
    }

    private static BlockState defaultWall(String type, int levels, Style style, long h) {
        int r = (int) ((h >>> 16) % 100);
        if (isResidentialType(type)) {
            if (levels >= 5) return apartmentWall(style, r);
            return switch (style) {
                case NORDIC -> r < 35 ? Blocks.CONCRETE.white().defaultBlockState()
                        : r < 50 ? Blocks.DYED_TERRACOTTA.red().defaultBlockState()
                        : r < 62 ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState()
                        : r < 72 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                        : r < 82 ? Blocks.BRICKS.defaultBlockState()
                        : r < 86 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                        : r < 90 ? NewBlocks.or(NewBlocks.POPLAR_PLANKS, Blocks.SPRUCE_PLANKS.defaultBlockState())
                        : r < 95 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                        : Blocks.BIRCH_PLANKS.defaultBlockState();
                case TEMPERATE -> r < 30 ? Blocks.BRICKS.defaultBlockState()
                        : r < 50 ? Blocks.CONCRETE.white().defaultBlockState()
                        : r < 62 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                        : r < 72 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                        : r < 80 ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState()
                        : r < 88 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : r < 94 ? Blocks.OAK_PLANKS.defaultBlockState()
                        : Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState();
                case MEDITERRANEAN -> r < 35 ? Blocks.CONCRETE.white().defaultBlockState()
                        : r < 55 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                        : r < 70 ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState()
                        : r < 80 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState()
                        : r < 88 ? Blocks.TERRACOTTA.defaultBlockState()
                        : r < 94 ? Blocks.DYED_TERRACOTTA.pink().defaultBlockState()
                        : Blocks.CONCRETE.lightGray().defaultBlockState();
                case TROPICAL -> r < 35 ? Blocks.CONCRETE.white().defaultBlockState()
                        : r < 50 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                        : r < 62 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                        : r < 72 ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState()
                        : r < 80 ? Blocks.DYED_TERRACOTTA.lightBlue().defaultBlockState()
                        : r < 88 ? Blocks.CONCRETE.lightBlue().defaultBlockState()
                        : r < 94 ? Blocks.DYED_TERRACOTTA.pink().defaultBlockState()
                        : Blocks.MUD_BRICKS.defaultBlockState();
            };
        }
        return switch (type) {
            case "apartments", "dormitory", "hotel" -> apartmentWall(style, r);
            case "commercial", "office", "retail", "supermarket", "mall", "kiosk", "public", "civic", "government", "transportation", "train_station" ->
                    r < 40 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                            : r < 60 ? Blocks.CONCRETE.white().defaultBlockState()
                            : r < 75 ? Blocks.BRICKS.defaultBlockState()
                            : r < 88 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                            : Blocks.SMOOTH_STONE.defaultBlockState();
            case "industrial", "warehouse", "factory", "hangar", "manufacture", "depot", "storage_tank", "silo", "data_center", "garages", "garage", "carport", "shed", "service", "bunker", "container", "parking", "multi-storey", "transformer_tower" ->
                    r < 40 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                            : r < 60 ? Blocks.CONCRETE.gray().defaultBlockState()
                            : r < 75 ? Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState()
                            : r < 88 ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.BRICKS.defaultBlockState();
            case "church", "cathedral", "chapel", "monastery", "temple", "synagogue", "shrine" ->
                    r < 45 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : r < 65 ? Blocks.CONCRETE.white().defaultBlockState()
                            : r < 80 ? Blocks.BRICKS.defaultBlockState()
                            : r < 90 ? Blocks.COBBLESTONE.defaultBlockState()
                            : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
            case "mosque" -> r < 60 ? Blocks.CONCRETE.white().defaultBlockState()
                    : r < 80 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState()
                    : Blocks.QUARTZ_BLOCK.defaultBlockState();
            case "school", "university", "college", "kindergarten", "hospital", "fire_station", "police", "sports_hall", "stadium", "sports_centre" ->
                    r < 45 ? Blocks.BRICKS.defaultBlockState()
                            : r < 70 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                            : r < 85 ? Blocks.CONCRETE.white().defaultBlockState()
                            : Blocks.DYED_TERRACOTTA.yellow().defaultBlockState();
            case "barn", "farm_auxiliary", "stable", "cowshed", "sty", "greenhouse", "boathouse" ->
                    style == Style.NORDIC ? (r < 65 ? Blocks.DYED_TERRACOTTA.red().defaultBlockState()
                            : r < 85 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : NewBlocks.or(NewBlocks.POPLAR_PLANKS, Blocks.SPRUCE_PLANKS.defaultBlockState()))
                            : (r < 50 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : r < 75 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.CONCRETE.lightGray().defaultBlockState());
            case "castle", "fort", "fortress", "ruins", "tower", "city_gate", "bridge" ->
                    r < 60 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            case "water_tower", "lighthouse" -> Blocks.CONCRETE.white().defaultBlockState();
            case "roof", "shelter", "pavilion", "gazebo" -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "construction" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            default -> r < 40 ? Blocks.CONCRETE.white().defaultBlockState()
                    : r < 60 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                    : r < 80 ? Blocks.BRICKS.defaultBlockState()
                    : Blocks.DYED_TERRACOTTA.white().defaultBlockState();
        };
    }

    private static BlockState apartmentWall(Style style, int r) {
        return r < 30 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                : r < 50 ? Blocks.CONCRETE.white().defaultBlockState()
                : r < 68 ? Blocks.BRICKS.defaultBlockState()
                : r < 80 ? Blocks.DYED_TERRACOTTA.white().defaultBlockState()
                : r < 90 ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState()
                : style == Style.MEDITERRANEAN ? Blocks.TERRACOTTA.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
    }

    private static BlockState defaultRoof(String type, RoofShape shape, Style style, boolean domed, long h) {
        int r = (int) (h % 100);
        if (domed || shape == RoofShape.DOME || shape == RoofShape.ONION) {
            if ("mosque".equals(type)) return r < 50 ? Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState() : Blocks.CONCRETE.yellow().defaultBlockState();
            return r < 60 ? Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState()
                    : r < 80 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                    : Blocks.QUARTZ_BLOCK.defaultBlockState();
        }
        if (shape == RoofShape.FLAT) {
            return switch (type) {
                case "industrial", "warehouse", "factory", "hangar", "depot", "manufacture" -> r < 70 ? Blocks.CONCRETE.lightGray().defaultBlockState() : Blocks.CONCRETE.gray().defaultBlockState();
                default -> r < 55 ? Blocks.CONCRETE.gray().defaultBlockState()
                        : r < 80 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                        : r < 92 ? Blocks.GRAVEL.defaultBlockState()
                        : Blocks.MOSS_BLOCK.defaultBlockState();
            };
        }
        if ("church".equals(type) || "cathedral".equals(type) || "chapel".equals(type) || "monastery".equals(type)) {
            return r < 60 ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                    : r < 80 ? Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState()
                    : Blocks.BRICKS.defaultBlockState();
        }
        return switch (style) {
            case NORDIC -> r < 50 ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                    : r < 75 ? Blocks.BRICKS.defaultBlockState()
                    : r < 88 ? Blocks.DYED_TERRACOTTA.red().defaultBlockState()
                    : r < 95 ? Blocks.CONCRETE.gray().defaultBlockState()
                    : Blocks.DYED_TERRACOTTA.black().defaultBlockState();
            case TEMPERATE -> r < 40 ? Blocks.BRICKS.defaultBlockState()
                    : r < 65 ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                    : r < 80 ? Blocks.DYED_TERRACOTTA.red().defaultBlockState()
                    : r < 90 ? Blocks.CONCRETE.gray().defaultBlockState()
                    : Blocks.DYED_TERRACOTTA.brown().defaultBlockState();
            case MEDITERRANEAN -> r < 45 ? Blocks.RESIN_BRICKS.defaultBlockState()
                    : r < 75 ? Blocks.DYED_TERRACOTTA.orange().defaultBlockState()
                    : r < 90 ? Blocks.TERRACOTTA.defaultBlockState()
                    : Blocks.BRICKS.defaultBlockState();
            case TROPICAL -> r < 40 ? Blocks.CONCRETE.lightGray().defaultBlockState()
                    : r < 60 ? Blocks.DYED_TERRACOTTA.red().defaultBlockState()
                    : r < 75 ? Blocks.RESIN_BRICKS.defaultBlockState()
                    : r < 88 ? Blocks.CONCRETE.gray().defaultBlockState()
                    : Blocks.CONCRETE.blue().defaultBlockState();
        };
    }

    private static BlockState accentFor(BlockState wall, String type, long h) {
        // A slightly different block for corners / storey bands so large
        // facades don't read as one flat slab.
        if (wall.is(Blocks.BRICKS)) return Blocks.STONE_BRICKS.defaultBlockState();
        if (wall.is(Blocks.CONCRETE.white())) return Blocks.CONCRETE.lightGray().defaultBlockState();
        if (wall.is(Blocks.CONCRETE.lightGray())) return Blocks.CONCRETE.gray().defaultBlockState();
        if (wall.is(Blocks.STONE_BRICKS)) return Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        if (wall.is(Blocks.COBBLESTONE)) return Blocks.STONE_BRICKS.defaultBlockState();
        if (wall.is(Blocks.SPRUCE_PLANKS) || wall.is(Blocks.OAK_PLANKS) || wall.is(Blocks.BIRCH_PLANKS)) return Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState();
        if (wall.is(Blocks.DYED_TERRACOTTA.red())) return Blocks.CONCRETE.white().defaultBlockState();
        if (wall.is(Blocks.DYED_TERRACOTTA.yellow()) || wall.is(Blocks.DYED_TERRACOTTA.white())) return Blocks.CONCRETE.white().defaultBlockState();
        if (wall.is(Blocks.QUARTZ_BLOCK)) return Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();
        if (wall.is(Blocks.SMOOTH_SANDSTONE) || wall.is(Blocks.SANDSTONE)) return Blocks.CUT_SANDSTONE.defaultBlockState();
        return wall;
    }

    private static BlockState floorFor(BlockState wall, String type) {
        if (wall.is(Blocks.SPRUCE_PLANKS) || wall.is(Blocks.OAK_PLANKS) || wall.is(Blocks.BIRCH_PLANKS)
                || wall.is(Blocks.DYED_TERRACOTTA.red()) || isResidentialType(type)) {
            return Blocks.SPRUCE_PLANKS.defaultBlockState();
        }
        if (wall.is(Blocks.STAINED_GLASS.lightBlue()) || wall.is(Blocks.GLASS)) return Blocks.SMOOTH_STONE.defaultBlockState();
        return Blocks.SMOOTH_STONE.defaultBlockState();
    }

    public static RoofShape roofShape(Map<String, String> tags, String type, int levels, double halfA, double halfB,
                                      boolean domed, long id) {
        RoofShape explicit = RoofShape.parse(tags.get("roof:shape"), null);
        if (explicit != null) return explicit;
        if (domed) return RoofShape.DOME;
        long h = mix(id ^ 0x9E3779B97F4A7C15L);
        int r = (int) (h % 100);
        if ("silo".equals(type) || "storage_tank".equals(type)) return RoofShape.CONE;
        if ("water_tower".equals(type)) return RoofShape.FLAT;
        if ("greenhouse".equals(type)) return RoofShape.GABLED;
        if ("barn".equals(type) || "farm_auxiliary".equals(type) || "stable".equals(type) || "cowshed".equals(type)) return RoofShape.GABLED;
        if ("church".equals(type) || "cathedral".equals(type) || "chapel".equals(type)) return RoofShape.GABLED;
        if ("garage".equals(type) || "garages".equals(type) || "shed".equals(type) || "carport".equals(type)) return r < 50 ? RoofShape.FLAT : RoofShape.GABLED;
        if ("roof".equals(type) || "shelter".equals(type)) return r < 60 ? RoofShape.FLAT : RoofShape.GABLED;
        if (isFlatRoofType(type)) return RoofShape.FLAT;
        if ("apartments".equals(type) || "dormitory".equals(type)) return levels >= 4 ? RoofShape.FLAT : (r < 60 ? RoofShape.GABLED : RoofShape.HIPPED);
        if (isResidentialType(type)) {
            if (levels >= 6) return RoofShape.FLAT;
            if (halfB > 14) return r < 50 ? RoofShape.FLAT : RoofShape.HIPPED;
            return r < 62 ? RoofShape.GABLED : r < 90 ? RoofShape.HIPPED : RoofShape.FLAT;
        }
        return halfB > 12 ? RoofShape.FLAT : (r < 50 ? RoofShape.GABLED : r < 70 ? RoofShape.HIPPED : RoofShape.FLAT);
    }

    public static int roofHeightBlocks(Map<String, String> tags, RoofShape shape, double halfA, double halfB, int storeyBlocks) {
        double explicit = OsmTags.parseLength(tags.get("roof:height"), Double.NaN);
        if (!Double.isNaN(explicit) && explicit > 0) return (int) Math.max(1, Math.round(explicit));
        int roofLevels = OsmTags.parseInt(tags.get("roof:levels"), -1);
        if (roofLevels > 0 && shape != RoofShape.FLAT) return Math.max(1, roofLevels * storeyBlocks);
        double b = Math.max(1.0, halfB);
        double a = Math.max(1.0, halfA);
        return switch (shape) {
            case FLAT -> 0;
            case GABLED, HIPPED, HALF_HIPPED, SALTBOX -> clamp((int) Math.round(b * 0.75), 2, 9);
            case PYRAMIDAL -> clamp((int) Math.round(Math.min(a, b) * 0.8), 2, 12);
            case SKILLION -> clamp((int) Math.round(b * 0.4), 1, 5);
            case GAMBREL, MANSARD -> clamp((int) Math.round(b * 0.7), 2, 8);
            case ROUND -> clamp((int) Math.round(b * 0.6), 2, 10);
            case DOME -> clamp((int) Math.round(b), 2, 40);
            case ONION -> clamp((int) Math.round(b * 1.3), 3, 45);
            case CONE -> clamp((int) Math.round(Math.min(a, b) * 2.0), 3, 40);
        };
    }

    // ------------------------------------------------------------------ roads

    public static BlockState roadSurface(RoadFeature.Kind kind, String highway, String surface, String tracktype) {
        String s = lower(surface);
        if (s != null) {
            BlockState explicit = surfaceBlock(s);
            if (explicit != null) return explicit;
        }
        return switch (kind) {
            case RAIL -> Blocks.GRAVEL.defaultBlockState();
            case RUNWAY, TAXIWAY -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case PIER -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case STEPS -> Blocks.STONE_BRICKS.defaultBlockState();
            case TRACK -> switch (tracktype == null ? "" : tracktype) {
                case "grade1" -> Blocks.GRAVEL.defaultBlockState();
                case "grade2" -> Blocks.GRAVEL.defaultBlockState();
                case "grade3" -> Blocks.COARSE_DIRT.defaultBlockState();
                default -> Blocks.DIRT_PATH.defaultBlockState();
            };
            case PATH -> switch (highway == null ? "" : highway) {
                case "footway", "sidewalk" -> Blocks.SMOOTH_STONE.defaultBlockState();
                case "pedestrian" -> Blocks.STONE_BRICKS.defaultBlockState();
                case "cycleway" -> Blocks.CONCRETE.gray().defaultBlockState();
                case "bridleway" -> Blocks.DIRT_PATH.defaultBlockState();
                case "corridor" -> Blocks.SMOOTH_STONE.defaultBlockState();
                default -> Blocks.DIRT_PATH.defaultBlockState();
            };
            case ROAD -> Blocks.CONCRETE.gray().defaultBlockState();
        };
    }

    public static BlockState surfaceBlock(String s) {
        return switch (s) {
            case "asphalt", "chipseal", "paved", "tarmac", "bitumen" -> Blocks.CONCRETE.gray().defaultBlockState();
            case "concrete", "concrete:plates", "concrete:lanes", "cement" -> Blocks.CONCRETE.lightGray().defaultBlockState();
            case "paving_stones", "paving_stones:30", "paving_stones:20", "paved_stones", "pavers", "flagstone" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "sett", "cobblestone", "cobblestone:flattened", "unhewn_cobblestone", "cobbles" -> Blocks.COBBLESTONE.defaultBlockState();
            case "gravel", "fine_gravel", "compacted", "pebblestone", "unpaved", "crushed_stone", "shells" -> Blocks.GRAVEL.defaultBlockState();
            case "dirt", "earth", "ground", "mud", "clay", "soil" -> Blocks.DIRT_PATH.defaultBlockState();
            case "grass" -> Blocks.GRASS_BLOCK.defaultBlockState();
            case "sand" -> Blocks.SAND.defaultBlockState();
            case "wood", "woodchips", "boardwalk", "planks", "decking" -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "metal", "metal_grid", "steel" -> Blocks.SMOOTH_STONE.defaultBlockState();
            case "brick", "bricks" -> Blocks.BRICKS.defaultBlockState();
            case "tartan", "rubber", "acrylic" -> Blocks.DYED_TERRACOTTA.red().defaultBlockState();
            case "artificial_turf", "artificial_grass" -> Blocks.MOSS_BLOCK.defaultBlockState();
            case "stone", "rock", "bedrock" -> Blocks.STONE.defaultBlockState();
            case "snow" -> Blocks.SNOW_BLOCK.defaultBlockState();
            case "ice" -> Blocks.PACKED_ICE.defaultBlockState();
            case "granite" -> Blocks.POLISHED_GRANITE.defaultBlockState();
            case "marble" -> Blocks.QUARTZ_BLOCK.defaultBlockState();
            case "sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "clay_court", "clay:court" -> Blocks.TERRACOTTA.defaultBlockState();
            default -> null;
        };
    }

    public static BlockState sidewalkBlock(BlockState roadSurface) {
        if (roadSurface.is(Blocks.COBBLESTONE)) return Blocks.STONE_BRICKS.defaultBlockState();
        if (roadSurface.is(Blocks.GRAVEL) || roadSurface.is(Blocks.DIRT_PATH)) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.SMOOTH_STONE.defaultBlockState();
    }

    // ------------------------------------------------------------------ helpers

    public static String firstTag(Map<String, String> tags, String... keys) {
        for (String k : keys) {
            String v = tags.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** SplitMix64 finaliser: a cheap, well-distributed hash of an id. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z & Long.MAX_VALUE;
    }
}
