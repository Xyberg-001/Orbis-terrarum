package com.berg.orbis.worldgen;

import com.berg.orbis.mc.Mc;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.osm.LatLon;
import com.berg.orbis.osm.OsmArea;
import com.berg.orbis.osm.OsmData;
import com.berg.orbis.osm.OsmNode;
import com.berg.orbis.osm.OsmWay;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A world datapack with one advancement per real landmark near the origin: peaks, viewpoints, castles,
 * churches, museums, attractions, funicular stations. Each one is a vanilla location trigger over a small
 * area around the place, so "Climb Fløyen" pops when a player actually stands there, and a grand tour
 * advancement wants all of them. Written once into the world's datapacks folder on first start and
 * enabled with a resource reload; nothing is needed on clients.
 */
public final class Landmarks {

    public static final String PACK_NAME = "orbis_landmarks";
    private static final int MAX_LANDMARKS = 36;
    private static final int MAX_PER_KIND = 12;

    public record Landmark(String name, double lat, double lon, String kind, int score, String eleTag) {
    }

    private Landmarks() {
    }

    /** Called on server start: generates and enables the pack if the world does not have it yet. */
    public static void ensure(MinecraftServer server) {
        if (!(server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator gen)) return;
        WorldModel model = gen.model();
        if (model == null || model.regions() == null || !(model.cfg().landmarkAdvancements || model.cfg().tunnelLoot)) return;
        Path dir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME);
        // Written by another Minecraft version (an upgraded world): its advancement and loot formats may be outdated.
        if (Files.exists(dir.resolve("pack.mcmeta")) && Files.exists(lootTablePath(dir)) && WorldHeight.isCurrent(dir)) return;
        Thread t = new Thread(() -> {
            try {
                int n = write(model, dir);
                System.out.println("[orbis] Landmark datapack written with " + n + " advancements; it will be enabled once the server is idle");
                // Never reload from here: a data pack reload while the server is still preparing the spawn
                // area deadlocks the server thread (the loading screen never finishes). The tick hook does it.
                reloadWanted = server;
            } catch (Exception e) {
                System.err.println("[orbis] Could not build the landmark datapack: " + e);
            }
        }, "Orbis-landmarks");
        t.setDaemon(true);
        t.start();
    }

    private static volatile MinecraftServer reloadWanted;

    /** From the server tick: enables a freshly written pack once the server has been running normally for a while. */
    public static void tick(MinecraftServer server) {
        if (reloadWanted != server || server.getTickCount() < 200 || !server.isReady()) return;
        reloadWanted = null;
        System.out.println("[orbis] Enabling the landmark datapack (data pack reload)");
        enable(server);
    }

    /** Regenerates the pack (for the /orbis landmarks command); returns the number of advancements. */
    public static int regenerate(MinecraftServer server) throws Exception {
        if (!(server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator gen)) return -1;
        WorldModel model = gen.model();
        if (model == null || model.regions() == null) return -1;
        Path dir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME);
        int n = write(model, dir);
        server.execute(() -> enable(server)); // a command runs on a running server, where a reload is the same as /reload
        return n;
    }

    private static void enable(MinecraftServer server) {
        PackRepository repo = server.getPackRepository();
        repo.reload();
        List<String> ids = new ArrayList<>(repo.getSelectedIds());
        String id = "file/" + PACK_NAME;
        if (repo.isAvailable(id) && !ids.contains(id)) ids.add(id);
        server.reloadResources(ids).exceptionally(e -> {
            System.err.println("[orbis] Data pack reload failed: " + e);
            return null;
        });
    }

    // ------------------------------------------------------------------ building the pack

    private static int write(WorldModel model, Path dir) throws IOException, InterruptedException {
        OrbisConfig cfg = model.cfg();
        if (!cfg.landmarkAdvancements) return writePack(List.of(), cfg, model.mapper(), dir); // loot tables only
        double[] bbox = searchBox(cfg);
        OsmData data = model.regions().provider().getData(bbox[0], bbox[1], bbox[2], bbox[3]);
        return writePack(select(data, cfg), cfg, model.mapper(), dir);
    }

    private static Path lootTablePath(Path dir) {
        return dir.resolve("data").resolve("orbisterrarum").resolve("loot_table").resolve("chests").resolve("tunnel_depot.json");
    }

    /**
     * The haul in a tunnel niche ({@code orbisterrarum:chests/tunnel_depot}), sized for a fight through a dark
     * tunnel: 12-24 iron, 2-5 diamonds in one chest out of four, three to five lots of emeralds / gold / copper / lapis / redstone /
     * experience / golden apples / torches / arrows / food, and one enchanted iron (rarely diamond) tool
     * or armour piece.
     */
    /** The tunnel depot chest loot; its JSON format differs between Minecraft versions (versions/<mc>/resources). */
    private static final String TUNNEL_DEPOT_LOOT_TEMPLATE = "/assets/orbisterrarum/templates/tunnel_depot_loot.json";

    /** South, west, north, east of the area searched for landmarks: 4 km at 1:1, up to 60 km on coarse maps. */
    public static double[] searchBox(OrbisConfig cfg) {
        double radiusM = Math.min(60_000, Math.max(4_000, cfg.metersPerBlock * 3_000));
        double dLat = radiusM / 111_320.0;
        double dLon = radiusM / (111_320.0 * Math.cos(Math.toRadians(cfg.originLat)));
        return new double[]{cfg.originLat - dLat, cfg.originLon - dLon, cfg.originLat + dLat, cfg.originLon + dLon};
    }

    /** The landmarks worth an advancement, best first, one per name. */
    public static List<Landmark> select(OsmData data, OrbisConfig cfg) {
        List<Landmark> found = new ArrayList<>();
        for (OsmNode n : data.nodes()) consider(found, n.tags(), n.pos().lat(), n.pos().lon());
        for (OsmWay w : data.ways()) {
            if (w.points().isEmpty()) continue;
            double lat = 0, lon = 0;
            for (LatLon p : w.points()) {
                lat += p.lat();
                lon += p.lon();
            }
            consider(found, w.tags(), lat / w.points().size(), lon / w.points().size());
        }
        for (OsmArea a : data.areas()) {
            LatLon c = a.centroid();
            consider(found, a.tags(), c.lat(), c.lon());
        }
        // Best first, nearer first among equals; one entry per name.
        found.sort((a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            return Double.compare(dist2(cfg, a), dist2(cfg, b));
        });
        List<Landmark> chosen = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> perKind = new java.util.HashMap<>();
        for (Landmark l : found) {
            if (!names.add(l.name.toLowerCase(Locale.ROOT))) continue;
            int k = perKind.getOrDefault(l.kind, 0);
            if (k >= MAX_PER_KIND) continue; // a city of museums still gets its peaks and castles in
            perKind.put(l.kind, k + 1);
            chosen.add(l);
            if (chosen.size() >= MAX_LANDMARKS) break;
        }
        return chosen;
    }

    /** Writes the datapack for these landmarks; returns the number of advancements. */
    public static int writePack(List<Landmark> chosen, OrbisConfig cfg, CoordinateMapper mapper, Path dir) throws IOException {
        Path adv = dir.resolve("data").resolve("orbisterrarum").resolve("advancement");
        Files.createDirectories(adv.resolve("landmark"));
        var gson = new GsonBuilder().setPrettyPrinting().create();

        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Orbis Terrarum: real landmarks around " + String.format(Locale.ROOT, "%.4f, %.4f", cfg.originLat, cfg.originLon));
        WorldHeight.putPackFormat(pack);
        meta.add("pack", pack);
        WorldHeight.stamp(meta);
        Files.writeString(dir.resolve("pack.mcmeta"), gson.toJson(meta), StandardCharsets.UTF_8);

        Files.createDirectories(lootTablePath(dir).getParent());
        try (var in = Landmarks.class.getResourceAsStream(TUNNEL_DEPOT_LOOT_TEMPLATE)) {
            if (in == null) throw new IOException("loot template missing from the mod jar: " + TUNNEL_DEPOT_LOOT_TEMPLATE);
            Files.write(lootTablePath(dir), in.readAllBytes());
        }
        if (!cfg.landmarkAdvancements) return 0;

        // Root: granted on the first tick, so the tab appears at once.
        JsonObject root = new JsonObject();
        root.add("display", display("minecraft:filled_map", "Orbis Terrarum", "Real places within walking distance. Find them all.", "task", false));
        root.getAsJsonObject("display").addProperty("background", "minecraft:gui/advancements/backgrounds/stone");
        root.getAsJsonObject("display").addProperty("show_toast", false);
        root.getAsJsonObject("display").addProperty("announce_to_chat", false);
        JsonObject rootCrit = new JsonObject();
        JsonObject tick = new JsonObject();
        tick.addProperty("trigger", "minecraft:tick");
        rootCrit.add("here", tick);
        root.add("criteria", rootCrit);
        Files.writeString(adv.resolve("root.json"), gson.toJson(root), StandardCharsets.UTF_8);

        JsonObject tour = new JsonObject();
        tour.addProperty("parent", "orbisterrarum:root");
        tour.add("display", display("minecraft:spyglass", "Grand Tour", "Stand at every landmark on the map.", "challenge", true));
        JsonObject tourCrit = new JsonObject();
        JsonArray tourReq = new JsonArray();

        Set<String> slugs = new HashSet<>();
        int count = 0;
        for (Landmark l : chosen) {
            String slug = slug(l.name);
            if (slug.isEmpty() || !slugs.add(slug)) continue;
            int[] b = mapper.toBlock(l.lat, l.lon);
            int radius = (int) Math.max(4, Math.round((l.kind.equals("peak") ? 60 : 30) / cfg.metersPerBlock));
            JsonObject a = new JsonObject();
            a.addProperty("parent", "orbisterrarum:root");
            a.add("display", display(icon(l.kind), title(l), description(l), l.kind.equals("peak") ? "goal" : "task", true));
            JsonObject crit = new JsonObject();
            crit.add("here", locationCriterion(b[0] - radius, b[0] + radius, b[1] - radius, b[1] + radius));
            a.add("criteria", crit);
            Files.writeString(adv.resolve("landmark").resolve(slug + ".json"), gson.toJson(a), StandardCharsets.UTF_8);
            tourCrit.add(slug, locationCriterion(b[0] - radius, b[0] + radius, b[1] - radius, b[1] + radius));
            JsonArray one = new JsonArray();
            one.add(slug);
            tourReq.add(one);
            count++;
        }
        if (count > 0) {
            tour.add("criteria", tourCrit);
            tour.add("requirements", tourReq);
            Files.writeString(adv.resolve("grand_tour.json"), gson.toJson(tour), StandardCharsets.UTF_8);
        }
        return count;
    }

    private static double dist2(OrbisConfig cfg, Landmark l) {
        double dx = (l.lon - cfg.originLon) * Math.cos(Math.toRadians(cfg.originLat)), dz = l.lat - cfg.originLat;
        return dx * dx + dz * dz;
    }

    private static void consider(List<Landmark> out, Map<String, String> t, double lat, double lon) {
        if (t == null) return;
        String name = t.get("name");
        if (name == null || name.isBlank()) return;
        int semi = name.indexOf(';');
        if (semi > 0) name = name.substring(0, semi); // "Fløibanen, nedre stasjon;Fløibanen"
        name = name.trim();
        if (name.length() > 60) name = name.substring(0, 60).trim();
        String kind = null;
        int score = 0;
        String tourism = t.get("tourism"), historic = t.get("historic"), natural = t.get("natural"), building = t.get("building");
        String amenity = t.get("amenity"), manMade = t.get("man_made"), aerialway = t.get("aerialway"), leisure = t.get("leisure");
        if ("peak".equals(natural)) {
            kind = "peak";
            double ele = parse(t.get("ele"));
            score = 3 + (ele >= 300 ? 2 : 0) + (ele >= 800 ? 1 : 0);
        } else if (tourism != null) {
            switch (tourism) {
                case "attraction" -> { kind = "attraction"; score = 4; }
                case "viewpoint" -> { kind = "viewpoint"; score = 3; }
                case "museum", "gallery" -> { kind = "museum"; score = 3; }
                case "zoo", "aquarium", "theme_park" -> { kind = "attraction"; score = 3; }
                case "artwork" -> { kind = "artwork"; score = 1; }
                default -> { }
            }
        }
        if (kind == null && historic != null) {
            switch (historic) {
                case "castle", "fort", "fortress", "citadel" -> { kind = "castle"; score = 4; }
                case "monument", "memorial", "ruins", "city_gate", "tower", "ship", "archaeological_site", "manor", "palace" -> { kind = "historic"; score = 2; }
                default -> { }
            }
        }
        if (kind == null && ("station".equals(aerialway))) { kind = "aerialway"; score = 3; }
        if (kind == null && ("lighthouse".equals(manMade))) { kind = "lighthouse"; score = 3; }
        if (kind == null && ("stadium".equals(leisure) || "stadium".equals(building))) { kind = "stadium"; score = 2; }
        if (kind == null && ("place_of_worship".equals(amenity) || "cathedral".equals(building) || "church".equals(building))) {
            kind = "church";
            score = "cathedral".equals(building) ? 3 : 1;
        }
        if (kind == null && ("castle".equals(building) || "fort".equals(building))) { kind = "castle"; score = 3; }
        if (kind == null) return;
        if (t.containsKey("wikipedia") || t.containsKey("wikidata")) score += 3;
        if (t.containsKey("heritage")) score += 1;
        out.add(new Landmark(name.trim(), lat, lon, kind, score, t.get("ele")));
    }

    private static double parse(String s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.replace(",", ".").replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String icon(String kind) {
        return switch (kind) {
            case "peak" -> "minecraft:spyglass";
            case "viewpoint" -> "minecraft:spyglass";
            case "museum" -> "minecraft:book";
            case "castle" -> "minecraft:shield";
            case "historic" -> "minecraft:brush";
            case "church" -> "minecraft:bell";
            case "lighthouse" -> "minecraft:lantern";
            case "aerialway" -> "minecraft:minecart";
            case "stadium" -> "minecraft:leather_boots";
            case "artwork" -> "minecraft:painting";
            default -> "minecraft:map";
        };
    }

    private static String title(Landmark l) {
        return switch (l.kind) {
            case "peak" -> "Climb " + l.name;
            case "aerialway" -> "Ride to " + l.name;
            case "viewpoint" -> "The view from " + l.name;
            default -> "Visit " + l.name;
        };
    }

    private static String description(Landmark l) {
        double ele = parse(l.eleTag);
        return switch (l.kind) {
            case "peak" -> Double.isNaN(ele) ? "Stand on the summit of " + l.name + "." : String.format(Locale.ROOT, "Stand on the summit of %s, %.0f m above the sea.", l.name, ele);
            case "viewpoint" -> "Find the viewpoint at " + l.name + ".";
            case "museum" -> "Walk into " + l.name + ".";
            case "castle" -> "Reach the walls of " + l.name + ".";
            case "church" -> "Stand before " + l.name + ".";
            case "lighthouse" -> "Reach the lighthouse " + l.name + ".";
            case "aerialway" -> "Reach the station " + l.name + ".";
            case "stadium" -> "Step onto the ground of " + l.name + ".";
            default -> "Stand at " + l.name + ".";
        };
    }

    private static JsonObject display(String icon, String title, String desc, String frame, boolean toast) {
        JsonObject d = new JsonObject();
        JsonObject i = new JsonObject();
        i.addProperty("id", icon);
        d.add("icon", i);
        d.addProperty("title", title);
        d.addProperty("description", desc);
        d.addProperty("frame", frame);
        d.addProperty("show_toast", toast);
        d.addProperty("announce_to_chat", toast);
        return d;
    }

    private static JsonObject locationCriterion(int x0, int x1, int z0, int z1) {
        JsonObject c = new JsonObject();
        c.addProperty("trigger", "minecraft:location");
        JsonObject conditions = new JsonObject();
        JsonObject predicate = new JsonObject();
        JsonObject location = new JsonObject();
        JsonObject position = new JsonObject();
        position.add("x", range(x0, x1 + 1));
        position.add("z", range(z0, z1 + 1));
        location.add("position", position);
        predicate.add("minecraft:location", location);
        conditions.add("player", Mc.playerCondition(predicate));
        c.add("conditions", conditions);
        return c;
    }

    private static JsonObject range(double min, double max) {
        JsonObject r = new JsonObject();
        r.addProperty("min", min);
        r.addProperty("max", max);
        return r;
    }

    static String slug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('æ', 'a').replace('ø', 'o').replace('å', 'a').replace('ß', 's')
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.length() > 48 ? s.substring(0, 48) : s;
    }
}
