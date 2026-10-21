package com.berg.orbis.sky;

import com.berg.orbis.net.OrbisHttp;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Lightning over Europe, Africa, the Middle East and the Atlantic: the Lightning Imager of Meteosat Third Generation
 * (EUMETSAT, MTG-I at 0 degrees), whose accumulated flash area of the last few minutes EUMETSAT View draws as a map
 * every five minutes (about twenty minutes late). Its point query gives only colours, so a 16 x 16 pixel picture of the
 * 30 km round the place is asked for instead: any coloured pixel in it is lightning within about 15 km, where its
 * thunder is heard. An empty picture is under 100 bytes.
 */
final class MtgLightning {
    public static final String ATTRIBUTION = "EUMETSAT MTG Lightning Imager";
    private static final String URL = "https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap"
            + "&layers=mtg_fd:li_afa&styles=&crs=EPSG:4326&bbox=%.4f,%.4f,%.4f,%.4f&width=16&height=16&format=image/png&transparent=true";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "OrbisTerrarum/1.1 (Minecraft mod; https://modrinth.com/mod/orbis-terrarum)");
    /** Half the side of the picture, in degrees of latitude (about 15 km). */
    private static final double REACH_DEG = 0.14;

    private MtgLightning() {
    }

    /** Whether the satellite sees the place well (its disc, short of the edge where flashes are smeared). */
    static boolean covers(double lat, double lon) {
        return Math.abs(lat) < 65 && lon > -60 && lon < 60 && Math.toDegrees(Math.acos(Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lon)))) < 70;
    }

    /** Whether lightning flashed within about 15 km lately. */
    static boolean near(double lat, double lon) throws IOException, InterruptedException {
        double dLon = REACH_DEG / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        OrbisHttp.Response r = OrbisHttp.get(String.format(Locale.ROOT, URL, lat - REACH_DEG, lon - dLon, lat + REACH_DEG, lon + dLon), HEADERS, 30);
        if (r.status() != 200 || r.body() == null) return false;
        boolean[] lit = {false};
        MrmsRadar.rows(r.body(), 15, (row, line, bpp) -> {
            if (bpp != 4) return; // RGBA expected: anything else is not the map
            for (int i = 3; i < line.length; i += 4) if (line[i] != 0) lit[0] = true;
        });
        return lit[0];
    }
}
