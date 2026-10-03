package com.berg.orbis.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;
import java.util.Optional;

/** Minecraft 26.3 versions of the game calls whose shape differs between Minecraft versions (server side). */
public final class Mc {

    private Mc() {
    }

    /** Vanilla's animals at chunk generation, for the biome at {@code biomePos}. */
    public static void spawnMobsForChunkGeneration(WorldGenRegion level, BlockPos biomePos, ChunkPos center, RandomSource random) {
        NaturalSpawner.spawnMobsForChunkGeneration(level, biomePos, center, random);
    }

    /** The structure starts of one structure referenced from the chunk at chunkX, chunkZ. */
    public static List<StructureStart> startsForStructure(StructureManager manager, int chunkX, int chunkZ, Structure structure) {
        return manager.startsForStructure(chunkX, chunkZ, structure);
    }

    /** Writes up to four lines on the front of a sign (may throw NullPointerException in a chunk still generating). */
    public static void setSignFront(SignBlockEntity sign, List<String> lines) {
        SignText.Mutable text = SignText.EMPTY.asMutable();
        for (int i = 0; i < lines.size() && i < 4; i++) text = text.setLine(i, Component.literal(lines.get(i)));
        sign.setText(text.asImmutable(), SignTextSlot.FRONT);
    }

    /** The same on the back of a sign. */
    public static void setSignBack(SignBlockEntity sign, List<String> lines) {
        SignText.Mutable text = SignText.EMPTY.asMutable();
        for (int i = 0; i < lines.size() && i < 4; i++) text = text.setLine(i, Component.literal(lines.get(i)));
        sign.setText(text.asImmutable(), SignTextSlot.BACK);
    }

    /** One of vanilla's world-generation features by name (for example "monster_room"). */
    public static Optional<PlaceableFeature> feature(RegistryAccess access, String name) {
        return access.lookupOrThrow(Registries.FEATURE)
                .get(ResourceKey.create(Registries.FEATURE, Identifier.withDefaultNamespace(name)))
                .map(holder -> holder.value()::place);
    }

    /** Vanilla's biome-info noise (kelp patches, coral reefs), -1..1; a {@code Noise} in 26.3. */
    public static double biomeInfoNoise(double x, double z) {
        return net.minecraft.world.level.biome.Biome.BIOME_INFO_NOISE.get(x, z);
    }

    /** An advancement's "player" condition that checks entity properties: one typed condition in 26.3. */
    public static JsonElement playerCondition(JsonObject entityPredicate) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:entity_properties");
        condition.addProperty("entity", "this");
        condition.add("predicate", entityPredicate);
        return condition;
    }

    /** The chunk status once Orbis's terrain is built: 26.3 builds terrain in one step, "terrain". */
    public static final net.minecraft.world.level.chunk.status.ChunkStatus TERRAIN_DONE = net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN;

    /** The server's structure templates (26.3 name). */
    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager structureTemplates(net.minecraft.server.MinecraftServer server) {
        return server.getStructureTemplateManager();
    }

    /** What 26.3's terrain step does after the generator built the terrain: the four heightmaps the game keeps. */
    public static void afterTerrain(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));
    }
}
