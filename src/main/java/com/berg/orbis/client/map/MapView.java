package com.berg.orbis.client.map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A web map drawn into a rectangle of a screen (the world map's {@link MapCanvas}, the world preview's map), as far
 * as a tool drawing over it needs: converting places to and from GUI positions, and drawing in physical pixels.
 */
public interface MapView {
    /** The map's rectangle in GUI coordinates. */
    int left();

    int top();

    int right();

    int bottom();

    /** Physical pixels per GUI pixel. */
    int guiScale();

    /** GUI coordinates of a place (may lie outside the map). */
    double[] gui(double lat, double lon);

    /** Latitude and longitude under a GUI position. */
    double[] latLonAt(double x, double y);

    /** Physical-pixel position of a place relative to the map's corner, for drawing between begin/endOverlay. */
    double[] phys(double lat, double lon);

    /** Clip to the map and draw in physical pixels from its corner. */
    void beginOverlay(GuiGraphicsExtractor g);

    void endOverlay(GuiGraphicsExtractor g);
}
