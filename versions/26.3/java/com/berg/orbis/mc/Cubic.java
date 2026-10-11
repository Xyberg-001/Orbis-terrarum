package com.berg.orbis.mc;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Cubic worlds (the Cubic Worlds mod, optional): when it is installed, cubic levels made by Orbis's generator get their cubes from
 * {@link com.berg.orbis.cubic.OrbisCubeGenerator}. Without it nothing here loads a Cubic Worlds class.
 */
public final class Cubic {
    private Cubic() {
    }

    /** Called at startup. */
    public static void init() {
        if (FabricLoader.getInstance().isModLoaded("cubicworlds")) {
            com.berg.orbis.cubic.OrbisCubeGenerator.register();
            com.berg.orbis.cubic.CubeMapDrawer.register();
            com.berg.orbis.cubic.CubicPregen.register();
        }
    }
}
