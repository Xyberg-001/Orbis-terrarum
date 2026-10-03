package com.berg.orbis.client.map;

import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.config.WorldSettings;

/**
 * What the world generator map chose for the new world besides the area: its centre, which is also where players
 * spawn (block 0, 0), and the two pre-generation switches. Like the area ({@link MapSelectTool#previewShapes}) it goes
 * into the world at Create ({@code SpawnGate.takeSelection}), since leaving Customize with Done saves nothing when
 * only the map changed, and into Customize's own settings when those are saved. Cleared once the world is created.
 *
 * <p>Every new world starts at Mount Everest's summit (when the game starts, and again after each world is created),
 * not wherever the last world was: the world generator map opens there and a world created without choosing a place
 * is there. A preset, a search or a right-click on the map moves it.
 */
public final class PreviewChoices {

    /** Where a new world is until a place is chosen: Mount Everest. */
    private static final double[] DEFAULT_CENTRE = {
            com.berg.orbis.client.LocationPreset.MOUNT_EVEREST.lat, com.berg.orbis.client.LocationPreset.MOUNT_EVEREST.lon};

    private static double[] centre = DEFAULT_CENTRE.clone();
    private static Boolean generate, limit;

    private PreviewChoices() {
    }

    /** The new world's centre ([lat, lon]): the one chosen on the map or from the presets, else Mount Everest. */
    public static synchronized double[] centre() {
        return centre == null ? null : centre.clone();
    }

    public static synchronized void setCentre(double lat, double lon) {
        centre = new double[]{lat, lon};
    }

    /** Pre-generate the selected area on Create, or null while unchanged. */
    public static synchronized Boolean generate() {
        return generate;
    }

    public static synchronized void setGenerate(boolean on) {
        generate = on;
    }

    /** The hard limit, or null while unchanged. */
    public static synchronized Boolean limit() {
        return limit;
    }

    public static synchronized void setLimit(boolean on) {
        limit = on;
    }

    public static synchronized void clear() {
        centre = DEFAULT_CENTRE.clone();
        generate = null;
        limit = null;
    }

    /** Puts the choices into a new world's settings; true when that changed them. */
    public static synchronized boolean applyTo(WorldSettings s) {
        boolean changed = false;
        if (centre != null && (s.originLat != centre[0] || s.originLon != centre[1] || s.customSpawn)) {
            s.originLat = centre[0];
            s.originLon = centre[1];
            s.customSpawn = false;
            changed = true;
        }
        if (generate != null && s.pregenOnCreate != generate) {
            s.pregenOnCreate = generate;
            changed = true;
        }
        if (limit != null && s.pregenHardLimit != limit) {
            s.pregenHardLimit = limit;
            changed = true;
        }
        return changed;
    }

    /** The same for Customize's settings (they also become the defaults of the next world). */
    public static synchronized void applyTo(OrbisConfig c) {
        if (centre != null) {
            c.originLat = centre[0];
            c.originLon = centre[1];
            c.customSpawn = false;
        }
        if (generate != null) c.pregenOnCreate = generate;
        if (limit != null) c.pregenHardLimit = limit;
    }

    /** Where the map opens and players spawn: the chosen centre (Mount Everest until one is chosen). */
    public static synchronized double[] centreOr(OrbisConfig c) {
        if (centre != null) return centre.clone();
        return c.customSpawn ? new double[]{c.spawnLat, c.spawnLon} : new double[]{c.originLat, c.originLon};
    }
}
