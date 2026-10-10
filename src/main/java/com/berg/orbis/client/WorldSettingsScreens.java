package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Entry points for the two settings screens. The screens themselves are
 * built with YetAnotherConfigLib in {@link YaclScreens}; that class is only
 * touched when the library is present, so the mod still loads without it.
 */
public final class WorldSettingsScreens {

    private WorldSettingsScreens() {}

    public static boolean yaclPresent() {
        return FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3");
    }

    /** The world creation screen's choices while its Customize is open (whether the world will be cubic, see AreaPreviewScreen). */
    static volatile net.minecraft.client.gui.screens.worldselection.WorldCreationUiState creating;

    /** Create New World → Customize: choose the world's settings, store them in its generator. */
    public static Screen newWorldScreen(CreateWorldScreen parent) {
        if (!yaclPresent()) return missingLibraryScreen(parent);
        creating = parent.getUiState();
        return YaclScreens.worldSettings(parent, settings -> {
            // Defaults for the next world too, and the active model switches to it
            // so the spawn area starts downloading while the player finishes the form.
            OrbisMod.saveWorldDefaults(settings);
            // Bake the settings into this world's generator so it is independent of later config changes.
            parent.getUiState().updateDimensions((registries, dimensions) -> dimensions.replaceOverworldGenerator(registries,
                    new RealWorldChunkGenerator(dimensions.overworld().getBiomeSource(), Optional.of(settings))));
        });
    }

    /** From Mod Menu: the installation settings (data sources, network, caches) plus the defaults for new worlds. */
    public static Screen configScreen(Screen parent) {
        if (!yaclPresent()) return missingLibraryScreen(parent);
        return YaclScreens.installationSettings(parent);
    }

    private static Screen missingLibraryScreen(Screen parent) {
        return new AlertScreen(() -> Minecraft.getInstance().gui.setScreen(parent),
                Component.translatable("orbisterrarum.screen.noyacl.title"),
                Component.translatable("orbisterrarum.screen.noyacl.text"));
    }
}
