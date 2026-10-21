package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import com.berg.orbis.osm.OsmData;
import com.berg.orbis.osm.OsmDataProvider;
import com.berg.orbis.worldgen.Landmarks;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The landmarks a new world would get as advancements, for the world generator map before the world exists: the same
 * choice ({@link Landmarks#select}) over the same area ({@link Landmarks#searchBox}), from the local extract or the
 * downloaded regions where those hold the area, else from a small map query for the named places of the kinds it looks at.
 * Asked again a moment after the centre or the scale stops changing.
 */
final class LandmarkPreview {
    record Pin(String name, String kind, double lat, double lon) {}

    private static final long SETTLE_MS = 700;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-landmark-preview");
        t.setDaemon(true);
        return t;
    });

    private String wanted, asked;
    private long wantedAt;
    private double lat, lon, mpb;
    private volatile List<Pin> pins = List.of();
    private volatile boolean loading, failed;

    /** The world's centre and scale now (cheap: call it every frame). */
    void want(double lat0, double lon0, double metresPerBlock) {
        String key = String.format(Locale.ROOT, "%.4f,%.4f,%s", lat0, lon0, metresPerBlock);
        if (!key.equals(wanted)) {
            wanted = key;
            wantedAt = System.currentTimeMillis();
            lat = lat0;
            lon = lon0;
            mpb = metresPerBlock;
        }
        if (!wanted.equals(asked) && System.currentTimeMillis() - wantedAt >= SETTLE_MS) {
            asked = wanted;
            String key0 = wanted;
            double la = lat, lo = lon, m = mpb;
            loading = true;
            failed = false;
            POOL.execute(() -> {
                List<Pin> found;
                boolean bad = false;
                try {
                    found = find(la, lo, m);
                } catch (Exception e) {
                    System.err.println("[orbis] Landmarks for the map could not be looked up: " + e.getMessage());
                    found = List.of();
                    bad = true;
                }
                if (key0.equals(asked)) {
                    pins = found;
                    failed = bad;
                    loading = false;
                }
            });
        }
    }

    List<Pin> pins() {
        return pins;
    }

    boolean loading() {
        return loading;
    }

    boolean failed() {
        return failed;
    }

    private static List<Pin> find(double lat0, double lon0, double mpb) throws Exception {
        OrbisConfig live = OrbisMod.config();
        if (live == null || !live.landmarkAdvancements) return List.of();
        OrbisConfig c = new OrbisConfig();
        c.originLat = lat0;
        c.originLon = lon0;
        c.metersPerBlock = mpb;
        Path data = OrbisMod.dataDirFor(live);
        OsmDataProvider osm = new OsmDataProvider(data.resolve("osm-cache"), live.overpassUrls, live.overpassTimeoutSeconds,
                live.overpassConcurrentRequests, live.overpassRetries);
        osm.setLocalExtracts(com.berg.orbis.osm.extract.LocalExtractStore.get(data.resolve("extracts")), mpb);
        double[] b = Landmarks.searchBox(c);
        OsmData d;
        if (osm.isCached(b[0], b[1], b[2], b[3])) {
            d = osm.getData(b[0], b[1], b[2], b[3]);
        } else {
            String bbox = String.format(Locale.ROOT, "(%.6f,%.6f,%.6f,%.6f)", b[0], b[1], b[2], b[3]);
            // Only the named places of the kinds Landmarks.select considers.
            String q = "[out:json][timeout:" + live.overpassTimeoutSeconds + "];("
                    + "node[\"natural\"=\"peak\"][\"name\"]" + bbox + ";"
                    + "nwr[\"tourism\"~\"^(attraction|viewpoint|museum|gallery|zoo|aquarium|theme_park|artwork)$\"][\"name\"]" + bbox + ";"
                    + "nwr[\"historic\"~\"^(castle|fort|fortress|citadel|monument|memorial|ruins|city_gate|tower|ship|archaeological_site|manor|palace)$\"][\"name\"]" + bbox + ";"
                    + "nwr[\"aerialway\"=\"station\"][\"name\"]" + bbox + ";"
                    + "nwr[\"man_made\"=\"lighthouse\"][\"name\"]" + bbox + ";"
                    + "nwr[\"leisure\"=\"stadium\"][\"name\"]" + bbox + ";"
                    + "nwr[\"building\"~\"^(stadium|cathedral|church|castle|fort)$\"][\"name\"]" + bbox + ";"
                    + "nwr[\"amenity\"=\"place_of_worship\"][\"name\"]" + bbox + ";"
                    + ");out geom;";
            d = osm.getQuery(String.format(Locale.ROOT, "landmarks_%.5f_%.5f_%.5f_%.5f", b[0], b[1], b[2], b[3]), q);
        }
        List<Pin> out = new java.util.ArrayList<>();
        for (Landmarks.Landmark l : Landmarks.select(d, c)) out.add(new Pin(l.name(), l.kind(), l.lat(), l.lon()));
        return out;
    }
}
