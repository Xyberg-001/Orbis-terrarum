package com.berg.orbis.render;

import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.WorldGenLevel;

/**
 * The ids of the entities the decoration places (residents, street animals, item frames, minecarts), worked out from the world's seed, the
 * entity's kind and where it stands instead of drawn at random. Making a chunk's or cube's decoration again (a cubic world whose server
 * stopped before a cube's finished state was saved, while its entities already were) then brings back the same ids, and the game keeps
 * the one already in the world instead of a second villager. Two placed on the same spot are one.
 */
final class SpawnIds {
    private SpawnIds() {
    }

    static void assign(WorldGenLevel level, Entity entity) {
        long h = level.getSeed() * 0x9E3779B97F4A7C15L;
        h = mix(h ^ EntityType.getKey(entity.getType()).toString().hashCode());
        h = mix(h ^ entity.blockPosition().asLong());
        h = mix(h ^ Float.floatToIntBits(entity.getYRot()) ^ ((long) entity.getDirection().ordinal() << 40));
        long low = mix(h ^ 0x5EED);
        // version 4 and the IETF variant, as a random id has
        entity.setUUID(new UUID((h & ~0xF000L) | 0x4000L, (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
