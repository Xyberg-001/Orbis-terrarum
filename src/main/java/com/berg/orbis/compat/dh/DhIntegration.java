package com.berg.orbis.compat.dh;

import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
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
        System.out.println("[orbis] Distant Horizons found: Orbis worlds will use the Orbis LOD generator");
    }
}
