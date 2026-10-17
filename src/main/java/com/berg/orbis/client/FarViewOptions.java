package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The far view settings, shared by Sodium's video settings ({@link SodiumVideoOptions}) and Minecraft's own (without
 * Sodium, mixin/VideoSettingsFarViewMixin): Distant Horizons showing land not generated yet, and Voxy's far view fill and
 * its radius. In Mod Menu until 17 Oct 2026.
 */
public final class FarViewOptions {
    public static final int MIN_CHUNKS = 64, MAX_CHUNKS = 1024, STEP_CHUNKS = 64;

    private FarViewOptions() {
    }

    public static boolean distantHorizons() {
        return FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    public static boolean voxy() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    /** The option's explanation, and when its mod is not installed, that it needs it. */
    public static Component tooltip(String key, boolean installed, String mod) {
        Component desc = Component.translatable("orbisterrarum.opt." + key + ".desc");
        return installed ? desc : Component.translatable("orbisterrarum.opt.farView.needs", mod).append("\n\n").append(desc);
    }

    /** "512 chunks (8.2 km)": the radius in chunks and in kilometres at this world's scale (1:1 when none is open). */
    public static Component chunksLabel(int chunks) {
        double mpb = OrbisMod.model() != null ? OrbisMod.model().cfg().metersPerBlock : 1;
        return Component.literal(String.format(java.util.Locale.ROOT, "%d chunks (%.1f km)", chunks, chunks * 16 * mpb / 1000));
    }

    /**
     * Minecraft's own video settings (no Sodium): the options for the far view mods that are installed (Voxy needs Sodium,
     * so in practice Distant Horizons). Made afresh each time the screen opens from the config; never saved in options.txt.
     */
    public static List<OptionInstance<?>> vanillaOptions() {
        List<OptionInstance<?>> out = new ArrayList<>();
        if (distantHorizons()) {
            out.add(OptionInstance.createBoolean("orbisterrarum.opt.farViewFromData",
                    OptionInstance.cachedConstantTooltip(Component.translatable("orbisterrarum.opt.farViewFromData.desc")),
                    OrbisMod.config().farViewFromData, v -> OrbisMod.updateConfig(c -> c.farViewFromData = v)));
        }
        if (voxy()) {
            out.add(OptionInstance.createBoolean("orbisterrarum.opt.voxyFarView",
                    OptionInstance.cachedConstantTooltip(Component.translatable("orbisterrarum.opt.voxyFarView.desc")),
                    OrbisMod.config().voxyFarView, v -> OrbisMod.updateConfig(c -> c.voxyFarView = v)));
            out.add(new OptionInstance<>("orbisterrarum.opt.voxyFarViewChunks",
                    OptionInstance.cachedConstantTooltip(Component.translatable("orbisterrarum.opt.voxyFarViewChunks.desc")),
                    (caption, steps) -> Component.translatable("options.generic_value", caption, chunksLabel(steps * STEP_CHUNKS)),
                    new OptionInstance.IntRange(MIN_CHUNKS / STEP_CHUNKS, MAX_CHUNKS / STEP_CHUNKS),
                    Math.max(MIN_CHUNKS, Math.min(MAX_CHUNKS, OrbisMod.config().voxyFarViewChunks)) / STEP_CHUNKS,
                    steps -> OrbisMod.updateConfig(c -> c.voxyFarViewChunks = steps * STEP_CHUNKS)));
        }
        return out;
    }
}
