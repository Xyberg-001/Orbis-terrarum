package com.berg.orbis.mixin.client;

import com.berg.orbis.client.SpawnGate;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Create New World" first makes sure the map data around the spawn has
 * arrived. Vanilla's spawn search generates chunks synchronously on the
 * server thread; with the data still downloading that looked like the game
 * hanging. Now a progress screen waits instead, and creation resumes by itself.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void orbis$waitForSpawnData(CallbackInfo ci) {
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        if (SpawnGate.shouldWait()) {
            SpawnGate.begin(self);
            ci.cancel();
        }
    }
}
