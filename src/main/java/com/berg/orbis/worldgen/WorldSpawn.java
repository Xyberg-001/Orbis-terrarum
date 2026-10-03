package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The spawn point chosen on the World tab ("Spawn point"), applied once, the first time a world starts: the
 * chunk at that real-world place is generated, and players appear on its surface (never inside a building or
 * under the ground), on exactly that block unless "Exact spawn" is off. A marker file in the world folder records
 * that it was done, so a later /setworldspawn is never undone. Without a custom spawn the world keeps Minecraft's
 * own spawn near block 0, 0, the chosen location.
 */
public final class WorldSpawn {
    public static final String MARKER = "orbis-spawn.json";
    /** Minecraft's world border sits at 29,999,984 blocks from 0. */
    private static final int WORLD_EDGE = 29_999_000;

    private WorldSpawn() {}

    public static void applyOnce(MinecraftServer server) {
        WorldModel model = OrbisMod.model();
        if (model == null) return;
        OrbisConfig cfg = model.cfg();
        if (!cfg.customSpawn) return;
        Path marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
        if (Files.exists(marker)) return;
        int[] b = model.mapper().toBlock(cfg.spawnLat, cfg.spawnLon);
        String where = String.format(Locale.ROOT, "%.5f, %.5f", cfg.spawnLat, cfg.spawnLon);
        if (Math.abs(b[0]) > WORLD_EDGE || Math.abs(b[1]) > WORLD_EDGE) {
            System.err.println("[orbis] Spawn point " + where + " is block " + b[0] + ", " + b[1] + ", past the world edge; keeping Minecraft's spawn");
            writeMarker(marker, "{\"skipped\":\"past the world edge\"}");
            return;
        }
        ServerLevel level = server.overworld();
        long t = System.currentTimeMillis();
        level.getChunk(b[0] >> 4, b[1] >> 4); // generated in full, so the surface includes buildings and trees
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b[0], b[1]);
        BlockPos pos = new BlockPos(b[0], y, b[1]);
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, pos, 0f, 0f));
        if (cfg.exactSpawn) level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
        System.out.println("[orbis] Spawn point set to " + where + " = block " + pos.toShortString()
                + (cfg.exactSpawn ? " (exact: respawn_radius 0)" : "") + " in " + (System.currentTimeMillis() - t) + " ms");
        writeMarker(marker, String.format(Locale.ROOT, "{\"lat\":%.7f,\"lon\":%.7f,\"x\":%d,\"y\":%d,\"z\":%d,\"exact\":%b}",
                cfg.spawnLat, cfg.spawnLon, pos.getX(), pos.getY(), pos.getZ(), cfg.exactSpawn));
    }

    private static void writeMarker(Path marker, String json) {
        try {
            Files.writeString(marker, json + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not write " + marker.getFileName() + ": " + e);
        }
    }
}
