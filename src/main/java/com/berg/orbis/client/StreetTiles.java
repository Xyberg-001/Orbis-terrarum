package com.berg.orbis.client;

import com.berg.orbis.net.OrbisHttp;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The Street map layer, drawn here from OpenStreetMap's own data the way Google Maps or openstreetmap.org show it:
 * streets by class with their names, buildings, parks, water and rail, shops, restaurants, hotels and other places
 * as coloured dots with names, and house numbers close in. Esri's street map (the layer until 4 Oct 2026) showed
 * roads and a few names; in Kathmandu's Thamel it named nothing between the main roads.
 *
 * The data are OpenFreeMap's vector tiles (OpenMapTiles schema; free, no key, no usage limits): zoom 0-14 from the
 * server, finer zooms drawn from the zoom-14 tile. Each 256-pixel map tile is drawn at 512 pixels with Java2D, text
 * at twice its on-screen size, so it stays sharp. The tiles are kept in the map tile cache like the photo tiles.
 */
public final class StreetTiles {

    /** The layer's service name in {@link MapTiles}. */
    public static final String SERVICE = "orbis-street";
    /** The same map in dark colours. */
    public static final String SERVICE_DARK = "orbis-street-dark";
    public static final String CREDIT = "© OpenStreetMap contributors, OpenFreeMap, OpenMapTiles";

    private static final String TILEJSON = "https://tiles.openfreemap.org/planet";
    private static final int PX = 512, SOURCE_MAX = 14;
    /** Text and dots are drawn this much larger than first designed. */
    private static final float TEXT_SCALE = 1.5f;
    /** The tile addresses change with each weekly build of the map: a copy is fresh for a week. */
    private static final long FRESH_S = 7 * 86_400L;

    private static volatile String template;
    private static volatile long templateAt;

    private StreetTiles() {
    }

    // ------------------------------------------------------------------ colours

    private static final Color BACKGROUND = new Color(0xF3F1EC);
    private static final Color WATER = new Color(0xAAD3E8);
    private static final Color WATER_TEXT = new Color(0x3E6E96);
    private static final Color BUILDING = new Color(0xDDD6CD), BUILDING_EDGE = new Color(0xC9BEB2);
    private static final Color TEXT = new Color(0x2E2E2E), TEXT_GREY = new Color(0x666666), HALO = new Color(255, 255, 255, 230);
    private static final Color HALO_DARK = new Color(0x17, 0x1C, 0x22, 230);

    /**
     * The dark map's colours for the light map's: dark ground and buildings, streets a little lighter than the ground
     * (as Google's night map has them), muted greens and blues, light text on a dark halo.
     */
    private static final Map<Integer, Color> DARK = new HashMap<>();

    private static void dark(int light, int dark) {
        DARK.put(light, new Color(dark));
    }

    static {
        dark(0xF3F1EC, 0x23292F); // ground
        dark(0xAAD3E8, 0x1A3048); // water
        dark(0x3E6E96, 0x7FA6CF); // water names
        dark(0xDDD6CD, 0x323A43);
        dark(0xC9BEB2, 0x3E4752); // buildings
        dark(0x2E2E2E, 0xE4E7EB); // text
        dark(0xFFFFFF, 0x414A57); // white: streets (halo, rings and rail dashes are dark separately)
        DARK.put(0x000000, new Color(0x171C22)); // the halo's colour is set below
        dark(0xC2DDAF, 0x1F3326);
        dark(0xD5EDC4, 0x223829);
        dark(0xEEF0D9, 0x2A302B);
        dark(0xD3E8E2, 0x1F3331);
        dark(0xF4E8C4, 0x3A3628);
        dark(0xE2DED8, 0x33363A);
        dark(0xF2F7FB, 0x3A4350);
        dark(0xEDE9E3, 0x272D34);
        dark(0xF6E8E4, 0x2D2B31);
        dark(0xEEE8EE, 0x2D2A34);
        dark(0xF7E0E0, 0x352B30);
        dark(0xF6EFD6, 0x34302A);
        dark(0xD5E2CF, 0x25312A);
        dark(0xCDEBC6, 0x21392A);
        dark(0xF2DCDC, 0x382A2B);
        dark(0xCFE9BF, 0x22392A);
        dark(0xD9D4DF, 0x3A3F4A);
        dark(0xECE8EF, 0x2E333B);
        dark(0xF5A36A, 0xB06A3C);
        dark(0xC6773F, 0x6E4124);
        dark(0xF8BD78, 0xA2703F);
        dark(0xCC8A47, 0x6A4728);
        dark(0xFCD88C, 0x8F7442);
        dark(0xC9A354, 0x5E4C2B);
        dark(0xFFF2A8, 0x6E6C4B);
        dark(0xBDAF73, 0x4A4932);
        dark(0xC5BEB4, 0x2C333C);
        dark(0xC9C3BA, 0x2C333C);
        dark(0xCFC9C0, 0x2C333C);
        dark(0xB79C7B, 0x7A6548);
        dark(0xE07B62, 0x7A5A50);
        dark(0xE9E4DC, 0x3A3F47);
        dark(0xF7F4EF, 0x3A4049);
        dark(0xD8D1C7, 0x2C323A);
        dark(0x9DBEDF, 0x3D6C97);
        dark(0xBDB4A8, 0x6E675C);
        dark(0xD5CDC2, 0x2B3139);
        dark(0xFBF9F6, 0x4E5663);
        dark(0x9A9AAE, 0x7C7F93);
        dark(0x8E8E8E, 0x6E737B);
        dark(0x9E7FB0, 0x9A80B6);
        dark(0x7A5A3A, 0xCDA77F);
        dark(0x5C5C5C, 0xBBC1C9);
        dark(0x666666, 0xA3A9B1);
        dark(0x8A7E74, 0x8E949C);
    }

