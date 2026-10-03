package com.berg.orbis.compat.dh;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point for the optional Distant Horizons support. This class never touches a Distant Horizons type, so the
 * mod loads without DH installed; {@link DhIntegration} (which does) is only loaded once DH is known to be present.
 */
public final class DhCompat {

    private DhCompat() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) return;
        try {
            DhIntegration.register();
        } catch (Throwable t) {
            System.err.println("[orbis] Distant Horizons is installed but its API could not be used (" + t
                    + "); Distant Horizons will use its own generator in Orbis worlds");
        }
    }
}
