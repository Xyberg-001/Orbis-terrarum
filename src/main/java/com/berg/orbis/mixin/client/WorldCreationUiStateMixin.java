package com.berg.orbis.mixin.client;

import com.berg.orbis.client.WorldSettingsScreens;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With the "Orbis Terrarum" world type selected, vanilla's own "Customize"
 * button opens the Orbis Terrarum world settings. Every other world type
 * keeps vanilla's Customize.
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {

    @org.spongepowered.asm.mixin.Shadow
    public abstract net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry getWorldType();

    @Inject(method = "getPresetEditor", at = @At("RETURN"), cancellable = true)
    private void orbis$customizeOpensWorldSettings(CallbackInfoReturnable<PresetEditor> cir) {
        if (cir.getReturnValue() != null) return;
        if (!com.berg.orbis.client.OrbisWorldType.is(getWorldType())) return; // vanilla world types keep vanilla's Customize
        cir.setReturnValue((createWorldScreen, context) -> WorldSettingsScreens.newWorldScreen(createWorldScreen));
    }
}
