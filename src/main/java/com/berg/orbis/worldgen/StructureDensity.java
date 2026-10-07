package com.berg.orbis.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Fewer mineshafts, trial chambers and Ancient Cities in an Orbis world: a data pack in the world
 * ({@link #PACK_NAME}) gives vanilla's three structure sets a lower placement frequency, so vanilla itself places
 * fewer of them and its /locate, explorer maps and eyes of ender agree with what is generated. Vanilla's densities
 * are per block, so at 1:2 every real square kilometre had four times vanilla's (Bergen 1:2: 2,155 mineshafts, 424
 * trial chambers, 136 Ancient Cities in 566 km², 4 Oct 2026). Written when a world is created
 * (OrbisConfig.undergroundStructureShare, kept in the pack's pack.mcmeta) and rewritten from vanilla's current
 * definitions when the world is opened by another Minecraft version; worlds without the pack keep vanilla's.
 */
public final class StructureDensity {

    public static final String PACK_NAME = "orbis_structures";
    private static final List<String> SETS = List.of("mineshafts", "trial_chambers", "ancient_cities");

    private StructureDensity() {}

    /** Writes the pack for a share of vanilla's structures (1: no pack is needed, nothing is written). */
    public static void writePack(Path datapacksDir, double share) throws IOException {
        if (share >= 1) return;
        Path dir = datapacksDir.resolve(PACK_NAME);
        Path setDir = dir.resolve("data").resolve("minecraft").resolve("worldgen").resolve("structure_set");
        Files.createDirectories(setDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        int written = 0;
        for (String name : SETS) {
            JsonObject set = vanilla(name);
            if (set == null || !set.has("placement")) continue;
            JsonObject placement = set.getAsJsonObject("placement");
            double frequency = placement.has("frequency") ? placement.get("frequency").getAsDouble() : 1.0;
            placement.addProperty("frequency", (float) (frequency * share));
            Files.writeString(setDir.resolve(name + ".json"), gson.toJson(set), StandardCharsets.UTF_8);
            written++;
        }
        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", String.format(Locale.ROOT,
                "Orbis Terrarum: mineshafts, trial chambers and Ancient Cities at %.0f%% of vanilla", share * 100));
        WorldHeight.putPackFormat(pack);
        meta.add("pack", pack);
        WorldHeight.stamp(meta);
        meta.getAsJsonObject("orbisterrarum").addProperty("structure_share", share);
        Files.writeString(dir.resolve("pack.mcmeta"), gson.toJson(meta), StandardCharsets.UTF_8);
        System.out.println(String.format(Locale.ROOT, "[orbis] Structure pack: %d structure sets at %.0f%% of vanilla", written, share * 100));
    }

    /** A world opened by another Minecraft version (or mod build) gets the pack rewritten from that version's sets. */
    public static void refresh(Path datapacksDir) {
        Path dir = datapacksDir.resolve(PACK_NAME);
        if (!Files.exists(dir.resolve("pack.mcmeta")) || WorldHeight.isCurrent(dir)) return;
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
            double share = meta.getAsJsonObject("orbisterrarum").get("structure_share").getAsDouble();
            writePack(datapacksDir, share);
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not refresh the structure pack: " + e);
        }
    }

    private static JsonObject vanilla(String name) {
        String path = "/data/minecraft/worldgen/structure_set/" + name + ".json";
        try (InputStream in = net.minecraft.SharedConstants.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
