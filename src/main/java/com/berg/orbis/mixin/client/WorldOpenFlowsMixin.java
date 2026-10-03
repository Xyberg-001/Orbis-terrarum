package com.berg.orbis.mixin.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.sky.Seasons;
import com.berg.orbis.worldgen.WorldHeight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Before a singleplayer world opens: an Orbis world (one with the mod's world pack) gets this month's season pack,
 * since biome colours are read with the world's registries and cannot change while it is loaded.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    @Inject(method = "openWorld", at = @At("HEAD"))
    private void orbisterrarum$season(String levelId, Runnable onCancel, CallbackInfo ci) {
        try {
            Path level = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(levelId);
            Path packs = level.resolve("datapacks");
            if (!Files.isDirectory(packs.resolve(WorldHeight.PACK_NAME))) return;
            double lat = Seasons.worldLatitude(level);
            if (Double.isNaN(lat)) lat = OrbisMod.config().originLat;
            Seasons.update(packs, lat, Seasons.worldWantsSeasons(level)); // the world's own setting decides
        } catch (RuntimeException e) {
            System.err.println("[orbis] Season pack not updated: " + e);
        }
    }
}
