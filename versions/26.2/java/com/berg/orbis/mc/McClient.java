package com.berg.orbis.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.util.Util;

import java.nio.file.Path;

/** Minecraft 26.2 versions of client calls whose shape differs between Minecraft versions. */
public final class McClient {

    private McClient() {
    }

    /** Opens a file with the system's default program (the browser for a web page). */
    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }

    /** A smooth (linear), edge-clamped texture sampler. */
    public static GpuSampler linearClampSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
