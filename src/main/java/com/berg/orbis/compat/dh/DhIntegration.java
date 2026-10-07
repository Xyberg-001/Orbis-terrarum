package com.berg.orbis.compat.dh;

import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;

/**
 * Registers {@link OrbisDhGenerator} for every Orbis Terrarum level Distant Horizons loads. Distant Horizons' own
 * generator would run the full Orbis chunk generator on its threads (real map downloads per region), which is far
 * too slow for distant terrain; the Orbis generator reads saved chunks straight from the region files instead.
 */
final class DhIntegration {

    private DhIntegration() {}

    static void register() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                IDhApiLevelWrapper wrapper = input.value.levelWrapper;
                if (wrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL) return;
                if (!(wrapper.getWrappedMcObject() instanceof ServerLevel level)) return;
                if (!(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
                DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, new OrbisDhGenerator(level, wrapper));
                if (result.success) {
                    System.out.println("[orbis] Distant Horizons: Orbis LOD generator active for " + wrapper.getDimensionName());
                } else {
                    System.err.println("[orbis] Distant Horizons: could not register the Orbis LOD generator (" + result.message + ")");
                }
            }
        });
        DhApi.events.bind(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
            @Override
            public void onChunkModified(DhApiEventParam<EventParam> input) {
                countChunkUpdate(input.value.chunkX, input.value.chunkZ);
            }
        });
        System.out.println("[orbis] Distant Horizons found: Orbis worlds will use the Orbis LOD generator");
    }

    // ------------------------------------------------------------------ chunk updates

    /** Times Distant Horizons turned each loaded chunk into LODs again (a chunk is redone each time it is saved). */
    private static final java.util.Map<Long, java.util.concurrent.atomic.AtomicInteger> UPDATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong UPDATES_TOTAL = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastUpdateLog = System.currentTimeMillis();

    /**
     * Counts Distant Horizons' chunk updates and logs them once a minute: on an Everest world its LOD builders were
     * busy for 15 minutes with the few hundred chunks around the player, apparently doing the same chunks over.
     */
    private static void countChunkUpdate(int x, int z) {
        UPDATES.computeIfAbsent(((long) x << 32) | (z & 0xffffffffL), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        long total = UPDATES_TOTAL.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastUpdateLog < 60_000) return;
        synchronized (UPDATES) {
            if (now - lastUpdateLog < 60_000) return;
            lastUpdateLog = now;
        }
        int distinct = UPDATES.size(), most = 0, again = 0;
        for (java.util.concurrent.atomic.AtomicInteger c : UPDATES.values()) {
            most = Math.max(most, c.get());
            if (c.get() > 1) again++;
        }
        System.out.println(String.format("[orbis] Distant Horizons chunk updates so far: %,d for %,d chunks (%,d of them more than once, the most %d times)",
                total, distinct, again, most));
    }
}
