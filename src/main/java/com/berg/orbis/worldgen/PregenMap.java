package com.berg.orbis.worldgen;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.osm.CoordinateMapper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A picture of what the world has on disk: one pixel per chunk, green where the chunk is complete, amber where
 * only an early stage was saved (the ring a finished chunk drags along, or a sweep interrupted), dark where
 * there is nothing. Over it: the names of the city's places the way a street map shows them (bigger for the
 * city, smaller for neighbourhoods, fewer where they would crowd), each coloured by whether it is generated,
 * the outline of the area being swept, the row the sweep has reached, the origin and a 100-chunk grid.
 * Written as {@code orbis-pregen/map.png} in the world folder next to {@code map.html}, which shows the picture
 * with the lists of places done, partly done and not yet done and reloads both every 30 s.
 * {@code /orbis pregen map} writes it on demand; a running sweep writes it every ten minutes.
 *
 * <p>Reading the status of every chunk means inflating every chunk of every region file (a few tens of
 * microseconds each), so region files are cached by size and modification time and only the ones the sweep
 * is still writing are read again.
 */
public final class PregenMap {
    private static final Color BG = new Color(24, 26, 32);
    private static final Color FULL = new Color(63, 185, 80);
    private static final Color PARTIAL = new Color(176, 128, 32);
    private static final Color GRID = new Color(60, 64, 76);
    private static final Color OUTLINE = new Color(200, 210, 230);
    private static final Color ROW_DONE = new Color(90, 160, 255);
    private static final Color ROW_DISK = new Color(255, 255, 255);
    private static final Color ORIGIN = new Color(255, 60, 60);
    private static final Color HALO = new Color(12, 14, 18);
    // Index: 0 generated, 1 partly, 2 not yet, 3 outside the area being swept (orientation only).
    private static final Color[] DOT = {new Color(110, 230, 125), new Color(245, 185, 60), new Color(240, 95, 95), new Color(110, 116, 128)};
    private static final Color[] TEXT = {new Color(200, 255, 205), new Color(255, 222, 150), new Color(255, 180, 175), new Color(150, 156, 168)};
    private static final int[] FONT_SIZE = {26, 21, 18, 16, 14, 13, 12, 11};
    private static final byte[] FULL_TAG = "minecraft:full".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_KEY = "Status".getBytes(StandardCharsets.US_ASCII);

    /** Per region file: presence and completeness of its 1024 chunks, valid for the file size and mtime it was read at. */
    private record Region(long size, FileTime mtime, byte[] state) {} // 0 = absent, 1 = partial, 2 = full

    /** What a render found: chunk counts and the places by status. */
    public record Result(long full, long partial, int widthChunks, int heightChunks,
                         List<MapPlaces.Place> done, List<MapPlaces.Place> partly, List<MapPlaces.Place> notYet) {
        public String summary() {
            return String.format(Locale.ROOT, "%,d complete, %,d partial, %dx%d chunks; places: %d done, %d partly, %d not yet",
                    full, partial, widthChunks, heightChunks, done.size(), partly.size(), notYet.size());
        }
    }

    private static final Map<Path, Region> CACHE = new HashMap<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private PregenMap() {}

    /** The world's overworld region folder (26.2 keeps every dimension under {@code dimensions/}). */
    public static Path regionDir(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path modern = root.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        return Files.isDirectory(modern) ? modern : root.resolve("region");
    }

