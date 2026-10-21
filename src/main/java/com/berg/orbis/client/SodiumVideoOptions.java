package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Orbis Terrarum's page in Sodium's video settings: the far view (Distant Horizons and Voxy), which is about what you see,
 * so it sits with the other view settings rather than in Mod Menu. Sodium finds this through the
 * {@code sodium:config_api_user} entry point and only then loads the class, so without Sodium nothing here runs (Minecraft's
 * own video settings get the same options from mixin/VideoSettingsFarViewMixin). Each option is greyed out unless its mod
 * is installed. The options change the installation config and apply at once.
 */
public final class SodiumVideoOptions implements ConfigEntryPoint {
    /** Sodium calls this after it has stored the changed values through the bindings: the config file is written once. */
    private static final StorageEventHandler SAVE = () -> OrbisMod.updateConfig(c -> { });

    @Override
    public void registerConfigLate(ConfigBuilder b) {
        boolean dh = FarViewOptions.distantHorizons(), voxy = FarViewOptions.voxy();
        OptionGroupBuilder far = b.createOptionGroup().setName(Component.translatable("orbisterrarum.group.farView"));
        far.addOption(b.createBooleanOption(id("far_view_from_data"))
                .setName(Component.translatable("orbisterrarum.opt.farViewFromData"))
                .setTooltip(FarViewOptions.tooltip("farViewFromData", dh, "Distant Horizons"))
                .setDefaultValue(true)
                .setEnabled(dh)
                .setStorageHandler(SAVE)
                .setBinding(v -> OrbisMod.config().farViewFromData = v, () -> OrbisMod.config().farViewFromData));
        far.addOption(b.createBooleanOption(id("voxy_far_view"))
                .setName(Component.translatable("orbisterrarum.opt.voxyFarView"))
                .setTooltip(FarViewOptions.tooltip("voxyFarView", voxy, "Voxy"))
                .setDefaultValue(false)
                .setEnabled(voxy)
                .setStorageHandler(SAVE)
                .setBinding(v -> OrbisMod.config().voxyFarView = v, () -> OrbisMod.config().voxyFarView));
        far.addOption(b.createIntegerOption(id("voxy_far_view_chunks"))
                .setName(Component.translatable("orbisterrarum.opt.voxyFarViewChunks"))
                .setTooltip(FarViewOptions.tooltip("voxyFarViewChunks", voxy, "Voxy"))
                .setRange(FarViewOptions.MIN_CHUNKS, FarViewOptions.MAX_CHUNKS, FarViewOptions.STEP_CHUNKS)
                .setValueFormatter(FarViewOptions::chunksLabel)
                .setDefaultValue(512)
                .setEnabled(voxy)
                .setStorageHandler(SAVE)
                .setBinding(v -> OrbisMod.config().voxyFarViewChunks = v, () -> OrbisMod.config().voxyFarViewChunks));
        b.registerOwnModOptions()
                .setNonTintedIcon(Identifier.fromNamespaceAndPath("orbisterrarum", "icon.png"))
                .addPage(b.createOptionPage().setName(Component.translatable("orbisterrarum.group.farView")).addOptionGroup(far));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("orbisterrarum", path);
    }
}
