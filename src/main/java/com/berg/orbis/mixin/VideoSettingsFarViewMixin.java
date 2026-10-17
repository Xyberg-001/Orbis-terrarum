package com.berg.orbis.mixin;

import com.berg.orbis.client.FarViewOptions;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The far view settings at the foot of Minecraft's own video settings, for players without Sodium (with Sodium they are
 * on Orbis Terrarum's page in Sodium's settings, see client/SodiumVideoOptions). Only for the far view mods installed;
 * nothing when there are none.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsFarViewMixin extends OptionsSubScreen {
    private VideoSettingsFarViewMixin() {
        super(null, null, null);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void orbis$farView(CallbackInfo ci) {
        if (this.list == null) return;
        List<OptionInstance<?>> options = FarViewOptions.vanillaOptions();
        if (options.isEmpty()) return;
        this.list.addHeader(Component.translatable("orbisterrarum.group.farView"));
        this.list.addSmall(options.toArray(new OptionInstance<?>[0]));
    }
}
