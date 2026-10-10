package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Point-of-interest queries (villagers looking for a bed, a job or a bell, bees for a hive, ...) walk every
 * section of every chunk within range. Vanilla's world is 24 sections tall; this one is 254, so each query
 * costs ten times more, and a city of residents multiplies it again: the server thread was spending whole
 * seconds per tick in these scans. In an Orbis Terrarum world a query that is not about nether portals only
 * looks at the sections from 64 blocks below to 96 blocks above the chunk's surface, where every bed, job
 * block and bell of the city is, and skips chunks that are not loaded (vanilla would read their POI data from
 * disk on the server thread). Portal searches keep the full range so that caves and the underground keep
 * working.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {

    private static Holder<PoiType> orbisterrarum$portal;

    @Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$surfaceSectionsOnly(Predicate<Holder<PoiType>> predicate, ChunkPos chunk, PoiManager.Occupancy occupancy,
                                                  CallbackInfoReturnable<Stream<PoiRecord>> cir) {
        LevelHeightAccessor lha = ((SectionStorageAccessor) this).orbisterrarum$heightAccessor();
        if (!(lha instanceof ServerLevel level) || !OrbisMod.isOrbisLevel(level)) return;
        if (!level.getServer().isSameThread()) return;
        Holder<PoiType> portal = orbisterrarum$portal;
        if (portal == null) {
            try {
                portal = level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.NETHER_PORTAL);
                orbisterrarum$portal = portal;
            } catch (RuntimeException e) {
                return;
            }
        }
        if (predicate.test(portal)) return; // portal linking may need the deep underground
        LevelChunk c = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (c == null) {
            cir.setReturnValue(Stream.empty());
            return;
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] s : new int[][]{{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}, {4, 11}, {11, 4}}) {
            int y = c.getHeight(Heightmap.Types.MOTION_BLOCKING, s[0], s[1]);
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        int minSection = Math.max(level.getMinSectionY(), SectionPos.blockToSectionCoord(lo - 64));
        int maxSection = Math.min(level.getMaxSectionY(), SectionPos.blockToSectionCoord(hi + 96));
        SectionStorageAccessor storage = (SectionStorageAccessor) this;
        cir.setReturnValue(IntStream.rangeClosed(minSection, maxSection).boxed()
                .map(y -> storage.orbisterrarum$getOrLoad(SectionPos.of(chunk, y).asLong()))
                .filter(Optional::isPresent)
                .flatMap(o -> ((PoiSection) o.get()).getRecords(predicate, occupancy)));
    }
}
