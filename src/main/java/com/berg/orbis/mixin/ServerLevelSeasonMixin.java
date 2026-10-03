package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Real snow depth: where the world has it on, the snow on the ground settles towards today's real depth (see
 * sky/SnowCover) a layer at a time, on the ticks Minecraft considers snowfall: more layers where the real snow is
 * deeper (and the column is natural ground), fewer where it has melted; not while it is snowing there.
 *
 * Spring thaw in Orbis worlds with real seasons. Winter's season pack cools the biomes so rain falls as snow and
 * water freezes where it is cold; Minecraft never melts that snow and ice again (only light does). On the same
 * tick Minecraft lets it snow, a snow layer loses a layer and surface ice turns back to water wherever the biome
 * is warm enough to rain again, so the snow of winter is gone a few days into spring. Snow the world was generated
 * with lies in biomes that are cold all year and never melts.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeasonMixin {

    @Inject(method = "tickPrecipitation", at = @At("TAIL"))
    private void orbisterrarum$thaw(BlockPos pos, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!OrbisMod.isOrbisSeaLevel(level.getSeaLevel())) return; // the world's own setting decides (below)
        var model = OrbisMod.model();
        if (model == null) return;
        if (level.getRandom().nextInt(3) != 0) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        Biome biome = level.getBiome(top).value();
        if (model.cfg().realSnow && settle(level, model, top, biome)) return;
        if (!model.cfg().realSeasons) return;
        if (!biome.warmEnoughToRain(top, level.getSeaLevel())) return;
        BlockState snow = level.getBlockState(top);
        if (!model.cfg().realSnow && snow.is(Blocks.SNOW)) {
            int layers = snow.getValue(SnowLayerBlock.LAYERS);
            level.setBlockAndUpdate(top, layers > 1 ? snow.setValue(SnowLayerBlock.LAYERS, layers - 1) : Blocks.AIR.defaultBlockState());
            return;
        }
        BlockPos below = top.below();
        if (level.getBlockState(below).is(Blocks.ICE)) level.setBlockAndUpdate(below, Blocks.WATER.defaultBlockState());
    }

    /** One layer towards today's real snow depth; true when the column was handled (changed, or left alone on purpose). */
    private static boolean settle(ServerLevel level, com.berg.orbis.worldgen.WorldModel model, BlockPos top, Biome biome) {
        // The snow pile: a layer at the top (or none) on whole snow blocks.
        BlockState at = level.getBlockState(top);
        int layers = at.is(Blocks.SNOW) ? at.getValue(SnowLayerBlock.LAYERS) : 0;
        if (layers == 0 && !at.isAir()) return false; // a plant, a rail, a carpet: leave it
        BlockPos ground = top.below();
        int blocks = 0;
        while (blocks < 48 && level.getBlockState(ground).is(Blocks.SNOW_BLOCK)) {
            ground = ground.below();
            blocks++;
        }
        int have = blocks * 8 + layers;
        int want = model.snowLayersIfKnown(top.getX(), ground.getY(), top.getZ());
        if (want < 0) return false;
        if (want > have) {
            if (level.isRaining() && biome.getPrecipitationAt(top, level.getSeaLevel()) == Biome.Precipitation.SNOW) return true;
            // Only natural ground grows a pile: roads, roofs and squares keep the one layer they were given.
            BlockState g = level.getBlockState(ground);
            boolean natural = g.is(BlockTags.DIRT) || g.is(BlockTags.BASE_STONE_OVERWORLD) || g.is(Blocks.GRAVEL)
                    || g.is(Blocks.SAND) || g.is(Blocks.PACKED_ICE) || g.is(Blocks.BLUE_ICE) || g.is(Blocks.ICE);
            if (!natural && have > 0) return true;
            if (layers == 0) {
                BlockState one = Blocks.SNOW.defaultBlockState();
                if (one.canSurvive(level, top)) level.setBlockAndUpdate(top, one);
            } else if (layers < 7) {
                level.setBlockAndUpdate(top, at.setValue(SnowLayerBlock.LAYERS, layers + 1));
            } else {
                level.setBlockAndUpdate(top, Blocks.SNOW_BLOCK.defaultBlockState());
            }
            return true;
        }
        if (want < have) {
            if (level.isRaining() && biome.getPrecipitationAt(top, level.getSeaLevel()) == Biome.Precipitation.SNOW) return true;
            if (layers > 1) level.setBlockAndUpdate(top, at.setValue(SnowLayerBlock.LAYERS, layers - 1));
            else if (layers == 1) level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            else level.setBlockAndUpdate(top.below(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 7));
            return true;
        }
        return false; // as deep as today's: the ice below may still thaw
    }
}