    /** A colour the dark palette does not list: its lightness turned round, muted. */
    private static Color darkOf(Color c) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1] * 0.6f, 0.12f + (1 - hsb[2]) * 0.8f);
    }

    private static Color area(String layer, String cls) {
        if (cls == null) cls = "";
        switch (layer) {
            case "landcover" -> {
                return switch (cls) {
                    case "wood", "forest" -> new Color(0xC2DDAF);
                    case "grass", "park" -> new Color(0xD5EDC4);
                    case "farmland" -> new Color(0xEEF0D9);
                    case "wetland" -> new Color(0xD3E8E2);
                    case "sand", "beach" -> new Color(0xF4E8C4);
                    case "rock" -> new Color(0xE2DED8);
                    case "ice", "glacier" -> new Color(0xF2F7FB);
                    default -> null;
                };
            }
            case "landuse" -> {
                return switch (cls) {
                    case "residential", "suburb", "quarter", "neighbourhood" -> new Color(0xEDE9E3);
                    case "commercial", "retail" -> new Color(0xF6E8E4);
                    case "industrial", "railway", "garages" -> new Color(0xEEE8EE);
                    case "hospital" -> new Color(0xF7E0E0);
                    case "school", "college", "university", "kindergarten" -> new Color(0xF6EFD6);
                    case "cemetery" -> new Color(0xD5E2CF);
                    case "stadium", "pitch", "playground", "track" -> new Color(0xCDEBC6);
                    case "military" -> new Color(0xF2DCDC);
                    default -> null;
                };
            }
            case "park" -> {
                return new Color(0xCFE9BF);
            }
            case "aeroway" -> {
                return "runway".equals(cls) || "taxiway".equals(cls) ? new Color(0xD9D4DF) : new Color(0xECE8EF);
            }
            default -> {
                return null;
            }
        }
    }

    /** Road style by class: {fill, casing, metres wide, least pixels, first zoom}; null for classes not drawn. */
    private record RoadStyle(Color fill, Color casing, double metres, double minPx, int fromZoom, int rank) {
    }

    private static RoadStyle road(String cls) {
        if (cls == null) return null;
        return switch (cls) {
            case "motorway" -> new RoadStyle(new Color(0xF5A36A), new Color(0xC6773F), 22, 3.2, 0, 9);
            case "trunk" -> new RoadStyle(new Color(0xF8BD78), new Color(0xCC8A47), 20, 3.0, 0, 8);
            case "primary" -> new RoadStyle(new Color(0xFCD88C), new Color(0xC9A354), 18, 2.6, 7, 7);
            case "secondary" -> new RoadStyle(new Color(0xFFF2A8), new Color(0xBDAF73), 15, 2.2, 9, 6);
            case "tertiary" -> new RoadStyle(Color.WHITE, new Color(0xC5BEB4), 13, 2.0, 11, 5);
            case "minor", "busway", "raceway" -> new RoadStyle(Color.WHITE, new Color(0xC9C3BA), 10, 1.6, 13, 4);
            case "service" -> new RoadStyle(Color.WHITE, new Color(0xCFC9C0), 6, 1.2, 14, 3);
            case "track" -> new RoadStyle(new Color(0xB79C7B), null, 4, 1.2, 15, 2);
            case "path" -> new RoadStyle(new Color(0xE07B62), null, 2.5, 1.2, 15, 1);
            case "pier" -> new RoadStyle(new Color(0xE9E4DC), null, 5, 1.5, 15, 1);
            default -> null;
        };
    }

    /** The symbols drawn in places' dots. */
    private enum Icon { BAG, CART, FOOD, CUP, GLASS, BED, CROSS, PILL, SCHOOL, BOOK, COLUMNS, STAR, FILM, BUS, TRAIN, BOAT, PLANE,
        PARKING, BANK_SIGN, FUEL, WORSHIP, TREE, BALL, MAIL, SHIELD }

    /** A place's symbol by its kind (OpenMapTiles' poi class and subclass); null for a plain dot. */
    private static Icon iconOf(String cls, String sub) {
        if (cls == null) return null;
        if ("railway".equals(cls) || "subway".equals(sub) || "tram_stop".equals(sub) || "halt".equals(sub) || "station".equals(sub) && !"bus".equals(cls))
            return "bus".equals(cls) ? Icon.BUS : Icon.TRAIN;
        return switch (cls) {
            case "shop", "clothing_store", "shoes", "jewelry", "gift", "mobile_phone", "furniture", "hardware", "books", "alcohol_shop",
                 "bakery", "butcher", "convenience", "department_store", "mall" -> Icon.BAG;
            case "grocery", "supermarket" -> Icon.CART;
            case "restaurant", "fast_food", "food_court" -> Icon.FOOD;
            case "cafe", "ice_cream" -> Icon.CUP;
            case "bar", "beer" -> Icon.GLASS;
            case "lodging", "campsite" -> Icon.BED;
            case "hospital", "doctors", "dentist", "veterinary", "clinic" -> Icon.CROSS;
            case "pharmacy" -> Icon.PILL;
            case "school", "college", "university", "kindergarten" -> Icon.SCHOOL;
            case "library" -> Icon.BOOK;
            case "museum", "art_gallery", "town_hall", "courthouse" -> Icon.COLUMNS;
            case "attraction", "castle", "monument", "viewpoint", "zoo", "aquarium" -> Icon.STAR;
            case "theatre", "cinema" -> Icon.FILM;
            case "bus" -> Icon.BUS;
            case "ferry_terminal", "harbor" -> Icon.BOAT;
            case "airport", "aerialway" -> Icon.PLANE;
            case "parking", "bicycle_parking" -> Icon.PARKING;
            case "bank" -> Icon.BANK_SIGN;
            case "fuel", "charging_station" -> Icon.FUEL;
            case "place_of_worship" -> Icon.WORSHIP;
            case "park", "garden" -> Icon.TREE;
            case "stadium", "pitch", "sports", "swimming", "golf" -> Icon.BALL;
            case "post" -> Icon.MAIL;
            case "police", "fire_station" -> Icon.SHIELD;
            default -> null;
        };
    }

    /** A place's dot colour by its kind (OpenMapTiles' poi class). */
    private static Color poiColour(String cls) {
        if (cls == null) return new Color(0x7A7A7A);
        return switch (cls) {
            case "restaurant", "fast_food", "cafe", "bar", "beer", "ice_cream", "food_court" -> new Color(0xE0782F);
            case "shop", "grocery", "clothing_store", "shoes", "bakery", "butcher", "jewelry", "books", "gift", "mobile_phone",
                 "furniture", "hardware", "alcohol_shop", "convenience", "supermarket" -> new Color(0x8E4FC2);
            case "lodging", "campsite" -> new Color(0x2F7FD0);
            case "hospital", "pharmacy", "doctors", "dentist", "veterinary" -> new Color(0xD6403A);
            case "school", "college", "university", "kindergarten", "library" -> new Color(0x9A6B3E);
            case "museum", "art_gallery", "attraction", "castle", "monument", "theatre", "cinema", "zoo", "viewpoint" -> new Color(0x1E9C8F);
            case "bus", "railway", "tram", "ferry_terminal", "aerialway", "airport", "taxi", "bicycle_rental", "parking", "fuel" -> new Color(0x3366CC);
            case "place_of_worship", "cemetery" -> new Color(0x6E6E6E);
            case "park", "playground", "garden", "sports", "stadium", "pitch", "swimming", "golf" -> new Color(0x4C9A3B);
            case "town_hall", "police", "fire_station", "post", "bank", "office" -> new Color(0x6A6A9A);
            default -> new Color(0x7A7A7A);
        };
    }

    // ------------------------------------------------------------------ data

    /** The tile address template from OpenFreeMap's TileJSON (it carries the current weekly build). */
    private static String template(TileDiskCache disk) throws IOException, InterruptedException {
        String t = template;
        if (t != null && System.currentTimeMillis() - templateAt < 6 * 3_600_000L) return t;
        synchronized (StreetTiles.class) {
            if (template != null && System.currentTimeMillis() - templateAt < 6 * 3_600_000L) return template;
            Path saved = disk.dir().resolve("openfreemap-template.txt");
            if (template == null && Files.isRegularFile(saved)) {
                // The last known build at once (cached tiles need no new address), the current one asked meanwhile.
                template = Files.readString(saved, StandardCharsets.UTF_8).trim();
                templateAt = System.currentTimeMillis() - 6 * 3_600_000L + 60_000L; // asked again within a minute
                Thread refresh = new Thread(() -> {
                    try {
                        refreshTemplate(saved);
                    } catch (IOException | RuntimeException | InterruptedException ignored) {
                    }
                }, "Orbis street map address");
                refresh.setDaemon(true);
                refresh.start();
                return template;
            }
            try {
                OrbisHttp.Response r = OrbisHttp.get(TILEJSON, Map.of("User-Agent", "OrbisTerrarum/1.0 (Minecraft mod; street map)"), 20);
                if (r.status() != 200) throw new IOException("HTTP " + r.status());
                String json = new String(r.body(), StandardCharsets.UTF_8);
                String url = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("tiles").get(0).getAsString();
                Files.createDirectories(saved.getParent());
                Files.writeString(saved, url, StandardCharsets.UTF_8);
                template = url;
            } catch (IOException | RuntimeException e) {
                if (Files.isRegularFile(saved)) template = Files.readString(saved, StandardCharsets.UTF_8).trim();
                else throw e instanceof IOException io ? io : new IOException(e);
            }
            templateAt = System.currentTimeMillis();
            return template;
        }
    }

    private static void refreshTemplate(Path saved) throws IOException, InterruptedException {
        OrbisHttp.Response r = OrbisHttp.get(TILEJSON, Map.of("User-Agent", "OrbisTerrarum/1.0 (Minecraft mod; street map)"), 20);
        if (r.status() != 200) return;
        String url = com.google.gson.JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("tiles").get(0).getAsString();
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, url, StandardCharsets.UTF_8);
        synchronized (StreetTiles.class) {
            template = url;
            templateAt = System.currentTimeMillis();
        }
    }

    private static byte[] tileBytes(TileDiskCache disk, int z, int x, int y) throws IOException, InterruptedException {
        String url = template(disk).replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        byte[] b = disk.fetch(url, "openfreemap", z, x, y, Map.of("Accept-Encoding", "gzip"), FRESH_S);
        if (b != null && b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
                b = in.readAllBytes();
            }
        }
        return b;
    }

    /** One feature of a vector tile. */
    private record Feature(String layer, int type, long[] geometry, Map<String, Object> tags, int[] box) {
        String str(String key) {
            Object v = tags.get(key);
            return v == null ? null : v.toString();
        }

        double num(String key, double fallback) {
            Object v = tags.get(key);
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }
    }

    // ------------------------------------------------------------------ drawing

    /** The 512-pixel picture (ARGB) of map tile (z, x, y). */
    public static int[] render(TileDiskCache disk, int z, int x, int y) throws IOException, InterruptedException {
        return render(disk, z, x, y, false);
    }

    /** The picture of map tile (z, x, y), in the dark colours when {@code dark}. */
    public static int[] render(TileDiskCache disk, int z, int x, int y, boolean dark) throws IOException, InterruptedException {
        int sz = Math.min(z, SOURCE_MAX), d = z - sz;
        int sx = x >> d, sy = y >> d;
        // A tile drawn before, from the same map data, is read back (a few ms against half a second to draw it).
        Path drawn = disk.dir().resolve((dark ? "street-dark-" : "street-") + STYLE).resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".img");
        Path source = disk.dir().resolve("openfreemap").resolve(String.valueOf(sz)).resolve(String.valueOf(sx)).resolve(sy + ".img");
        int[] again = readDrawn(drawn, source);
        if (again != null) return again;
        Decoded dec = decoded(disk, sz, sx, sy);
        Map<String, List<Feature>> layers = dec.layers();
        boolean empty = dec.empty();
        int[] out = draw(z, x, y, sz, sx, sy, layers, empty, dark);
        writeDrawn(drawn, out);
        return out;
    }

    /** Bumped when the drawing changes, so tiles drawn the old way are drawn again. */
    private static final String STYLE = "v2";

    private static int[] readDrawn(Path drawn, Path source) {
        try {
            if (!Files.isRegularFile(drawn) || !Files.isRegularFile(source)) return null;
            if (Files.getLastModifiedTime(drawn).compareTo(Files.getLastModifiedTime(source)) < 0) return null; // data newer
            BufferedImage img = javax.imageio.ImageIO.read(drawn.toFile());
            if (img == null || img.getWidth() != PX || img.getHeight() != PX) return null;
            return img.getRGB(0, 0, PX, PX, null, 0, PX);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeDrawn(Path drawn, int[] argb) {
        try {
            BufferedImage img = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, PX, PX, argb, 0, PX);
            Files.createDirectories(drawn.getParent());
            Path tmp = drawn.resolveSibling(drawn.getFileName() + ".tmp" + Thread.currentThread().threadId());
            javax.imageio.ImageIO.write(img, "png", tmp.toFile());
            Files.move(tmp, drawn, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Only costs drawing it again next time.
        }
    }

    /** A source tile decoded once for all the finer tiles drawn from it (a zoom-14 tile serves 4 to 1024 of them). */
    private record Decoded(Map<String, List<Feature>> layers, boolean empty) {
    }

    private static final Map<String, java.util.concurrent.CompletableFuture<Decoded>> DECODED = new java.util.LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, java.util.concurrent.CompletableFuture<Decoded>> e) {
            return size() > 8; // a busy city tile decodes to tens of MB
        }
    };

    private static Decoded decoded(TileDiskCache disk, int z, int x, int y) throws IOException, InterruptedException {
        String key = z + "/" + x + "/" + y;
        java.util.concurrent.CompletableFuture<Decoded> f;
        boolean mine = false;
        synchronized (DECODED) {
            f = DECODED.get(key);
            if (f == null) {
                f = new java.util.concurrent.CompletableFuture<>();
                DECODED.put(key, f);
                mine = true;
            }
        }
        if (mine) {
            try {
                byte[] mvt = tileBytes(disk, z, x, y);
                boolean empty = mvt == null || mvt.length == 0;
                f.complete(new Decoded(empty ? Map.of() : decode(mvt), empty));
            } catch (IOException | RuntimeException | InterruptedException e) {
                synchronized (DECODED) {
                    DECODED.remove(key, f);
                }
                f.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            throw new IOException(c);
        }
    }

    private static int[] draw(int z, int x, int y, int sz, int sx, int sy, Map<String, List<Feature>> layers, boolean empty, boolean dark) {
        BufferedImage img = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // An empty tile is open sea (OpenMapTiles leaves nothing out on land).
            Painter painter = new Painter(g, z, x, y, sz, sx, sy, layers, dark);
            g.setColor(painter.c(empty ? WATER : BACKGROUND));
            g.fillRect(0, 0, PX, PX);
            painter.paint();
        } finally {
            g.dispose();
        }
        return img.getRGB(0, 0, PX, PX, null, 0, PX);
    }

    private static final class Painter {
        final Graphics2D g;
        /** The dark map: every colour through {@link #DARK}. */
        final boolean dark;
        final int z;
        final Map<String, List<Feature>> layers;
        final double scale, ox, oy, metresPerPx;
        final List<Rectangle2D> placed = new ArrayList<>();
        final Set<String> namedRoads = new HashSet<>();
        final int extentDefault = 4096;
        Map<String, Integer> extents = new HashMap<>();

        Painter(Graphics2D g, int z, int x, int y, int sz, int sx, int sy, Map<String, List<Feature>> layers, boolean dark) {
            this.g = g;
            this.dark = dark;
            this.z = z;
            this.layers = layers;
            int d = z - sz;
            double tiles = 1 << d;
            this.scale = PX * tiles; // pixels per source tile; divided by the extent per feature
            this.ox = -(x - sx * tiles) * PX;
            this.oy = -(y - sy * tiles) * PX;
            double n = Math.PI - 2 * Math.PI * (y + 0.5) / (1 << z);
            double lat = Math.toDegrees(Math.atan(Math.sinh(n)));
            this.metresPerPx = 40_075_016.686 * Math.cos(Math.toRadians(lat)) / (1L << z) / PX;
        }

        /** A layer's features that reach into this tile (a zoom-17 tile is a 64th of its source tile). */
        /** A colour of the light map, or its counterpart on the dark one. */
        Color c(Color light) {
            if (!dark || light == null) return light;
            Color d = DARK.get(light.getRGB() & 0xFFFFFF);
            if (d == null) d = darkOf(light);
            return light.getAlpha() == 255 ? d : new Color(d.getRed(), d.getGreen(), d.getBlue(), light.getAlpha());
        }

        /** A colour made lighter for dark ground (places' dots and names). */
        static Color lighter(Color c) {
            float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            return Color.getHSBColor(hsb[0], Math.min(hsb[1], 0.55f), Math.max(hsb[2], 0.85f));
        }

        List<Feature> layer(String name) {
            List<Feature> all = layers.getOrDefault(name, List.of());
            if (scale <= PX) return all;
            double k = scale / 4096, margin = 160; // pixels: labels and wide strokes reach past their geometry
            List<Feature> in = new ArrayList<>();
            for (Feature f : all) {
                int[] b = f.box();
                if (b[2] * k + ox < -margin || b[0] * k + ox > PX + margin || b[3] * k + oy < -margin || b[1] * k + oy > PX + margin) continue;
                in.add(f);
            }
            return in;
        }

        void paint() {
            for (String l : new String[]{"landcover", "landuse", "park"}) {
                for (Feature f : layer(l)) {
                    if (f.type != 3) continue;
                    Color c = c(area(l, f.str("class")));
                    if (c == null) continue;
                    g.setColor(c);
                    g.fill(path(f, true));
                }
            }
            g.setColor(c(WATER));
            for (Feature f : layer("water")) if (f.type == 3) g.fill(path(f, true));
            for (Feature f : layer("waterway")) {
                if (f.type != 2) continue;
                String cls = f.str("class");
                double m = "river".equals(cls) ? 12 : "canal".equals(cls) ? 8 : 3;
                if (!"river".equals(cls) && z < 13) continue;
                g.setStroke(new BasicStroke((float) Math.max(1.2, Math.min(24, m / metresPerPx)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path(f, false));
            }
            for (Feature f : layer("aeroway")) {
                Color c = c(area("aeroway", f.str("class")));
                if (c == null) continue;
                g.setColor(c);
                if (f.type == 3) g.fill(path(f, true));
                else if (f.type == 2) {
                    g.setStroke(new BasicStroke((float) Math.max(2, 45 / metresPerPx)));
                    g.draw(path(f, false));
                }
            }
            if (z >= 14) {
                for (Feature f : layer("building")) {
                    if (f.type != 3) continue;
                    Shape s = path(f, true);
                    g.setColor(c(BUILDING));
                    g.fill(s);
                    if (z >= 16) {
                        g.setColor(c(BUILDING_EDGE));
                        g.setStroke(new BasicStroke(1.2f));
                        g.draw(s);
                    }
                }
            }
            roads();
            boundaries();
            labels();
        }

        void roads() {
            List<Feature> all = new ArrayList<>();
            List<Feature> rails = new ArrayList<>();
            for (Feature f : layer("transportation")) {
                if (f.type != 2) continue;
                String cls = f.str("class");
                if ("rail".equals(cls) || "transit".equals(cls)) {
                    rails.add(f);
                    continue;
                }
                RoadStyle st = road(cls);
                if (st == null || z < st.fromZoom()) continue;
                all.add(f);
            }
            all.sort((a, b) -> Integer.compare(order(a), order(b)));
            // Tunnels faded underneath, then each level: casings first, fills on top, so crossings join cleanly.
            for (int level = 0; level < 3; level++) {
                for (Feature f : all) if (levelOf(f) == level) drawRoad(f, true);
                for (Feature f : all) if (levelOf(f) == level) drawRoad(f, false);
                if (level == 1) for (Feature f : rails) drawRail(f);
            }
        }

        int levelOf(Feature f) {
            String b = f.str("brunnel");
            return "tunnel".equals(b) ? 0 : "bridge".equals(b) ? 2 : 1;
        }

        int order(Feature f) {
            RoadStyle st = styleOf(f);
            return st == null ? 0 : st.rank();
        }

        double widthPx(RoadStyle st) {
            return Math.max(st.minPx(), Math.min(70, st.metres() / metresPerPx));
        }

        /** Pedestrian streets and squares look like light streets (as on Google Maps), not footpaths. */
        static final RoadStyle PEDESTRIAN = new RoadStyle(new Color(0xF7F4EF), new Color(0xD8D1C7), 9, 1.6, 14, 3);

        RoadStyle styleOf(Feature f) {
            String cls = f.str("class");
            if ("path".equals(cls) && "pedestrian".equals(f.str("subclass"))) return PEDESTRIAN;
            return road(cls);
        }

        void drawRoad(Feature f, boolean casing) {
            RoadStyle st = styleOf(f);
            if (st == null) return;
            boolean tunnel = levelOf(f) == 0;
            if (tunnel && z < 15) return; // underground: only close in, faintly
            Shape s = path(f, false);
            if (st.casing() == null) {
                if (casing || tunnel) return;
                // Footpaths, sidewalks, cycleways and tracks: quiet lines of the same width at every zoom, as Google
                // shows them. Red dashes for every footway (Oslo maps each sidewalk as one) covered the city centre.
                float w = (float) Math.max(1.2, Math.min(2.6, 1.2 + (z - 15) * 0.35));
                String sub = f.str("subclass");
                if ("track".equals(f.str("class"))) {
                    g.setColor(c(st.fill()));
                    g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{7, 5}, 0));
                } else if ("cycleway".equals(sub)) {
                    g.setColor(c(new Color(0x9DBEDF)));
                    g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                } else if ("steps".equals(sub)) {
                    g.setColor(c(new Color(0xBDB4A8)));
                    g.setStroke(new BasicStroke(w * 1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{2, 2}, 0));
                } else {
                    // A white line with a faint edge: it shows on grass and in squares, and fades next to streets.
                    g.setColor(c(new Color(0xD5CDC2)));
                    g.setStroke(new BasicStroke(w + 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                    g.setColor(c(new Color(0xFBF9F6)));
                    g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                g.draw(s);
                return;
            }
            double w = widthPx(st);
            if (casing) {
                if (w < 2.4 || tunnel) return; // too thin for an edge (or underground): the fill alone
                g.setColor(c(st.casing()));
                g.setStroke(new BasicStroke((float) (w + 2.4), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(s);
            } else {
                Color c = c(st.fill());
                g.setColor(tunnel ? new Color(c.getRed(), c.getGreen(), c.getBlue(), 90) : c);
                g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(s);
            }
        }

        void drawRail(Feature f) {
            if (z < 10) return;
            if ("tunnel".equals(f.str("brunnel")) && z < 15) return;
            Shape s = path(f, false);
            boolean tram = "tram".equals(f.str("subclass")) || "light_rail".equals(f.str("subclass"));
            float w = (float) (tram ? 1.6 : Math.max(2.0, Math.min(5, 3.0 / metresPerPx)));
            g.setColor(tram ? c(new Color(0x9A9AAE)) : c(new Color(0x8E8E8E)));
            g.setStroke(new BasicStroke(w + (tram ? 0 : 1.5f)));
            g.draw(s);
            if (!tram && z >= 13) {
                g.setColor(dark ? c(BACKGROUND) : Color.WHITE);
                g.setStroke(new BasicStroke(w * 0.55f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{8, 8}, 0));
                g.draw(s);
            }
        }

        void boundaries() {
            for (Feature f : layer("boundary")) {
                if (f.type != 2) continue;
                double level = f.num("admin_level", 10);
                if (level > 4 || f.num("maritime", 0) == 1) continue;
                g.setColor(c(new Color(0x9E7FB0)));
                g.setStroke(new BasicStroke(level <= 2 ? 2.4f : 1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{10, 5, 3, 5}, 0));
                g.draw(path(f, false));
            }
        }

        // ------------------------------------------------------------------ labels

        void labels() {
            // Most important first: the first label to claim a spot keeps it.
            for (Feature f : layer("place")) placeLabel(f);
            for (Feature f : layer("water_name")) {
                String n = name(f);
                if (n != null && f.type == 1) pointText(f, n, font(Font.ITALIC, 22), c(WATER_TEXT), null, 0);
            }
            for (Feature f : layer("mountain_peak")) {
                if (z < 12 || f.type != 1) continue;
                String n = name(f);
                double ele = f.num("ele", Double.NaN);
                String label = n == null ? null : Double.isNaN(ele) ? n : n + " " + Math.round(ele) + " m";
                pointText(f, label, font(Font.PLAIN, 19), c(new Color(0x7A5A3A)), c(new Color(0x7A5A3A)), 7 * TEXT_SCALE);
            }
            if (z >= 14) roadNames();
            if (z >= 15) pois();
            if (z >= 18) houseNumbers();
        }

        void placeLabel(Feature f) {
            if (f.type != 1) return;
            String cls = f.str("class"), n = name(f);
            if (n == null || cls == null) return;
            Font font;
            Color c = c(TEXT);
            switch (cls) {
                case "city" -> {
                    if (z > 15) return;
                    font = font(Font.BOLD, z >= 11 ? 30 : 26);
                }
                case "town" -> {
                    if (z > 16 || z < 8) return;
                    font = font(Font.BOLD, 25);
                }
                case "village" -> {
                    if (z > 17 || z < 11) return;
                    font = font(Font.PLAIN, 23);
                }
                case "suburb", "quarter" -> {
                    if (z > 16 || z < 12) return;
                    font = font(Font.BOLD, 21);
                    c = c(new Color(0x5C5C5C));
                    n = n.toUpperCase(Locale.ROOT);
                }
                case "neighbourhood", "hamlet", "isolated_dwelling", "locality" -> {
                    if (z > 17 || z < 14) return;
                    font = font(Font.PLAIN, 20);
                    c = c(new Color(0x666666));
                }
                default -> {
                    return;
                }
            }
            pointText(f, n, font, c, null, 0);
        }

        void roadNames() {
            Font font = font(Font.PLAIN, z >= 17 ? 21 : 19);
            List<Feature> named = new ArrayList<>(layer("transportation_name"));
            named.sort((a, b) -> Integer.compare(order(b), order(a)));
            for (Feature f : named) {
                if (f.type != 2) continue;
                RoadStyle st = road(f.str("class"));
                if (st == null) continue;
                if (z < 15 && st.rank() < 5) continue;
                String n = name(f);
                if (n == null || !namedRoads.add(n)) continue;
                lineText(f, n, font);
            }
        }

        void pois() {
            List<Feature> pois = new ArrayList<>(layer("poi"));
            pois.sort((a, b) -> Double.compare(a.num("rank", 99), b.num("rank", 99)));
            int maxRank = z >= 17 ? 999 : z == 16 ? 40 : 12;
            Font font = font(Font.PLAIN, 18);
            for (Feature f : pois) {
                if (f.type != 1 || f.num("rank", 99) > maxRank) continue;
                String n = name(f);
                if (n == null) continue;
                Color c = poiColour(f.str("class"));
                if (dark) c = lighter(c);
                Icon icon = iconOf(f.str("class"), f.str("subclass"));
                pointText(f, n, font, dark ? lighter(c) : c.darker(), c, (icon == null ? 7 : 11) * TEXT_SCALE, icon);
            }
        }

        void houseNumbers() {
            Font font = font(Font.PLAIN, 16);
            for (Feature f : layer("housenumber")) {
                String n = f.str("housenumber");
                if (n != null && f.type == 1) pointText(f, n, font, c(new Color(0x8A7E74)), null, 0);
            }
        }

        /**
         * A font {@code size} pixels high as designed, times {@link #TEXT_SCALE}: a tile shows at 180 to 360 screen pixels
         * (the map switches zoom halfway), so text drawn for 256 came out small next to Minecraft's own (4 Oct 2026).
         */
        static Font font(int style, int size) {
            return new Font(Font.SANS_SERIF, style, Math.round(size * TEXT_SCALE));
        }

        /** A label at a point, with a dot (radius {@code dot}) to its left when {@code dotColour} is set. */
        void pointText(Feature f, String text, Font font, Color colour, Color dotColour, double dot) {
            pointText(f, text, font, colour, dotColour, dot, null);
        }

        void pointText(Feature f, String text, Font font, Color colour, Color dotColour, double dot, Icon icon) {
            if (text == null) return;
            double[] p = firstPoint(f);
            if (p == null) return;
            FontRenderContext frc = g.getFontRenderContext();
            TextLayout layout = new TextLayout(text, font, frc);
            Rectangle2D b = layout.getBounds();
            double tx, ty;
            if (dotColour != null) {
                tx = p[0] + dot + 4;
                ty = p[1] + b.getHeight() / 2 - 2;
            } else {
                tx = p[0] - b.getWidth() / 2;
                ty = p[1] + b.getHeight() / 2;
            }
            double top = Math.min(ty - b.getHeight(), dotColour != null ? p[1] - dot : Double.MAX_VALUE);
            double bottom = Math.max(ty, dotColour != null ? p[1] + dot : -Double.MAX_VALUE);
            Rectangle2D box = new Rectangle2D.Double(Math.min(tx, p[0] - dot) - 3, top - 3,
                    b.getWidth() + (dotColour != null ? dot * 2 + 4 : 0) + 6, bottom - top + 6);
            if (!free(box)) return;
            if (dotColour != null) {
                g.setColor(dark ? c(BACKGROUND) : Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(p[0] - dot - 2, p[1] - dot - 2, dot * 2 + 4, dot * 2 + 4));
                g.setColor(dotColour);
                g.fill(new java.awt.geom.Ellipse2D.Double(p[0] - dot, p[1] - dot, dot * 2, dot * 2));
                if (icon != null) drawIcon(icon, p[0], p[1], dot * 0.62);
            }
            halo(layout, AffineTransform.getTranslateInstance(tx, ty), colour);
            placed.add(box);
        }

        // ------------------------------------------------------------------ icons

        /**
         * A small white symbol in the place's dot, drawn with lines and shapes (Java's fonts have no reliable icons):
         * {@code r} is about half the symbol's size.
         */
        void drawIcon(Icon icon, double x, double y, double r) {
            Graphics2D h = (Graphics2D) g.create();
            try {
                h.translate(x, y);
                h.setColor(Color.WHITE);
                float sw = (float) Math.max(1.4, r * 0.24);
                h.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (icon) {
                    case BAG -> { // shopping bag
                        h.fill(new Rectangle2D.Double(-r * 0.75, -r * 0.35, r * 1.5, r * 1.25));
                        h.draw(new java.awt.geom.Arc2D.Double(-r * 0.42, -r * 0.95, r * 0.84, r * 1.1, 0, 180, java.awt.geom.Arc2D.OPEN));
                    }
                    case CART -> { // shopping trolley
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r * 0.95, -r * 0.7);
                        p.lineTo(-r * 0.6, -r * 0.7);
                        p.lineTo(-r * 0.35, r * 0.3);
                        p.lineTo(r * 0.75, r * 0.3);
                        p.lineTo(r * 0.9, -r * 0.4);
                        p.lineTo(-r * 0.5, -r * 0.4);
                        h.draw(p);
                        h.fill(new java.awt.geom.Ellipse2D.Double(-r * 0.4, r * 0.55, r * 0.35, r * 0.35));
                        h.fill(new java.awt.geom.Ellipse2D.Double(r * 0.45, r * 0.55, r * 0.35, r * 0.35));
                    }
                    case FOOD -> { // fork and knife
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.4, -r * 0.9, -r * 0.4, r * 0.9));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.7, -r * 0.9, -r * 0.7, -r * 0.25));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.1, -r * 0.9, -r * 0.1, -r * 0.25));
                        h.draw(new java.awt.geom.Arc2D.Double(-r * 0.7, -r * 0.6, r * 0.6, r * 0.7, 180, 180, java.awt.geom.Arc2D.OPEN));
                        Path2D k = new Path2D.Double();
                        k.moveTo(r * 0.5, r * 0.9);
                        k.lineTo(r * 0.5, -r * 0.9);
                        k.quadTo(r * 0.95, -r * 0.4, r * 0.55, r * 0.05);
                        h.draw(k);
                    }
                    case CUP -> { // coffee cup
                        h.fill(new Rectangle2D.Double(-r * 0.7, -r * 0.4, r * 1.05, r * 0.95));
                        h.draw(new java.awt.geom.Arc2D.Double(r * 0.15, -r * 0.25, r * 0.6, r * 0.55, -90, 180, java.awt.geom.Arc2D.OPEN));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.85, r * 0.8, r * 0.55, r * 0.8));
                    }
                    case GLASS -> { // drinks
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r * 0.75, -r * 0.8);
                        p.lineTo(r * 0.75, -r * 0.8);
                        p.lineTo(0, r * 0.1);
                        p.closePath();
                        h.fill(p);
                        h.draw(new java.awt.geom.Line2D.Double(0, r * 0.1, 0, r * 0.85));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.45, r * 0.85, r * 0.45, r * 0.85));
                    }
                    case BED -> { // hotel
                        h.fill(new Rectangle2D.Double(-r * 0.9, -r * 0.05, r * 1.8, r * 0.5));
                        h.fill(new java.awt.geom.Ellipse2D.Double(-r * 0.8, -r * 0.55, r * 0.55, r * 0.45));
                        h.fill(new Rectangle2D.Double(-r * 0.15, -r * 0.45, r * 1.05, r * 0.4));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.9, -r * 0.7, -r * 0.9, r * 0.8));
                        h.draw(new java.awt.geom.Line2D.Double(r * 0.9, r * 0.2, r * 0.9, r * 0.8));
                    }
                    case CROSS -> { // hospital, doctors
                        double t = r * 0.32;
                        h.fill(new Rectangle2D.Double(-t, -r * 0.85, t * 2, r * 1.7));
                        h.fill(new Rectangle2D.Double(-r * 0.85, -t, r * 1.7, t * 2));
                    }
                    case PILL -> { // pharmacy
                        h.fill(new java.awt.geom.RoundRectangle2D.Double(-r * 0.9, -r * 0.38, r * 1.8, r * 0.76, r * 0.76, r * 0.76));
                    }
                    case SCHOOL -> { // mortarboard
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r, -r * 0.2);
                        p.lineTo(0, -r * 0.7);
                        p.lineTo(r, -r * 0.2);
                        p.lineTo(0, r * 0.3);
                        p.closePath();
                        h.fill(p);
                        h.fill(new Rectangle2D.Double(-r * 0.55, -r * 0.05, r * 1.1, r * 0.6));
                        h.draw(new java.awt.geom.Line2D.Double(r * 0.8, -r * 0.2, r * 0.8, r * 0.55));
                    }
                    case BOOK -> { // library
                        h.fill(new Rectangle2D.Double(-r * 0.9, -r * 0.7, r * 0.8, r * 1.4));
                        h.fill(new Rectangle2D.Double(r * 0.1, -r * 0.7, r * 0.8, r * 1.4));
                    }
                    case COLUMNS -> { // museum, gallery, town hall, bank
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r, -r * 0.45);
                        p.lineTo(0, -r * 0.95);
                        p.lineTo(r, -r * 0.45);
                        p.closePath();
                        h.fill(p);
                        for (int i = -1; i <= 1; i++) h.fill(new Rectangle2D.Double(i * r * 0.6 - r * 0.14, -r * 0.35, r * 0.28, r * 0.95));
                        h.fill(new Rectangle2D.Double(-r, r * 0.65, r * 2, r * 0.25));
                    }
                    case STAR -> { // sights
                        Path2D p = new Path2D.Double();
                        for (int i = 0; i < 10; i++) {
                            double a = -Math.PI / 2 + i * Math.PI / 5, rr = i % 2 == 0 ? r : r * 0.42;
                            if (i == 0) p.moveTo(Math.cos(a) * rr, Math.sin(a) * rr);
                            else p.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
                        }
                        p.closePath();
                        h.fill(p);
                    }
                    case FILM -> { // cinema, theatre
                        h.fill(new java.awt.geom.RoundRectangle2D.Double(-r * 0.9, -r * 0.6, r * 1.8, r * 1.2, r * 0.3, r * 0.3));
                    }
                    case BUS -> {
                        h.fill(new java.awt.geom.RoundRectangle2D.Double(-r * 0.7, -r * 0.9, r * 1.4, r * 1.55, r * 0.4, r * 0.4));
                        h.fill(new Rectangle2D.Double(-r * 0.55, r * 0.6, r * 0.3, r * 0.3));
                        h.fill(new Rectangle2D.Double(r * 0.25, r * 0.6, r * 0.3, r * 0.3));
                    }
                    case TRAIN -> {
                        h.fill(new java.awt.geom.RoundRectangle2D.Double(-r * 0.6, -r * 0.95, r * 1.2, r * 1.5, r * 0.6, r * 0.6));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.4, r * 0.65, -r * 0.7, r * 0.95));
                        h.draw(new java.awt.geom.Line2D.Double(r * 0.4, r * 0.65, r * 0.7, r * 0.95));
                    }
                    case BOAT -> {
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r, r * 0.15);
                        p.lineTo(r, r * 0.15);
                        p.lineTo(r * 0.6, r * 0.75);
                        p.lineTo(-r * 0.6, r * 0.75);
                        p.closePath();
                        h.fill(p);
                        h.fill(new Rectangle2D.Double(-r * 0.45, -r * 0.55, r * 0.9, r * 0.55));
                    }
                    case PLANE -> {
                        h.fill(new Rectangle2D.Double(-r * 0.14, -r, r * 0.28, r * 2));
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r, r * 0.15);
                        p.lineTo(0, -r * 0.35);
                        p.lineTo(r, r * 0.15);
                        p.closePath();
                        h.fill(p);
                    }
                    case PARKING, BANK_SIGN -> {
                        Font f = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(r * 2.0));
                        String s = icon == Icon.PARKING ? "P" : "$";
                        TextLayout l = new TextLayout(s, f, h.getFontRenderContext());
                        Rectangle2D b = l.getBounds();
                        l.draw(h, (float) (-b.getCenterX()), (float) (-b.getCenterY()));
                    }
                    case FUEL -> {
                        h.fill(new Rectangle2D.Double(-r * 0.7, -r * 0.8, r * 0.95, r * 1.6));
                        h.draw(new java.awt.geom.Line2D.Double(r * 0.25, -r * 0.4, r * 0.75, -r * 0.1));
                        h.draw(new java.awt.geom.Line2D.Double(r * 0.75, -r * 0.1, r * 0.75, r * 0.6));
                    }
                    case WORSHIP -> {
                        h.fill(new Rectangle2D.Double(-r * 0.14, -r, r * 0.28, r * 1.9));
                        h.fill(new Rectangle2D.Double(-r * 0.6, -r * 0.55, r * 1.2, r * 0.26));
                    }
                    case TREE -> {
                        Path2D p = new Path2D.Double();
                        p.moveTo(0, -r);
                        p.lineTo(r * 0.8, r * 0.35);
                        p.lineTo(-r * 0.8, r * 0.35);
                        p.closePath();
                        h.fill(p);
                        h.fill(new Rectangle2D.Double(-r * 0.15, r * 0.3, r * 0.3, r * 0.6));
                    }
                    case BALL -> {
                        h.draw(new java.awt.geom.Ellipse2D.Double(-r * 0.8, -r * 0.8, r * 1.6, r * 1.6));
                        h.draw(new java.awt.geom.Line2D.Double(-r * 0.8, 0, r * 0.8, 0));
                        h.draw(new java.awt.geom.Line2D.Double(0, -r * 0.8, 0, r * 0.8));
                    }
                    case MAIL -> {
                        h.draw(new Rectangle2D.Double(-r * 0.85, -r * 0.55, r * 1.7, r * 1.1));
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r * 0.85, -r * 0.55);
                        p.lineTo(0, r * 0.1);
                        p.lineTo(r * 0.85, -r * 0.55);
                        h.draw(p);
                    }
                    case SHIELD -> { // police, fire station
                        Path2D p = new Path2D.Double();
                        p.moveTo(-r * 0.75, -r * 0.8);
                        p.lineTo(r * 0.75, -r * 0.8);
                        p.lineTo(r * 0.75, 0);
                        p.quadTo(r * 0.6, r * 0.75, 0, r);
                        p.quadTo(-r * 0.6, r * 0.75, -r * 0.75, 0);
                        p.closePath();
                        h.fill(p);
                    }
                }
            } finally {
                h.dispose();
            }
        }

        /** A street's name along its longest straight piece, upright. */
        void lineText(Feature f, String text, Font font) {
            List<double[]> pts = points(f);
            double best = 0;
            int at = -1;
            for (int i = 0; i + 1 < pts.size(); i++) {
                double len = Math.hypot(pts.get(i + 1)[0] - pts.get(i)[0], pts.get(i + 1)[1] - pts.get(i)[1]);
                if (len > best) {
                    best = len;
                    at = i;
                }
            }
            if (at < 0) return;
            TextLayout layout = new TextLayout(text, font, g.getFontRenderContext());
            Rectangle2D b = layout.getBounds();
            if (b.getWidth() + 10 > best) return;
            double[] a = pts.get(at), c = pts.get(at + 1);
            double angle = Math.atan2(c[1] - a[1], c[0] - a[0]);
            if (angle > Math.PI / 2) angle -= Math.PI;
            if (angle < -Math.PI / 2) angle += Math.PI;
            double mx = (a[0] + c[0]) / 2, my = (a[1] + c[1]) / 2;
            AffineTransform t = new AffineTransform();
            t.translate(mx, my);
            t.rotate(angle);
            t.translate(-b.getWidth() / 2, b.getHeight() / 2 - 1);
            Shape outline = layout.getOutline(t);
            Rectangle2D box = outline.getBounds2D();
            box = new Rectangle2D.Double(box.getX() - 3, box.getY() - 3, box.getWidth() + 6, box.getHeight() + 6);
            if (!free(box)) return;
            halo(layout, t, c(TEXT));
            placed.add(box);
        }

        boolean free(Rectangle2D box) {
            if (box.getMinX() < 2 || box.getMinY() < 2 || box.getMaxX() > PX - 2 || box.getMaxY() > PX - 2) return false;
            for (Rectangle2D r : placed) if (r.intersects(box)) return false;
            return true;
        }

        void halo(TextLayout layout, AffineTransform t, Color colour) {
            Shape s = layout.getOutline(t);
            g.setColor(dark ? HALO_DARK : HALO);
            g.setStroke(new BasicStroke(5f * TEXT_SCALE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            g.setColor(colour);
            g.fill(s);
        }

        /**
         * The name to show: the local one when it is written in Latin letters (Norwegian, French...), else the English
         * or transliterated one (a Devanagari or Chinese name would come out as boxes in some fonts).
         */
        String name(Feature f) {
            String n = f.str("name");
            if (n != null && latin(n)) return n;
            String alt = f.str("name_en");
            if (alt == null || alt.isBlank()) alt = f.str("name:latin");
            if (alt == null || alt.isBlank()) alt = f.str("name_int");
            return alt != null && !alt.isBlank() ? alt : n;
        }

        static boolean latin(String s) {
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x024F && !Character.isWhitespace(s.charAt(i))) return false;
            return true;
        }

        // ------------------------------------------------------------------ geometry

        double ext(Feature f) {
            Integer e = extents.get(f.layer());
            return e == null ? extentDefault : e;
        }

        Path2D path(Feature f, boolean polygon) {
            Path2D.Double p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            double k = scale / ext(f);
            long[] geo = f.geometry();
            int cx = 0, cy = 0, i = 0;
            while (i < geo.length) {
                int cmd = (int) geo[i] & 7, count = (int) (geo[i] >>> 3);
                i++;
                if (cmd == 7) {
                    if (polygon) p.closePath();
                    continue;
                }
                for (int c = 0; c < count && i + 1 < geo.length; c++) {
                    cx += zigzag(geo[i++]);
                    cy += zigzag(geo[i++]);
                    double px = cx * k + ox, py = cy * k + oy;
                    if (cmd == 1) p.moveTo(px, py);
                    else p.lineTo(px, py);
                }
            }
            return p;
        }

        List<double[]> points(Feature f) {
            List<double[]> out = new ArrayList<>();
            double k = scale / ext(f);
            long[] geo = f.geometry();
            int cx = 0, cy = 0, i = 0;
            while (i < geo.length) {
                int cmd = (int) geo[i] & 7, count = (int) (geo[i] >>> 3);
                i++;
                if (cmd == 7) continue;
                for (int c = 0; c < count && i + 1 < geo.length; c++) {
                    cx += zigzag(geo[i++]);
                    cy += zigzag(geo[i++]);
                    if (cmd == 1 && !out.isEmpty()) return out; // the first part of a multi-line is enough for a label
                    out.add(new double[]{cx * k + ox, cy * k + oy});
                }
            }
            return out;
        }

        double[] firstPoint(Feature f) {
            List<double[]> p = points(f);
            return p.isEmpty() ? null : p.get(0);
        }
    }

    // ------------------------------------------------------------------ the vector tile format

    private static Map<String, List<Feature>> decode(byte[] mvt) {
        Map<String, List<Feature>> out = new HashMap<>();
        Reader tile = new Reader(mvt, 0, mvt.length);
        while (tile.more()) {
            int tag = tile.tag();
            if (tag >>> 3 == 3 && (tag & 7) == 2) readLayer(tile.message(), out);
            else tile.skip(tag & 7);
        }
        return out;
    }

    private static void readLayer(Reader layer, Map<String, List<Feature>> out) {
        String name = null;
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Reader> features = new ArrayList<>();
        int extent = 4096;
        while (layer.more()) {
            int tag = layer.tag(), field = tag >>> 3, wire = tag & 7;
            if (field == 1 && wire == 2) name = layer.string();
            else if (field == 2 && wire == 2) features.add(layer.message());
            else if (field == 3 && wire == 2) keys.add(layer.string());
            else if (field == 4 && wire == 2) values.add(value(layer.message()));
            else if (field == 5 && wire == 0) extent = (int) layer.varint();
            else layer.skip(wire);
        }
        if (name == null) return;
        List<Feature> list = out.computeIfAbsent(name, k -> new ArrayList<>());
        double rescale = 4096.0 / extent; // geometry kept in 4096 units for one transform per tile
        for (Reader f : features) {
            long[] tags = null, geometry = null;
            int type = 0;
            while (f.more()) {
                int tag = f.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 2 && wire == 2) tags = f.packed();
                else if (field == 3 && wire == 0) type = (int) f.varint();
                else if (field == 4 && wire == 2) geometry = f.packed();
                else f.skip(wire);
            }
            if (geometry == null) continue;
            if (extent != 4096) geometry = rescale(geometry, rescale);
            Map<String, Object> t = new HashMap<>();
            if (tags != null) {
                for (int i = 0; i + 1 < tags.length; i += 2) {
                    int k = (int) tags[i], v = (int) tags[i + 1];
                    if (k >= 0 && k < keys.size() && v >= 0 && v < values.size()) t.put(keys.get(k), values.get(v));
                }
            }
            list.add(new Feature(name, type, geometry, t, bounds(geometry)));
        }
    }

    /** {minX, minY, maxX, maxY} of a geometry in tile units. */
    private static int[] bounds(long[] geo) {
        int cx = 0, cy = 0, i = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        while (i < geo.length) {
            int cmd = (int) geo[i] & 7, count = (int) (geo[i] >>> 3);
            i++;
            if (cmd == 7) continue;
            for (int c = 0; c < count && i + 1 < geo.length; c++) {
                cx += zigzag(geo[i++]);
                cy += zigzag(geo[i++]);
                minX = Math.min(minX, cx);
                minY = Math.min(minY, cy);
                maxX = Math.max(maxX, cx);
                maxY = Math.max(maxY, cy);
            }
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /** Geometry commands with their coordinates scaled (an extent other than 4096). */
    private static long[] rescale(long[] geo, double k) {
        long[] out = geo.clone();
        int i = 0;
        while (i < out.length) {
            int cmd = (int) out[i] & 7, count = (int) (out[i] >>> 3);
            i++;
            if (cmd == 7) continue;
            for (int c = 0; c < count * 2 && i < out.length; c++, i++) {
                long v = Math.round(zigzag(out[i]) * k);
                out[i] = (v << 1) ^ (v >> 63);
            }
        }
        return out;
    }

    private static Object value(Reader v) {
        Object out = null;
        while (v.more()) {
            int tag = v.tag(), field = tag >>> 3, wire = tag & 7;
            switch (field) {
                case 1 -> out = v.string();
                case 2 -> out = Float.intBitsToFloat(v.fixed32());
                case 3 -> out = Double.longBitsToDouble(v.fixed64());
                case 4, 5 -> out = v.varint();
                case 6 -> out = (long) zigzag(v.varint());
                case 7 -> out = v.varint() != 0;
                default -> v.skip(wire);
            }
        }
        return out;
    }

    private static int zigzag(long v) {
        return (int) ((v >>> 1) ^ -(v & 1));
    }

    /** Just enough of the protobuf wire format for a vector tile. */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() {
            return (int) varint();
        }

        long varint() {
            long r = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte c = b[pos++];
                r |= (long) (c & 0x7F) << shift;
                if (c >= 0) return r;
            }
            throw new IllegalStateException("varint too long");
        }

        int fixed32() {
            int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
            pos += 4;
            return v;
        }

        long fixed64() {
            long lo = fixed32() & 0xFFFFFFFFL, hi = fixed32() & 0xFFFFFFFFL;
            return lo | hi << 32;
        }

        int length() {
            int n = (int) varint();
            if (n < 0 || pos + n > end) throw new IllegalStateException("bad length " + n);
            return n;
        }

        Reader message() {
            int n = length();
            Reader m = new Reader(b, pos, pos + n);
            pos += n;
            return m;
        }

        String string() {
            int n = length();
            String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        long[] packed() {
            int n = length(), stop = pos + n, count = 0;
            long[] out = new long[Math.max(4, n)];
            while (pos < stop) out[count++] = varint();
            return Arrays.copyOf(out, count);
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> varint();
                case 1 -> pos += 8;
                case 2 -> {
                    int n = length();
                    pos += n;
                }
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("unsupported wire type " + wire);
            }
        }
    }
}
