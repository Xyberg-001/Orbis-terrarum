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

    /** A smooth (linear), edge-clamped texture sampler. */
    public static GpuSampler linearClampSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
