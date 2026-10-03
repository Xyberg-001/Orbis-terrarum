package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.osm.CoordinateMapper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard limit: only the world's allowed area generates. The allowed area is the selection chosen when the world was
 * created, plus every area pre-generated since (map selections, /orbis pregen areas and radii), kept in
 * {@code <world>/orbis-pregen/allowed-extra.json}. With the limit on, a chunk outside it is left empty: no
 * terrain, no biomes to work out, no downloads, only an invisible barrier wall (full height) on its sides that touch
 * the allowed area; and players who get past the wall any other way (above the build limit, an ender pearl) are put
 * back where they last stood inside. Such empty chunks are listed in {@code void-chunks.bin}; when one of them is
 * inside the allowed area later (the area grew, or the limit was switched off) its saved copy is ignored the next
 * time it loads (ChunkMapMixin), so it generates properly.
 * <p>
 * On or off is the world's setting ("Only generate the selection"), overridden by {@code /orbis hardlimit on|off}
 * (kept in {@code hard-limit.txt}).
 */
public final class HardLimit {
    private static final BlockState BARRIER = Blocks.BARRIER.defaultBlockState();

    private static volatile boolean enabled;
    private static volatile ChunkSelection allowed;
    private static final Set<Long> voidChunks = ConcurrentHashMap.newKeySet();
    private static volatile boolean voidDirty;
    private static volatile Path dir;
    private static long lastVoidSave;
    private static final Map<UUID, double[]> lastInside = new HashMap<>();

    private HardLimit() {}

