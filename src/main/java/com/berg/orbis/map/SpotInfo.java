package com.berg.orbis.map;

import com.berg.orbis.biome.BiomeClassifier;
import com.berg.orbis.feature.BuildingFeature;
import com.berg.orbis.feature.RegionRaster;
import com.berg.orbis.feature.RoadFeature;
import com.berg.orbis.feature.WaterFeature;
import com.berg.orbis.worldgen.WorldModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Orbis knows about one column of the world: its real elevation and the Y it becomes, the climate, and what the map data puts there
 * (land cover, road, water, building). Each line is a label and a value, tab-separated: the world map's What's here card shows them, and
 * /orbis here joins them. Only map data already loaded is used (nothing is downloaded for a look).
 */
public final class SpotInfo {
    private SpotInfo() {}

    /** {@code full}: the generator's own details too (decoration, photo ground class), for the command. */
    public static List<String> describe(WorldModel model, int x, int z, boolean full) {
        List<String> out = new ArrayList<>();
        double elev = model.elevation(x, z);
        if (Double.isNaN(elev)) {
            out.add("Elevation\tunknown here (sea level, Y " + model.cfg().seaLevelY + ")");
        } else {
            double shift = model.vertical().shiftBlocks(x, z);
            out.add("Elevation\t" + String.format(Locale.ROOT, "%,.1f m, at Y %d%s", elev, model.blockY(elev, x, z),
                    shift > 0 ? String.format(Locale.ROOT, " (lowered %.0f to fit)", shift) : ""));
        }
        BiomeClassifier.Climate climate = model.climate(x, z, elev);
        out.add("Climate\t" + String.format(Locale.ROOT, "%s, %.1f °C a year%s", words(String.valueOf(climate.zone())), climate.meanTempC(), climate.snowy() ? ", snowy" : ""));
        RegionRaster r = model.regions() == null ? null : model.regions().get(model.regions().regionCoord(x), model.regions().regionCoord(z), false);
        if (r == null) {
            out.add("Map data\t" + (model.regions() == null ? "off in this world" : "not loaded here yet (it loads as the land generates)"));
            return out;
        }
        int idx = r.index(x, z);
        if (idx < 0) return out;
        out.add("Land\t" + words(String.valueOf(r.landCoverAt(idx))));
        RoadFeature rf = r.roadAt(idx);
        if (rf != null) out.add("Road\t" + words(rf.highway != null ? rf.highway : String.valueOf(rf.kind)) + (rf.name != null ? " ‘" + rf.name + "’" : ""));
        WaterFeature wf = r.waterAt(idx);
        if (wf != null) out.add("Water\t" + words(String.valueOf(wf.kind)) + (wf.name != null ? " ‘" + wf.name + "’" : ""));
        if (r.hasCoastline && r.isSea(idx)) out.add("Sea\tyes");
        BuildingFeature bf = r.buildingAt(idx);
        if (bf != null) {
            out.add("Building\t" + words(String.valueOf(bf.type)) + (bf.name != null ? " ‘" + bf.name + "’" : "")
                    + ", " + bf.heightBlocks + " blocks high (" + bf.heightSource + "), " + words(String.valueOf(bf.roofShape)) + " roof"
                    + (full ? " of " + bf.roof.getBlock().getDescriptionId().replace("block.minecraft.", "") : ""));
        }
        if (full) {
            out.add("Decoration\t" + r.decorAt(idx));
            out.add("Photo ground\t" + com.berg.orbis.imagery.GroundClass.byCode(r.groundClassAt(idx)));
        }
        return out;
    }

    /** RESIDENTIAL_AREA or residential_area to "residential area". */
    private static String words(String s) {
        return s.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
