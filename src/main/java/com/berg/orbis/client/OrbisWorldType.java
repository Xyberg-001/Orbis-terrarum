package com.berg.orbis.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The "Orbis Terrarum" world type ({@code data/orbisterrarum/worldgen/world_preset/earth.json}). With it selected
 * on the Create New World screen, Customize opens the Orbis settings; every other world type stays vanilla.
 */
public final class OrbisWorldType {
    public static final ResourceKey<WorldPreset> KEY = ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath("orbisterrarum", "earth"));

    private OrbisWorldType() {}

    public static boolean is(WorldCreationUiState.WorldTypeEntry entry) {
        return entry != null && entry.preset() != null && entry.preset().is(KEY);
    }
}