    public static void register() {
        // As the overworld loads, before its spawn area is generated (SERVER_STARTED would be too late for those chunks).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register((server, world) -> {
            if (world.dimension() == Level.OVERWORLD) load(server, world);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveVoid());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            enabled = false;
            allowed = null;
            voidChunks.clear();
            lastInside.clear();
            dir = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(HardLimit::tick);
    }

    // ------------------------------------------------------------------ the allowed area

    private static void load(MinecraftServer server, ServerLevel overworld) {
        WorldModel model = OrbisMod.model();
        if (model == null || !(overworld.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator gen)) return;
        dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen");
        // The world's own settings (saved with it), not the active model's: as the world loads, the model can still
        // be the one built from the installation's defaults, whose selection belonged to another world (a Bergen
        // world allowed a Stord selection too, 2 Oct 2026).
        OrbisConfig own = gen.settings().map(s -> s.effective(OrbisMod.config())).orElse(model.cfg());
        CoordinateMapper mapper = new CoordinateMapper(own.originLat, own.originLon, own.metersPerBlock,
                CoordinateMapper.Projection.of(own.projection));
        ChunkSelection area = ChunkSelection.ofSettings(own.pregenShapes, mapper);
        ChunkSelection extra = readRows(dir.resolve("allowed-extra.json"));
        if (extra != null) area.addAll(extra);
        allowed = area;
        String override = readText(dir.resolve("hard-limit.txt"));
        boolean on = override != null ? "on".equals(override) : own.pregenHardLimit;
        loadVoid();
        if (on && area.isEmpty()) {
            System.out.println("[orbis] Hard limit is on but the world has no allowed area yet (nothing selected or pre-generated): everything generates");
            on = false;
        }
        enabled = on;
        if (on) {
            System.out.println(String.format(java.util.Locale.ROOT, "[orbis] Hard limit on: only %,d chunks generate (the world's selection and its pre-generated areas)", area.count()));
        }
    }

    /** A pre-generation started: its chunks join the allowed area (recorded whether the limit is on or not). */
    public static void extend(MinecraftServer server, ChunkSelection chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        Path d = dir != null ? dir : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("orbis-pregen");
        ChunkSelection now = new ChunkSelection();
        ChunkSelection a = allowed;
        if (a != null) now.addAll(a);
        now.addAll(chunks);
        allowed = now;
        broadcast(server);
        Path f = d.resolve("allowed-extra.json");
        ChunkSelection extra = readRows(f);
        if (extra == null) extra = new ChunkSelection();
        extra.addAll(chunks);
        try {
            Files.createDirectories(d);
            Files.writeString(f, extra.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save the allowed area: " + e);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /** The allowed area (the world's selection and every area pre-generated since), or null when there is none. */
    public static ChunkSelection allowedArea() {
        ChunkSelection a = allowed;
        return a == null || a.isEmpty() ? null : a;
    }

    /** True when the limit is on and the chunk lies outside the allowed area (it is left empty). */
    public static boolean blocks(int cx, int cz) {
        if (!enabled) return false;
        ChunkSelection a = allowed;
        return a != null && !a.contains(cx, cz);
    }

    /** False when the limit is on and no chunk of this block rectangle is allowed (its map data is not needed). */
    public static boolean needed(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        if (!enabled) return true;
        ChunkSelection a = allowed;
        return a == null || a.intersects(minBlockX >> 4, minBlockZ >> 4, maxBlockX >> 4, maxBlockZ >> 4);
    }

    // ------------------------------------------------------------------ empty chunks

    /** Leaves a blocked chunk empty, with a barrier wall along its sides that touch the allowed area. */
    public static void fillBlocked(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        voidChunks.add(ChunkPos.pack(pos.x(), pos.z()));
        voidDirty = true;
        int minY = chunk.getMinY(), maxY = chunk.getMaxY();
        boolean west = !blocks(pos.x() - 1, pos.z()), east = !blocks(pos.x() + 1, pos.z());
        boolean north = !blocks(pos.x(), pos.z() - 1), south = !blocks(pos.x(), pos.z() + 1);
        if (!(west || east || north || south)) return;
        boolean[] touched = new boolean[chunk.getSections().length];
        for (int y = minY; y <= maxY; y++) {
            int si = chunk.getSectionIndex(y);
            var states = chunk.getSection(si).getStates();
            for (int i = 0; i < 16; i++) {
                if (west) states.getAndSetUnchecked(0, y & 15, i, BARRIER);
                if (east) states.getAndSetUnchecked(15, y & 15, i, BARRIER);
                if (north) states.getAndSetUnchecked(i, y & 15, 0, BARRIER);
                if (south) states.getAndSetUnchecked(i, y & 15, 15, BARRIER);
            }
            touched[si] = true;
        }
        for (int i = 0; i < touched.length; i++) if (touched[i]) chunk.getSections()[i].recalcBlockCounts();
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
    }

    /** A chunk the hard limit left empty (and not generated properly since): the world map shows it as not generated. */
    public static boolean emptied(int cx, int cz) {
        return !voidChunks.isEmpty() && voidChunks.contains(ChunkPos.pack(cx, cz));
    }

    /**
     * Called as a chunk is read from disk: an empty chunk left by the limit that is now inside the allowed area (or
     * the limit is off) is not loaded, so it generates properly instead.
     */
    public static boolean regenerate(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        if (!voidChunks.contains(key) || blocks(cx, cz)) return false;
        voidChunks.remove(key);
        voidDirty = true;
        System.out.println("[orbis] Chunk " + cx + ", " + cz + " was left empty by the hard limit and is inside the area now: generating it");
        return true;
    }

    private static void loadVoid() {
        voidChunks.clear();
        Path f = dir.resolve("void-chunks.bin");
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) voidChunks.add(in.readLong());
        } catch (IOException e) {
            System.err.println("[orbis] Could not read the hard limit's empty chunks: " + e);
        }
    }

    private static synchronized void saveVoid() {
        Path d = dir;
        if (d == null || !voidDirty) return;
        voidDirty = false;
        try {
            Files.createDirectories(d);
            Path tmp = d.resolve("void-chunks.bin.tmp");
            Long[] keys = voidChunks.toArray(new Long[0]);
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(keys.length);
                for (Long k : keys) out.writeLong(k);
            }
            Files.move(tmp, d.resolve("void-chunks.bin"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[orbis] Could not save the hard limit's empty chunks: " + e);
        }
    }

    // ------------------------------------------------------------------ players

    /** Every tick: a player outside the allowed area goes back to where they last stood inside it. */
    private static void tick(MinecraftServer server) {
        if (System.currentTimeMillis() - lastVoidSave > 30_000) {
            lastVoidSave = System.currentTimeMillis();
            if (voidDirty) java.util.concurrent.CompletableFuture.runAsync(HardLimit::saveVoid); // not on the game's tick
        }
        if (!enabled) return;
        ServerLevel level = server.overworld();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            int cx = (int) Math.floor(p.getX()) >> 4, cz = (int) Math.floor(p.getZ()) >> 4;
            if (!blocks(cx, cz)) {
                lastInside.put(p.getUUID(), new double[]{p.getX(), p.getY(), p.getZ()});
                continue;
            }
            double[] back = lastInside.get(p.getUUID());
            if (back == null) back = nearestInside(level, cx, cz);
            if (back == null) continue;
            p.connection.teleport(back[0], back[1], back[2], p.getYRot(), p.getXRot());
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
    }

    /** The middle of the nearest allowed chunk, on its ground. */
    private static double[] nearestInside(ServerLevel level, int cx, int cz) {
        ChunkSelection a = allowed;
        int[] c = a == null ? null : a.nearest(cx, cz);
        if (c == null) return null;
        int x = (c[0] << 4) + 8, z = (c[1] << 4) + 8;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new double[]{x + 0.5, y + 1, z + 0.5};
    }

    // ------------------------------------------------------------------ command

    public static Component set(MinecraftServer server, Boolean on) {
        if (on == null) return status();
        if (dir == null) return Component.literal("Not an Orbis Terrarum world.");
        ChunkSelection a = allowed;
        if (on && (a == null || a.isEmpty())) {
            return Component.literal("There is no allowed area yet: select an area on the world map and Generate it (or /orbis pregen), then switch the hard limit on.");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("hard-limit.txt"), (on ? "on" : "off") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Component.literal("Could not save the setting: " + e.getMessage());
        }
        enabled = on;
        lastInside.clear();
        broadcast(server);
        return Component.literal(on
                ? String.format(java.util.Locale.ROOT, "Hard limit on: only the allowed area (%,d chunks) generates; players are kept inside it.", a.count())
                : "Hard limit off: the whole world generates again (chunks it left empty generate when they load).");
    }

    /** The allowed area for one player's world map (players with the mod only). */
    public static void sendTo(ServerPlayer player) {
        if (dir == null || !net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.berg.orbis.net.AllowedAreaPayload.TYPE)) return;
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, com.berg.orbis.net.AllowedAreaPayload.of(enabled, allowed));
    }

    /** The allowed area changed (grew, or the limit was switched): every world map gets the new one. */
    private static void broadcast(MinecraftServer server) {
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) sendTo(p);
        });
    }

    public static Component status() {
        ChunkSelection a = allowed;
        if (dir == null) return Component.literal("Not an Orbis Terrarum world.");
        return Component.literal(String.format(java.util.Locale.ROOT, "Hard limit %s. Allowed area: %,d chunks (the world's selection and every area pre-generated since); %,d chunks left empty so far.",
                enabled ? "ON" : "off", a == null ? 0 : a.count(), voidChunks.size()));
    }

    // ------------------------------------------------------------------ files

    private static ChunkSelection readRows(Path f) {
        try {
            return Files.exists(f) ? ChunkSelection.fromJson(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[orbis] Could not read " + f.getFileName() + ": " + e);
            return null;
        }
    }

    private static String readText(Path f) {
        try {
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
