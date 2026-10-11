package com.berg.orbis.mc;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import java.nio.file.Path;

/** Minecraft 26.3 versions of client calls whose shape differs between Minecraft versions. */
public final class McClient {

    private McClient() {
    }

    /** Opens a file with the system's default program (the browser for a web page). */
    public static void openPath(Path path) {
        Blaze3D.openPath(path);
    }

    /** Whether the world being created will be cubic (Cubic Chunks installed, and its tab on the creation screen says so). */
    public static boolean newWorldCubic(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState state) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cubicchunks")
                && io.github.opencubicchunks.cubicchunks.api.client.CubicClientApi.isNewWorldCubic(state);
    }

    /** How far the world border reaches from 0 on x and z with Cubic Chunks installed (Integer.MAX_VALUE without it). */
    public static int cubicReach() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cubicchunks")
                ? io.github.opencubicchunks.cubicchunks.api.CubicApi.borderReach() : Integer.MAX_VALUE;
    }

    /** A smooth (linear), edge-clamped texture sampler. */
    public static GpuSampler linearClampSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
