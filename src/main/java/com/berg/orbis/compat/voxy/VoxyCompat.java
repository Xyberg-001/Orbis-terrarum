package com.berg.orbis.compat.voxy;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point for the optional Voxy support (client). This class never touches a Voxy type, so the mod loads without
 * Voxy; {@link VoxyFarView} (which does) is only loaded once Voxy is known to be present.
 */
public final class VoxyCompat {

    private VoxyCompat() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) return;
        try {
            VoxyFarView.register();
        } catch (Throwable t) {
            System.err.println("[orbis] Voxy is installed but could not be used (" + t + "); its far view shows generated chunks only");
        }
    }
}
