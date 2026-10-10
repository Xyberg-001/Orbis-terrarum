package com.berg.orbis.sky;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.berg.orbis.worldgen.WorldModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real sky settings of a world (daylight, weather, seasons, snow, villagers' clock hours) switched on or off while it runs, by the
 * /orbis daylight|weather|seasons|snow|clockhours commands. A world's own choices were made when it was created and are kept in its
 * generator; a change made here is kept beside them, in orbis-sky.json in the world's folder, and put over them whenever the world's model
 * is made (so it lasts across restarts). The installation's off switches (Mod Menu, or a server's config file) still win.
 */
public final class SkySwitches {
    private static final String FILE = "orbis-sky.json";

    /** A switch: its command word, its setting (an OrbisConfig field) and what it does. */
    public enum Switch {
        DAYLIGHT("daylight", "realDaylight", "Real daylight", "the sun rises and sets when it really does at the world's place, the moon shows its real phase"),
        WEATHER("weather", "realWeather", "Real weather", "clear, rain or thunder as MET Norway forecasts it where the players are"),
        SEASONS("seasons", "realSeasons", "Real seasons", "grass and leaves in the real season's colours, snow in cold places in winter"),
        SNOW("snow", "realSnow", "Real snow depth", "today's snow on new ground, settling and melting as the real snow does"),
        CLOCKHOURS("clockhours", "villagerClockHours", "Villagers' clock hours", "Villagers keep the town's clock hours (work 08-16, bed at 22) under real daylight");

        public final String word;
        final String field;
        final String label;
        final String description;

        Switch(String word, String field, String label, String description) {
            this.word = word;
            this.field = field;
            this.label = label;
            this.description = description;
        }

        boolean read(OrbisConfig cfg) {
            try {
                return OrbisConfig.class.getField(this.field).getBoolean(cfg);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void write(OrbisConfig cfg, boolean on) {
            try {
                OrbisConfig.class.getField(this.field).setBoolean(cfg, on);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static volatile Path worldDir;
    private static final Map<Switch, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    private SkySwitches() {}

    public static void register() {
        // before the sky and seasons look at the world's settings (RealSky, at SERVER_STARTED)
        ServerLifecycleEvents.SERVER_STARTING.register(SkySwitches::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OVERRIDES.clear();
            worldDir = null;
        });
    }

    private static void load(MinecraftServer server) {
        OVERRIDES.clear();
        worldDir = server.getWorldPath(LevelResource.ROOT);
        Path file = worldDir.resolve(FILE);
        if (Files.exists(file)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (Switch s : Switch.values()) {
                    if (o.has(s.word)) OVERRIDES.put(s, o.get(s.word).getAsBoolean());
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[orbis] Could not read " + file + ": " + e);
            }
        }
        WorldModel model = OrbisMod.model();
        if (model != null) applyTo(model.cfg());
        if (!OVERRIDES.isEmpty()) System.out.println("[orbis] Real sky switched in this world by command: " + OVERRIDES);
    }

    /** Puts this world's switched settings over a model's (as the model for the running world is made). */
    public static void applyTo(OrbisConfig cfg) {
        for (Map.Entry<Switch, Boolean> e : OVERRIDES.entrySet()) e.getKey().write(cfg, e.getValue());
    }

    /** Switches one on or off in the running world, at once, and keeps it with the world. */
    public static Component set(MinecraftServer server, Switch s, boolean on) {
        WorldModel model = OrbisMod.model();
        if (model == null || !(server.overworld().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator) || worldDir == null) {
            return Component.literal("This is not an Orbis Terrarum world.");
        }
        OVERRIDES.put(s, on);
        s.write(model.cfg(), on);
        save();
        StringBuilder msg = new StringBuilder(s.label).append(" is ").append(on ? "on" : "off").append(" in this world.");
        switch (s) {
            case DAYLIGHT -> msg.append(on ? " The sun moves to the real time within 10 seconds."
                    : " Minecraft's own day and night carry on from here within 10 seconds.");
            case WEATHER -> msg.append(on ? " The real weather comes within a couple of minutes." : " The weather stays as it is until it changes by itself.");
            case SEASONS -> {
                Seasons.rememberWorldChoice(worldDir, on);
                msg.append(" Grass and leaf colours change the next time the world is opened.");
            }
            case SNOW -> {
                if (on) model.setSnowCover(SnowCover.shared(OrbisMod.dataDir().resolve("snow-cache")));
                msg.append(on ? " New ground gets today's snow; snow already there settles and melts as the real snow does."
                        : " Snow already lying stays; no real snow is added.");
            }
            case CLOCKHOURS -> msg.append(on ? " (with real daylight on)" : " Villagers follow the sun instead.");
        }
        if (on && !s.read(OrbisMod.config())) {
            msg.append(" But this installation has it switched off for every world (").append(s.field)
                    .append(" in the Orbis Terrarum config file, or Mod Menu), which still wins.");
        }
        return Component.literal(msg.toString());
    }

    /** Each switch: on or off in this world, and whether the installation's off switch overrides it. */
    public static Component status() {
        WorldModel model = OrbisMod.model();
        if (model == null) return Component.literal("This is not an Orbis Terrarum world.");
        StringBuilder sb = new StringBuilder("Real sky in this world (/orbis <name> on|off):");
        for (Switch s : Switch.values()) {
            boolean world = s.read(model.cfg()), installation = s.read(OrbisMod.config());
            sb.append("\n ").append(s.word).append(": ").append(world ? "on" : "off");
            if (world && !installation) sb.append(" (but off for every world in this installation's config)");
            if (OVERRIDES.containsKey(s)) sb.append(" (switched by command)");
            sb.append(" - ").append(s.description);
        }
        return Component.literal(sb.toString());
    }

    /** For the world map's World window: each switch as "sky", word, on, off for the whole installation, label, description (tab-separated). */
    public static java.util.List<String> lines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        WorldModel model = OrbisMod.model();
        if (model == null) return out;
        for (Switch s : Switch.values()) {
            boolean world = s.read(model.cfg()), installation = s.read(OrbisMod.config());
            out.add(String.join("\t", "sky", s.word, world ? "1" : "0", installation ? "0" : "1", s.label, s.description));
        }
        return out;
    }

    /** The switch with this command word, or null. */
    public static Switch byWord(String word) {
        for (Switch s : Switch.values()) if (s.word.equals(word)) return s;
        return null;
    }

    private static void save() {
        Path dir = worldDir;
        if (dir == null) return;
        JsonObject o = new JsonObject();
        for (Map.Entry<Switch, Boolean> e : OVERRIDES.entrySet()) o.addProperty(e.getKey().word, e.getValue());
        try {
            Files.writeString(dir.resolve(FILE), o.toString());
        } catch (IOException e) {
            System.err.println("[orbis] Could not save " + dir.resolve(FILE) + ": " + e);
        }
    }
}
