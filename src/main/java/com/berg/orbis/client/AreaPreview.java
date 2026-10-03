package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.net.AreaOutline;
import com.berg.orbis.net.Geocoder;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.mc.McClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The World tab's area preview: before a world exists, which chunks {@code /orbis pregen area <place>} (or a
 * radius sweep) would generate at the origin and scale being set up. {@link #plan} works the area out (by default
 * the city or municipality around the chosen location); {@link AreaPreviewScreen} shows it in the game, and
 * {@link #openInBrowser} writes the same preview as a web page.
 */
public final class AreaPreview {
    /** Radius used when the chosen location is not inside any named area (at sea) or the lookup fails. */
    static final double FALLBACK_KM = 5;

    private AreaPreview() {}

    /**
     * Works out the preview for {@code area}: empty means the municipality or city the origin lies in, a number
     * means a radius in km around the origin, anything else is a place name. Runs off the render thread.
     */
    public static CompletableFuture<PreviewPlan> plan(String area, double originLat, double originLon, double metersPerBlock, String projection) {
        return plan(area, originLat, originLon, metersPerBlock, projection, o -> {});
    }

    /** As above; {@code onOutline} hears when the place is found and its chunks are being worked out (any thread). */
    public static CompletableFuture<PreviewPlan> plan(String area, double originLat, double originLon, double metersPerBlock, String projection,
                                                      java.util.function.Consumer<AreaOutline> onOutline) {
        CoordinateMapper mapper = new CoordinateMapper(originLat, originLon, metersPerBlock, CoordinateMapper.Projection.of(projection));
        String q = area == null ? "" : area.trim();
        Double radiusKm = parseKm(q);
        CompletableFuture<AreaOutline> outline;
        boolean[] fellBack = {false};
        if (radiusKm != null) {
            outline = CompletableFuture.completedFuture(circle(originLat, originLon, radiusKm));
        } else if (q.isEmpty()) {
            outline = Geocoder.areaAt(originLat, originLon, 10).thenApply(AreaOutline::largestOnly)
                    .exceptionally(e -> {
                        fellBack[0] = true;
                        return circle(originLat, originLon, FALLBACK_KM);
                    });
        } else {
            outline = Geocoder.lookupArea(q).thenApply(AreaOutline::largestOnly);
        }
        return outline.thenApplyAsync(o -> {
            onOutline.accept(o);
            return PreviewPlan.of(o, radiusKm != null ? radiusKm : fellBack[0] ? FALLBACK_KM : null, mapper,
                    projection == null ? "equirectangular" : projection);
        });
    }

    static Double parseKm(String q) {
        String s = q.toLowerCase(Locale.ROOT).replace("km", "").trim();
        try {
            double v = Double.parseDouble(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A circle of real kilometres around the origin, as an outline. */
    static AreaOutline circle(double lat, double lon, double km) {
        double[][] ring = new double[96][];
        double dLat = km * 1000 / 111_320.0, dLon = km * 1000 / (111_320.0 * Math.cos(Math.toRadians(lat)));
        for (int i = 0; i < ring.length; i++) {
            double a = 2 * Math.PI * i / ring.length;
            ring[i] = new double[]{lat + dLat * Math.sin(a), lon + dLon * Math.cos(a)};
        }
        return new AreaOutline(String.format(Locale.ROOT, "%s km around the chosen location", PreviewPlan.fmt(km)), List.<double[][]>of(ring),
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);
    }

    /** Writes the preview as a web page into the config folder and opens it in the browser. */
    public static Path openInBrowser(PreviewPlan plan) throws IOException {
        Path page = write(plan, OrbisMod.dataDir().resolve("preview"));
        McClient.openPath(page.toAbsolutePath());
        return page;
    }

    /** Writes index.html and preview.js into {@code dir} (without opening anything). */
    public static Path write(PreviewPlan plan, Path dir) throws IOException {
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < plan.rows.length; i++) {
            if (i > 0) rows.append(',');
            rows.append('[');
            int[] r = plan.rows[i];
            for (int k = 0; k < r.length; k++) rows.append(k == 0 ? "" : ",").append(r[k]);
            rows.append(']');
        }
        rows.append(']');
        StringBuilder poly = new StringBuilder("[");
        for (int p = 0; p < plan.outline.polygons().size(); p++) {
            double[][] ring = plan.outline.polygons().get(p);
            poly.append(p == 0 ? "[" : ",[");
            int step = Math.max(1, ring.length / 1500);
            for (int i = 0; i < ring.length; i += step) {
                poly.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.6f,%.6f]", ring[i][0], ring[i][1]));
            }
            poly.append(']');
        }
        poly.append(']');
        CoordinateMapper m = plan.mapper;
        String js = "window.orbisPreview({"
                + "\"name\":" + json(plan.outline.name())
                + ",\"command\":" + json(plan.command)
                + String.format(Locale.ROOT, ",\"lat0\":%.7f,\"lon0\":%.7f,\"mpb\":%s", m.originLat(), m.originLon(), PreviewPlan.fmt(m.metersPerBlock()))
                + ",\"projection\":" + json(plan.projection)
                + String.format(Locale.ROOT, ",\"km2\":%.1f,\"chunks\":%d,\"regionFiles\":%d,\"kbPerChunk\":%.1f", plan.outline.totalKm2(), plan.chunks,
                plan.regionFiles, PreviewPlan.KB_PER_CHUNK)
                + ",\"firstRow\":" + plan.firstRow
                + ",\"rows\":" + rows
                + ",\"outline\":" + poly
                + "});\n";
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("preview.js"), js, StandardCharsets.UTF_8);
        Path page = dir.resolve("index.html");
        try (InputStream in = AreaPreview.class.getResourceAsStream("/assets/orbisterrarum/area-preview.html")) {
            if (in == null) throw new IOException("area-preview.html missing from the mod jar");
            Files.write(page, in.readAllBytes());
        }
        return page;
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else if (c == '<') b.append("\\u003c");
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
