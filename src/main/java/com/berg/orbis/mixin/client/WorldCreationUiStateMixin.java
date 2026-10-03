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
 * keeps vanilla's Customize. In the World Type button's list, Orbis Terrarum
 * comes right after Default instead of last (presets added through the tag
 * are appended); the other mods' presets keep their places.
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {

    @org.spongepowered.asm.mixin.Shadow
    public abstract net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry getWorldType();

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.List<WorldCreationUiState.WorldTypeEntry> normalPresetList;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.List<WorldCreationUiState.WorldTypeEntry> altPresetList;

    @Inject(method = "updatePresetLists", at = @At("TAIL"))
    private void orbis$afterDefault(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        orbis$moveAfterDefault(normalPresetList);
        orbis$moveAfterDefault(altPresetList);
    }

    private static void orbis$moveAfterDefault(java.util.List<WorldCreationUiState.WorldTypeEntry> list) {
        int orbis = -1, normal = -1;
        for (int i = 0; i < list.size(); i++) {
            if (com.berg.orbis.client.OrbisWorldType.is(list.get(i))) orbis = i;
            else if (list.get(i).preset().is(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)) normal = i;
        }
        if (orbis < 0 || normal < 0 || orbis == normal + 1) return;
        WorldCreationUiState.WorldTypeEntry entry = list.remove(orbis);
        list.add(orbis < normal ? normal : normal + 1, entry);
    }

    @Inject(method = "getPresetEditor", at = @At("RETURN"), cancellable = true)
    private void orbis$customizeOpensWorldSettings(CallbackInfoReturnable<PresetEditor> cir) {
        if (cir.getReturnValue() != null) return;
        if (!com.berg.orbis.client.OrbisWorldType.is(getWorldType())) return; // vanilla world types keep vanilla's Customize
        cir.setReturnValue((createWorldScreen, context) -> WorldSettingsScreens.newWorldScreen(createWorldScreen));
    }
}