    public static Path outputDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("orbis-pregen");
    }

    /** Writes the map in the background (one at a time); the reply names the file. */
    public static Component writeNow(ServerLevel level) {
        return writeNow(level, null);
    }

    /** As above, with an outline to draw instead of the running or last sweep's. */
    public static Component writeNow(ServerLevel level, AreaSweep outline) {
        if (!BUSY.compareAndSet(false, true)) return Component.literal("The map is being written already.");
        Path out = outputDir(level).resolve("map.html").toAbsolutePath().normalize();
        MinecraftServer server = level.getServer();
        Thread t = new Thread(() -> {
            try {
                Result r = write(level, outline);
                System.out.println("[orbis] Pregen map written: " + out + " (" + r.summary() + ")");
                Component done = chatSummary(r, !server.isDedicatedServer());
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(done, false));
            } catch (Exception e) {
                System.err.println("[orbis] Pregen map failed: " + e);
                e.printStackTrace();
            } finally {
                BUSY.set(false);
            }
        }, "Orbis-Pregen-Map");
        t.setDaemon(true);
        t.start();
        MutableComponent msg = Component.literal("Drawing the map... ").withStyle(ChatFormatting.GRAY);
        if (!server.isDedicatedServer()) {
            // Singleplayer / LAN host: the button opens it in the browser (see client.OpenMapCommand).
            msg.append(Component.literal("it opens with the button when ready. File: ").withStyle(ChatFormatting.GRAY));
        } else {
            msg.append(Component.literal("open this file in a browser on the server's computer: ").withStyle(ChatFormatting.GRAY));
        }
        msg.append(Component.literal(out.toString()).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(out.toString()))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Click to copy the path")))));
        return msg;
    }

    /** A chat button that runs the client command opening map.html in the player's own browser. */
    private static MutableComponent openButton() {
        return Component.literal("[Open map]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("orbismap"))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Open the map in your browser"))));
    }

    /** Three short lines for chat: counts, the open button (for the host) and the biggest places not done yet. */
    private static Component chatSummary(Result r, boolean button) {
        MutableComponent m = Component.literal("Map ready: ").withStyle(ChatFormatting.GOLD);
        m.append(Component.literal(r.done().size() + " places done").withStyle(ChatFormatting.GREEN));
        m.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
        m.append(Component.literal(r.partly().size() + " partly").withStyle(ChatFormatting.YELLOW));
        m.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
        m.append(Component.literal(r.notYet().size() + " not yet").withStyle(ChatFormatting.RED));
        if (button) m.append(Component.literal("  ")).append(openButton());
        if (!r.notYet().isEmpty()) {
            StringBuilder names = new StringBuilder();
            int shown = 0;
            for (MapPlaces.Place p : r.notYet()) {
                if (p.rank() > 4 || shown >= 10) break;
                names.append(shown++ == 0 ? "" : ", ").append(p.name());
            }
            if (shown > 0) m.append(Component.literal("\n  Not yet: " + names + (r.notYet().size() > shown ? ", ..." : "")).withStyle(ChatFormatting.GRAY));
        }
        return m;
    }

    /** Called from the sweep's tick; writes in the background if no write is running. */
    public static void writeInBackground(ServerLevel level) {
        if (!BUSY.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                write(level, null);
            } catch (Exception e) {
                System.err.println("[orbis] Pregen map failed: " + e);
            } finally {
                BUSY.set(false);
            }
        }, "Orbis-Pregen-Map");
        t.setDaemon(true);
        t.start();
    }

    /** Gathers what the world knows and renders into its orbis-pregen folder. */
    static Result write(ServerLevel level, AreaSweep outlineOverride) throws IOException {
        PregenTask task = PregenTask.active();
        // The outline: the one asked for, else the running sweep's, else the last sweep's of this session.
        AreaSweep sweep = outlineOverride != null ? outlineOverride : task != null && task.sweep() != null ? task.sweep() : PregenTask.lastSweep();
        boolean running = task != null && sweep != null && task.sweep() == sweep;
        WorldModel model = OrbisMod.model();
        CoordinateMapper mapper = model == null ? null : model.mapper();
        double mpb = model == null ? 1.0 : model.cfg().metersPerBlock;
        return render(regionDir(level), outputDir(level), sweep,
                running ? task.completedRow() : Integer.MIN_VALUE, running ? task.flushedRow() : Integer.MIN_VALUE,
                mapper, mpb, OrbisMod.dataDir().resolve("extracts"));
    }

    /**
     * Renders map.png, places.js and map.html into {@code outDir}. Independent of a running server, so a
     * probe can draw any world folder. {@code rowDone}/{@code rowDisk} are Integer.MIN_VALUE when no sweep runs;
     * without a mapper there are no place names.
     */
    public static Result render(Path regions, Path outDir, AreaSweep sweep, int rowDone, int rowDisk,
                                CoordinateMapper mapper, double metersPerBlock, Path extractsRoot) throws IOException {
        Map<Long, Byte> cells = new HashMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long full = 0, partial = 0;
        if (Files.isDirectory(regions)) {
            List<Path> list;
            try (var files = Files.list(regions)) {
                list = files.filter(f -> f.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList();
            }
            // A few threads, not all of them: during a sweep the worker pool needs the rest.
            java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            List<byte[]> states;
            try {
                states = pool.submit(() -> list.parallelStream().map(f -> {
                    try {
                        return regionState(f);
                    } catch (IOException e) {
                        return null;
                    }
                }).toList()).get();
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                throw new IOException("region scan failed", e);
            } finally {
                pool.shutdown();
            }
            for (int fi = 0; fi < list.size(); fi++) {
                {
                    String name = list.get(fi).getFileName().toString();
                    String[] parts = name.substring(2, name.length() - 4).split("\\.");
                    int rx = Integer.parseInt(parts[0]), rz = Integer.parseInt(parts[1]);
                    byte[] state = states.get(fi);
                    if (state == null) continue;
                    for (int i = 0; i < 1024; i++) {
                        if (state[i] == 0) continue;
                        int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                        cells.put(key(cx, cz), state[i]);
                        minX = Math.min(minX, cx);
                        maxX = Math.max(maxX, cx);
                        minZ = Math.min(minZ, cz);
                        maxZ = Math.max(maxZ, cz);
                        if (state[i] == 2) full++;
                        else partial++;
                    }
                }
            }
        }
        if (sweep != null) {
            for (double[][] poly : sweep.polygons()) {
                for (double[] p : poly) {
                    minX = Math.min(minX, (int) Math.floor(p[0] / 16));
                    maxX = Math.max(maxX, (int) Math.floor(p[0] / 16));
                    minZ = Math.min(minZ, (int) Math.floor(p[1] / 16));
                    maxZ = Math.max(maxZ, (int) Math.floor(p[1] / 16));
                }
            }
        }
        if (cells.isEmpty() && sweep == null) {
            minX = -64; maxX = 64; minZ = -64; maxZ = 64;
        }
        minX -= 8; maxX += 8; minZ -= 8; maxZ += 8;
        int scale = (maxX - minX + 1) * 2 > 3200 || (maxZ - minZ + 1) * 2 > 3200 ? 1 : 2;
        // Wide enough for the legend even when only a few chunks exist.
        if ((maxX - minX + 1) * scale < 900) maxX = minX + 900 / scale;
        int w = maxX - minX + 1, h = maxZ - minZ + 1;

        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (Map.Entry<Long, Byte> e : cells.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            g.setColor(e.getValue() == 2 ? FULL : PARTIAL);
            g.fillRect((cx - minX) * scale, (cz - minZ) * scale, scale, scale);
        }
        g.setColor(GRID);
        for (int gx = Math.floorDiv(minX, 100) * 100; gx <= maxX; gx += 100) g.drawLine((gx - minX) * scale, 0, (gx - minX) * scale, img.getHeight());
        for (int gz = Math.floorDiv(minZ, 100) * 100; gz <= maxZ; gz += 100) g.drawLine(0, (gz - minZ) * scale, img.getWidth(), (gz - minZ) * scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (sweep != null) {
            g.setColor(OUTLINE);
            g.setStroke(new BasicStroke(1.5f));
            for (double[][] poly : sweep.polygons()) {
                for (int i = 0; i < poly.length; i++) {
                    double[] a = poly[i], b = poly[(i + 1) % poly.length];
                    g.drawLine((int) Math.round((a[0] / 16 - minX) * scale), (int) Math.round((a[1] / 16 - minZ) * scale),
                            (int) Math.round((b[0] / 16 - minX) * scale), (int) Math.round((b[1] / 16 - minZ) * scale));
                }
            }
            if (rowDone != Integer.MIN_VALUE) {
                g.setStroke(new BasicStroke(1f));
                g.setColor(ROW_DONE);
                g.drawLine(0, (rowDone + 1 - minZ) * scale, img.getWidth(), (rowDone + 1 - minZ) * scale);
                g.setColor(ROW_DISK);
                g.drawLine(0, (rowDisk + 1 - minZ) * scale, img.getWidth(), (rowDisk + 1 - minZ) * scale);
            }
        }
        g.setColor(ORIGIN);
        g.setStroke(new BasicStroke(2f));
        int ox = (0 - minX) * scale, oz = (0 - minZ) * scale;
        g.drawLine(ox - 8, oz, ox + 8, oz);
        g.drawLine(ox, oz - 8, ox, oz + 8);

        // ---- place names --------------------------------------------------------------------------------
        List<MapPlaces.Place> done = new ArrayList<>(), partly = new ArrayList<>(), notYet = new ArrayList<>();
        List<MapPlaces.Place> places = mapper == null ? List.of()
                : MapPlaces.load(extractsRoot, mapper, metersPerBlock, minX, minZ, maxX, maxZ);
        List<Rectangle2D> taken = new ArrayList<>();
        taken.add(new Rectangle2D.Double(0, 0, 900, 44)); // the legend
        // Places inside the area first (they win the space), then the ones around it in grey, for orientation
        // only: they are not part of the sweep and are left out of the lists.
        List<MapPlaces.Place> outside = new ArrayList<>();
        for (MapPlaces.Place p : places) {
            if (!inside(sweep, p)) {
                outside.add(p);
                continue;
            }
            int status = status(cells, p);
            (status == 0 ? done : status == 1 ? partly : notYet).add(p);
            drawLabel(g, p, status, (p.cx() - minX) * scale, (p.cz() - minZ) * scale, img.getWidth(), img.getHeight(), taken);
        }
        for (MapPlaces.Place p : outside) {
            drawLabel(g, p, 3, (p.cx() - minX) * scale, (p.cz() - minZ) * scale, img.getWidth(), img.getHeight(), taken);
        }

        // ---- legend -------------------------------------------------------------------------------------
        double gridKm = 1.6 * metersPerBlock;
        String line1 = String.format(Locale.ROOT, "%,d chunks complete (green)   %,d partly (amber)   grid 100 chunks = %.1f km   red cross = world origin%s",
                full, partial, gridKm, rowDone != Integer.MIN_VALUE ? "   blue line = rows generated, white = rows on disk" : "");
        String line2 = places.isEmpty() ? "No place names: no local map extract covers this area."
                : String.format(Locale.ROOT, "Place names: green = generated (%d)   amber = partly (%d)   red = not yet (%d)%s", done.size(), partly.size(), notYet.size(),
                sweep != null ? "   grey = outside the area" : "");
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
        g.setColor(HALO);
        g.fillRect(0, 0, Math.min(img.getWidth(), 900), 42);
        g.setComposite(AlphaComposite.SrcOver);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(Color.WHITE);
        g.drawString(line1, 8, 17);
        g.drawString(line2, 8, 35);
        g.dispose();

        Files.createDirectories(outDir);
        Path tmp = outDir.resolve("map.png.tmp");
        ImageIO.write(img, "png", tmp.toFile());
        Files.move(tmp, outDir.resolve("map.png"), StandardCopyOption.REPLACE_EXISTING);
        Result result = new Result(full, partial, w, h, done, partly, notYet);
        double[] bounds = mapper == null ? null : writeOverlay(outDir, cells, mapper, minX, minZ, maxX, maxZ);
        writePage(outDir, result, mapper, sweep, bounds);
        return result;
    }

    /** Inside the area's outline (always true without one). */
    private static boolean inside(AreaSweep sweep, MapPlaces.Place p) {
        if (sweep == null) return true;
        int cx = (int) Math.floor(p.cx()), cz = (int) Math.floor(p.cz());
        if (cz < sweep.firstRow() || cz > sweep.lastRow()) return false;
        for (int[] range : sweep.rowRanges(cz)) if (cx >= range[0] && cx <= range[1]) return true;
        return false;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** 0 = the chunk the place sits in is complete, 1 = complete chunks close by, 2 = nothing yet. */
    private static int status(Map<Long, Byte> cells, MapPlaces.Place p) {
        int cx = (int) Math.floor(p.cx()), cz = (int) Math.floor(p.cz());
        Byte here = cells.get(key(cx, cz));
        if (here != null && here == 2) return 0;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                Byte b = cells.get(key(cx + dx, cz + dz));
                if (b != null && b == 2) return 1;
            }
        }
        return 2;
    }

    /**
     * A dot and the name next to it with a dark halo, as a street map does, in the first of four positions
     * (right, left, above, below) that overlaps no label already drawn; a place with no free position is left
     * out of the picture (it stays in the lists).
     */
    private static void drawLabel(Graphics2D g, MapPlaces.Place p, int status, double px, double py, int imgW, int imgH, List<Rectangle2D> taken) {
        if (px < 0 || py < 0 || px >= imgW || py >= imgH) return;
        int size = FONT_SIZE[Math.min(p.rank(), FONT_SIZE.length - 1)];
        Font font = new Font("SansSerif", p.rank() <= 3 ? Font.BOLD : Font.PLAIN, size);
        String text = p.rank() == 0 ? p.name().toUpperCase(Locale.ROOT) : p.name();
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        Rectangle2D vb = gv.getVisualBounds();
        double tw = vb.getWidth(), th = vb.getHeight(), gap = 5;
        double[][] anchors = {
                {px + gap, py - (vb.getY() + th / 2)},              // right
                {px - gap - tw - vb.getX(), py - (vb.getY() + th / 2)}, // left
                {px - tw / 2 - vb.getX(), py - gap - (vb.getY() + th)}, // above
                {px - tw / 2 - vb.getX(), py + gap - vb.getY()}         // below
        };
        for (double[] a : anchors) {
            Rectangle2D box = new Rectangle2D.Double(a[0] + vb.getX() - 3, a[1] + vb.getY() - 3, tw + 6, th + 6);
            if (box.getMinX() < 0 || box.getMinY() < 0 || box.getMaxX() > imgW || box.getMaxY() > imgH) continue;
            boolean free = true;
            for (Rectangle2D r : taken) {
                if (r.intersects(box)) {
                    free = false;
                    break;
                }
            }
            if (!free) continue;
            taken.add(box);
            double dot = p.rank() <= 3 ? 4.5 : 3.5;
            taken.add(new Rectangle2D.Double(px - dot - 1, py - dot - 1, 2 * dot + 2, 2 * dot + 2));
            g.setColor(HALO);
            g.fill(new java.awt.geom.Ellipse2D.Double(px - dot - 1.5, py - dot - 1.5, 2 * dot + 3, 2 * dot + 3));
            g.setColor(DOT[status]);
            g.fill(new java.awt.geom.Ellipse2D.Double(px - dot, py - dot, 2 * dot, 2 * dot));
            Shape outline = gv.getOutline((float) a[0], (float) a[1]);
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(HALO);
            g.draw(outline);
            g.setColor(TEXT[status]);
            g.fill(outline);
            return;
        }
    }

    /**
     * The generated chunks as a see-through picture in Web Mercator, the projection of every web map, so that a
     * browser map can lay it over real street maps and satellite photos exactly: each output pixel is mapped
     * back to latitude and longitude, then through the world's own projection to its chunk. Complete chunks are
     * green, partly generated ones amber, everything else transparent. Returns {south, west, north, east}.
     */
    private static double[] writeOverlay(Path outDir, Map<Long, Byte> cells, CoordinateMapper mapper,
                                         int minX, int minZ, int maxX, int maxZ) throws IOException {
        double s = Double.MAX_VALUE, n = -Double.MAX_VALUE, w = Double.MAX_VALUE, e = -Double.MAX_VALUE;
        // The rectangle's edge, sampled: with a transverse Mercator world its image is not a lat/lon rectangle.
        for (int i = 0; i <= 64; i++) {
            double t = i / 64.0;
            double bx = (minX + t * (maxX + 1 - minX)) * 16, bz = (minZ + t * (maxZ + 1 - minZ)) * 16;
            for (double[] b : new double[][]{{bx, minZ * 16.0}, {bx, (maxZ + 1) * 16.0}, {minX * 16.0, bz}, {(maxX + 1) * 16.0, bz}}) {
                double[] ll = mapper.toLatLonExact(b[0], b[1]);
                s = Math.min(s, ll[0]);
                n = Math.max(n, ll[0]);
                w = Math.min(w, ll[1]);
                e = Math.max(e, ll[1]);
            }
        }
        double yS = mercY(s), yN = mercY(n);
        int width = Math.max(64, Math.min(4096, maxX - minX + 1));
        int height = (int) Math.max(64, Math.min(6144, Math.round(width * (yN - yS) / Math.toRadians(e - w))));
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int green = 0xFF000000 | FULL.getRGB() & 0xFFFFFF, amber = 0xFF000000 | PARTIAL.getRGB() & 0xFFFFFF;
        for (int v = 0; v < height; v++) {
            double lat = Math.toDegrees(Math.atan(Math.sinh(yN - (v + 0.5) / height * (yN - yS))));
            for (int u = 0; u < width; u++) {
                double lon = w + (u + 0.5) / width * (e - w);
                double[] b = mapper.toBlockExact(lat, lon);
                Byte st = cells.get(key((int) Math.floor(b[0] / 16), (int) Math.floor(b[1] / 16)));
                if (st != null) img.setRGB(u, v, st == 2 ? green : amber);
            }
        }
        Files.createDirectories(outDir);
        Path tmp = outDir.resolve("overlay.png.tmp");
        ImageIO.write(img, "png", tmp.toFile());
        Files.move(tmp, outDir.resolve("overlay.png"), StandardCopyOption.REPLACE_EXISTING);
        return new double[]{s, w, n, e};
    }

    private static double mercY(double latDeg) {
        double phi = Math.toRadians(latDeg);
        return Math.log(Math.tan(Math.PI / 4 + phi / 2));
    }

    /** places.js (everything the page shows, reloaded by it) and map.html. */
    private static void writePage(Path outDir, Result r, CoordinateMapper mapper, AreaSweep sweep, double[] bounds) throws IOException {
        StringBuilder js = new StringBuilder("window.orbisMap({\"summary\":").append(json(r.summary()))
                .append(",\"time\":").append(json(java.time.LocalTime.now().withNano(0).toString()));
        js.append(",\"bounds\":");
        if (bounds == null) js.append("null");
        else js.append(String.format(Locale.ROOT, "[%.6f,%.6f,%.6f,%.6f]", bounds[0], bounds[1], bounds[2], bounds[3]));
        js.append(",\"outline\":[");
        if (sweep != null && mapper != null) {
            boolean firstRing = true;
            for (double[][] poly : sweep.polygons()) {
                js.append(firstRing ? "[" : ",[");
                firstRing = false;
                int step = Math.max(1, poly.length / 600);
                boolean firstPt = true;
                for (int i = 0; i < poly.length; i += step) {
                    double[] ll = mapper.toLatLonExact(poly[i][0], poly[i][1]);
                    js.append(firstPt ? "" : ",").append(String.format(Locale.ROOT, "[%.6f,%.6f]", ll[0], ll[1]));
                    firstPt = false;
                }
                js.append(']');
            }
        }
        js.append(']');
        appendList(js, "done", r.done(), mapper);
        appendList(js, "partly", r.partly(), mapper);
        appendList(js, "notYet", r.notYet(), mapper);
        js.append("});\n");
        Path tmp = outDir.resolve("places.js.tmp");
        Files.writeString(tmp, js.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, outDir.resolve("places.js"), StandardCopyOption.REPLACE_EXISTING);
        try (var in = PregenMap.class.getResourceAsStream("/assets/orbisterrarum/pregen-map.html")) {
            if (in == null) throw new IOException("pregen-map.html missing from the mod jar");
            Files.write(outDir.resolve("map.html"), in.readAllBytes());
        }
    }

    private static void appendList(StringBuilder js, String name, List<MapPlaces.Place> list, CoordinateMapper mapper) {
        js.append(",\"").append(name).append("\":[");
        for (int i = 0; i < list.size(); i++) {
            MapPlaces.Place p = list.get(i);
            if (i > 0) js.append(',');
            js.append('[').append(json(p.name())).append(',').append(json(p.kind())).append(',');
            if (mapper == null) {
                js.append("null,null");
            } else {
                double[] ll = mapper.toLatLonExact(p.cx() * 16, p.cz() * 16);
                js.append(String.format(Locale.ROOT, "%.6f,%.6f", ll[0], ll[1]));
            }
            js.append(',').append(p.rank()).append(']');
        }
        js.append(']');
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

    /** State of each chunk in a region file, from a cache when the file has not changed. */
    public static byte[] regionState(Path file) throws IOException {
        long size = Files.size(file);
        FileTime mtime = Files.getLastModifiedTime(file);
        synchronized (CACHE) {
            Region cached = CACHE.get(file);
            if (cached != null && cached.size() == size && cached.mtime().equals(mtime)) return cached.state();
        }
        byte[] state = new byte[1024];
        Inflater inflater = new Inflater();
        byte[] out = new byte[64 * 1024];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 8192) return state;
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int entry = ((header[i * 4] & 0xff) << 24) | ((header[i * 4 + 1] & 0xff) << 16) | ((header[i * 4 + 2] & 0xff) << 8) | (header[i * 4 + 3] & 0xff);
                if (entry == 0) continue;
                long offset = (long) (entry >>> 8) * 4096;
                if (offset + 5 > raf.length()) continue;
                raf.seek(offset);
                int length = raf.readInt();
                int compression = raf.readByte() & 0xff;
                if (length <= 1 || offset + 4 + length > raf.length()) continue;
                state[i] = 1;
                if (compression != 2) continue; // uncompressed / gzip / external: rare, count as partial
                // The chunk's Status is the first tag of its root compound (byte 4 in every chunk sampled), so
                // only the head of the stream is needed: a few hundred compressed bytes, not the whole ~66 KB.
                byte[] head = new byte[Math.min(length - 1, 512)];
                raf.readFully(head);
                try {
                    inflater.reset();
                    inflater.setInput(head);
                    int n = inflater.inflate(out, 0, 256);
                    if (contains(out, n, FULL_TAG)) {
                        state[i] = 2;
                    } else if (!contains(out, n, STATUS_KEY)) {
                        // Not where it usually is: read the whole chunk.
                        byte[] data = new byte[length - 1];
                        raf.seek(offset + 5);
                        raf.readFully(data);
                        inflater.reset();
                        inflater.setInput(data);
                        int m = 0;
                        while (!inflater.finished()) {
                            if (m == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                            int got = inflater.inflate(out, m, out.length - m);
                            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                            m += got;
                        }
                        if (contains(out, m, FULL_TAG)) state[i] = 2;
                    }
                } catch (DataFormatException ignored) {
                }
            }
        } finally {
            inflater.end();
        }
        synchronized (CACHE) {
            CACHE.put(file, new Region(size, mtime, state));
        }
        return state;
    }

    private static boolean contains(byte[] hay, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
