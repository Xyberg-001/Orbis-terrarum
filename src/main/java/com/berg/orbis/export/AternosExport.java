package com.berg.orbis.export;

import com.berg.orbis.worldgen.ChunkSelection;
import com.berg.orbis.worldgen.HardLimit;
import com.berg.orbis.worldgen.PregenMap;
import com.berg.orbis.worldgen.PregenTask;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code /orbis export aternos [orbis] [zip]}: a copy of this world ready to upload to Aternos (the world itself is
 * only read). Aternos takes a world upload of at most 1 GB and gives a server 4 GB; its Files page takes raw region
 * files but does not unpack zips.
 * <ul>
 *   <li>Which chunks: the fully generated ones (with the hard limit on, only its allowed area), nearest the spawn
 *   first, until the data budget: a single zip under 1 GB if everything fits (or with {@code zip}), else up to about
 *   3.4 GB as a base zip plus folders of region files of about 200 MB each, to add through the Files page.</li>
 *   <li>Region files are rewritten with only those chunks, packed without the gaps rewrites leave behind.</li>
 *   <li>For a plain server (the default, and what friends' servers without Orbis need): the overworld's generator
 *   becomes vanilla flat cold ocean at this world's sea level, so every chunk not in the upload is open sea; the
 *   world border goes round the chunks kept; the frame-lock datapack is added (museum and shop display frames of
 *   worlds furnished before frames were locked).</li>
 *   <li>With {@code orbis} (a server with Orbis Terrarum): the generator and every Orbis setting stay.</li>
 * </ul>
 * The result goes to {@code orbis-exports/<world>-aternos-<time>/} in the game folder, with a coverage picture
 * (a pixel per chunk) and HOW-TO-UPLOAD.txt.
 */
public final class AternosExport {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The whole zip, world files included: under Aternos' 1 GB with room (a 941 MB zip was refused on 2026-09-28). */
    private static final long ZIP_BUDGET = 900L << 20;
    private static final long STORAGE_BUDGET = 3400L << 20;
    private static final long BATCH = 200L << 20;
    private static final Set<String> SKIP_TOP = Set.of("session.lock", "voxy", "orbis-pregen", "xaeromap.txt", "orbis-repair-backup",
            "level.dat_old", "DistantHorizons.sqlite", "DistantHorizons");
    private static final String[] KINDS = {"region", "entities", "poi"};
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private AternosExport() {}

    public static Component start(MinecraftServer server, boolean forOrbis, boolean zipOnly) {
        if (PregenTask.isRunning()) return Component.literal("A pre-generation is running: export once it is done (or /orbis pregen stop).");
        if (!RUNNING.compareAndSet(false, true)) return Component.literal("An export is already running.");
        ServerLevel ow = server.overworld();
        server.saveEverything(true, true, true);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = server.getWorldData().getLevelName();
        net.minecraft.core.BlockPos spawn = ow.getRespawnData().pos();
        int seaTop = ow.getSeaLevel(), minY = ow.getMinY();
        ChunkSelection allowed = HardLimit.enabled() ? HardLimit.allowedArea() : null;
        Path games = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        Thread t = new Thread(() -> {
            try {
                run(text -> say(server, text), root, name, spawn.getX() >> 4, spawn.getZ() >> 4, seaTop, minY, allowed, forOrbis, zipOnly, games);
            } catch (Exception e) {
                e.printStackTrace();
                say(server, "The export failed: " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "Orbis-export");
        t.setDaemon(true);
        t.start();
        return Component.literal("Exporting \"" + name + "\" for Aternos (" + (forOrbis ? "for a server with Orbis Terrarum" : "for a plain Minecraft server")
                + "). The world was saved; the copy is made in the background, progress follows here.");
    }

    private static void say(MinecraftServer server, String text) {
        System.out.println("[orbis] Export: " + text);
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
    }

    // ------------------------------------------------------------------ the export

    private record Chunk(int cx, int cz, long bytes) {}

    /** The export itself (a background thread; {@code say} reports progress). Package-visible for offline tests. */
    static void run(java.util.function.Consumer<String> say, Path root, String name, int spawnCx, int spawnCz, int seaTop, int minY,
                    ChunkSelection allowed, boolean forOrbis, boolean zipOnly, Path gameDir) throws IOException {
        long t0 = System.currentTimeMillis();
        Path ow = root.resolve("dimensions").resolve("minecraft").resolve("overworld");
        Path regionDir = ow.resolve("region");
        if (!Files.isDirectory(regionDir)) throw new IOException("no region folder at " + regionDir);

        // 1. every fully generated overworld chunk, with its data (terrain, entities, points of interest together)
        Map<Long, long[]> size = new HashMap<>();
        for (String kind : KINDS) {
            Path dir = ow.resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    int[] rc = regionOf(f);
                    if (rc == null) continue;
                    long[] lens = chunkLengths(f);
                    for (int i = 0; i < 1024; i++) {
                        if (lens[i] <= 0) continue;
                        long key = key(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5));
                        size.computeIfAbsent(key, k -> new long[2]);
                        size.get(key)[0] += lens[i];
                        size.get(key)[1] += (lens[i] + 4095) / 4096 * 4096;
                    }
                }
            }
        }
        List<Chunk> candidates = new ArrayList<>();
        int skippedEmpty = 0, skippedOutside = 0;
        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                int[] rc = regionOf(f);
                if (rc == null) continue;
                byte[] state = PregenMap.regionState(f);
                for (int i = 0; i < 1024; i++) {
                    if (state[i] != 2) continue;
                    int cx = rc[0] * 32 + (i & 31), cz = rc[1] * 32 + (i >> 5);
                    if (HardLimit.emptied(cx, cz)) {
                        skippedEmpty++;
                        continue;
                    }
                    if (allowed != null && !allowed.contains(cx, cz)) {
                        skippedOutside++;
                        continue;
                    }
                    long[] s = size.get(key(cx, cz));
                    candidates.add(new Chunk(cx, cz, s == null ? 0 : (zipOnly ? s[0] : s[1])));
                }
            }
        }
        if (candidates.isEmpty()) throw new IOException("no fully generated chunks to export");
        candidates.sort((a, b) -> Long.compare(dist2(a, spawnCx, spawnCz), dist2(b, spawnCx, spawnCz)));
        long total = 0;
        for (Chunk c : candidates) total += c.bytes();
        long other = otherFiles(root, ow);
        boolean split = !zipOnly && total + other > ZIP_BUDGET;
        // What the chunks may take: the budget less the world's other files (player data, datapacks, the map cache).
        long budget = (split ? STORAGE_BUDGET : ZIP_BUDGET) - other;
        Set<Long> keep = new HashSet<>();
        long used = 0;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        for (Chunk c : candidates) {
            if (used + c.bytes() > budget) break;
            used += c.bytes();
            keep.add(key(c.cx(), c.cz()));
            minCx = Math.min(minCx, c.cx());
            maxCx = Math.max(maxCx, c.cx());
            minCz = Math.min(minCz, c.cz());
            maxCz = Math.max(maxCz, c.cz());
        }
        say.accept(String.format(Locale.ROOT, "%,d of %,d generated chunks go in (%.2f GB of data%s)%s%s", keep.size(), candidates.size(), used / 1e9,
                keep.size() < candidates.size() ? ", the ones nearest the spawn: Aternos' limit" : "",
                skippedOutside > 0 ? String.format(Locale.ROOT, "; %,d outside the hard limit's area left out", skippedOutside) : "",
                skippedEmpty > 0 ? String.format(Locale.ROOT, "; %,d empty hard-limit chunks left out", skippedEmpty) : ""));

        // 2. the stage: a copy of the world without the overworld's chunk folders and the client-only extras
        String folder = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (folder.isEmpty()) folder = "world";
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path out = gameDir.resolve("orbis-exports").resolve(folder + "-aternos-" + stamp);
        Path stage = out.resolve("stage").resolve(folder);
        Files.createDirectories(stage);
        copyWorld(root, stage, ow);

        // 3. the overworld's region, entity and POI files with only the kept chunks
        Path sow = stage.resolve("dimensions").resolve("minecraft").resolve("overworld");
        int written = 0;
        for (String kind : KINDS) {
            Path dir = ow.resolve(kind);
            Path dest = sow.resolve(kind);
            Files.createDirectories(dest);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    int[] rc = regionOf(f);
                    if (rc == null) continue;
                    if (rewriteRegion(f, dest.resolve(f.getFileName()), rc, keep)) written++;
                }
            }
        }
        Set<Long> keptRegions = new HashSet<>();
        for (long k : keep) keptRegions.add(key(Math.floorDiv((int) (k >> 32), 32), Math.floorDiv((int) k, 32)));

        // 4. a plain server: flat ocean generator, the border round the upload, the frame lock; an Orbis server: as is
        if (!forOrbis) {
            makeVanilla(stage, sow, seaTop, minY, minCx, maxCx, minCz, maxCz);
            deleteTree(stage.resolve("orbis-map"));
        } else {
            trimMap(stage.resolve("orbis-map"), keptRegions);
        }
        CompoundTag level = NbtIo.readCompressed(stage.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        level.getCompoundOrEmpty("Data").putString("LevelName", folder);
        NbtIo.writeCompressed(level, stage.resolve("level.dat"));

        // 5. package: one zip, or a base zip plus batches of region files
        Path zip;
        List<Path> batches = new ArrayList<>();
        if (!split) {
            zip = out.resolve(folder + ".zip");
            zipTree(out.resolve("stage"), zip, null);
        } else {
            zip = out.resolve(folder + "-base.zip");
            Path region = sow.resolve("region");
            zipTree(out.resolve("stage"), zip, region);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(region)) {
                s.sorted().forEach(files::add);
            }
            long acc = BATCH;
            Path batch = null;
            for (Path f : files) {
                long len = Files.size(f);
                if (batch == null || acc + len > BATCH) {
                    batch = out.resolve(String.format(Locale.ROOT, "region-batch-%02d", batches.size() + 1));
                    Files.createDirectories(batch);
                    batches.add(batch);
                    acc = 0;
                }
                Files.move(f, batch.resolve(f.getFileName()));
                acc += len;
            }
        }
        coverage(out.resolve("coverage.png"), candidates, keep);
        instructions(out.resolve("HOW-TO-UPLOAD.txt"), folder, zip, batches, forOrbis, keep.size(), minCx, maxCx, minCz, maxCz);
        deleteTree(out.resolve("stage"));
        say.accept(String.format(Locale.ROOT, "Export ready in %.0f s: %s (%s%s). Open HOW-TO-UPLOAD.txt there for the Aternos steps.",
                (System.currentTimeMillis() - t0) / 1000.0, out, human(Files.size(zip)),
                batches.isEmpty() ? "" : String.format(Locale.ROOT, " + %d folder(s) of region files", batches.size())));
    }

    /** Bytes of the world files that go into the upload besides the overworld's chunks. */
    private static long otherFiles(Path root, Path ow) throws IOException {
        long[] n = {0};
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(root) && dir.getParent().equals(root) && SKIP_TOP.contains(dir.getFileName().toString())) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.getParent() != null && dir.getParent().equals(ow)) {
                    String name = dir.getFileName().toString();
                    if (name.equals("region") || name.equals("entities") || name.equals("poi")) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                n[0] += attrs.size();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return n[0];
    }

    // ------------------------------------------------------------------ region files

    private static int[] regionOf(Path f) {
        Matcher m = REGION.matcher(f.getFileName().toString());
        return m.matches() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static long dist2(Chunk c, int sx, int sz) {
        long dx = c.cx() - sx, dz = c.cz() - sz;
        return dx * dx + dz * dz;
    }

    /** Each chunk's stored length in bytes (4-byte length + type + data), 0 when absent. */
    private static long[] chunkLengths(Path f) throws IOException {
        long[] out = new long[1024];
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            if (raf.length() < 8192) return out;
            byte[] hdr = new byte[4096];
            raf.readFully(hdr);
            for (int i = 0; i < 1024; i++) {
                long off = (((hdr[4 * i] & 0xffL) << 16) | ((hdr[4 * i + 1] & 0xffL) << 8) | (hdr[4 * i + 2] & 0xffL)) * 4096;
                if (off == 0 || off + 4 > raf.length()) continue;
                raf.seek(off);
                int len = raf.readInt();
                if (len > 0 && off + 4 + len <= raf.length()) out[i] = 4L + len;
            }
        }
        return out;
    }

    /** Writes the region file with only the kept chunks, back to back on 4 KiB sectors. False when none are kept. */
    private static boolean rewriteRegion(Path src, Path dest, int[] rc, Set<Long> keep) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(src.toFile(), "r")) {
            if (raf.length() < 8192) return false;
            byte[] loc = new byte[4096], ts = new byte[4096];
            raf.readFully(loc);
            raf.readFully(ts);
            byte[] newLoc = new byte[4096], newTs = new byte[4096];
            List<byte[]> bodies = new ArrayList<>();
            int sector = 2;
            for (int i = 0; i < 1024; i++) {
                if (!keep.contains(key(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5)))) continue;
                long off = (((loc[4 * i] & 0xffL) << 16) | ((loc[4 * i + 1] & 0xffL) << 8) | (loc[4 * i + 2] & 0xffL)) * 4096;
                if (off == 0 || off + 4 > raf.length()) continue;
                raf.seek(off);
                int len = raf.readInt();
                if (len <= 0 || off + 4 + len > raf.length()) continue;
                byte[] raw = new byte[4 + len];
                raf.seek(off);
                raf.readFully(raw);
                int n = (raw.length + 4095) / 4096;
                if (n > 255) continue; // an oversized chunk lives in a separate .mcc file; not carried over
                newLoc[4 * i] = (byte) (sector >> 16);
                newLoc[4 * i + 1] = (byte) (sector >> 8);
                newLoc[4 * i + 2] = (byte) sector;
                newLoc[4 * i + 3] = (byte) n;
                System.arraycopy(ts, 4 * i, newTs, 4 * i, 4);
                bodies.add(raw);
                sector += n;
            }
            if (bodies.isEmpty()) return false;
            try (OutputStream o = new java.io.BufferedOutputStream(Files.newOutputStream(dest), 1 << 20)) {
                o.write(newLoc);
                o.write(newTs);
                for (byte[] b : bodies) {
                    o.write(b);
                    int pad = (4096 - b.length % 4096) % 4096;
                    o.write(new byte[pad]);
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ world files

    private static void copyWorld(Path root, Path stage, Path ow) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && dir.getParent().equals(root) && SKIP_TOP.contains(dir.getFileName().toString())) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.getParent() != null && dir.getParent().equals(ow)) {
                    String n = dir.getFileName().toString();
                    if (n.equals("region") || n.equals("entities") || n.equals("poi")) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(stage.resolve(root.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (file.getParent().equals(root) && SKIP_TOP.contains(file.getFileName().toString())) return java.nio.file.FileVisitResult.CONTINUE;
                try {
                    Files.copy(file, stage.resolve(root.relativize(file).toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.err.println("[orbis] Export: could not copy " + file + ": " + e.getMessage());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * A plain server: vanilla flat cold ocean (bedrock, stone, 4 gravel, 20 water up to the sea level) wherever the
     * upload has no chunk, the world border round the chunks kept, and the frame-lock datapack.
     */
    private static void makeVanilla(Path stage, Path sow, int seaTop, int minY, int minCx, int maxCx, int minCz, int maxCz) throws IOException {
        Path wgs = stage.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        CompoundTag rootTag = NbtIo.readCompressed(wgs, NbtAccounter.unlimitedHeap());
        CompoundTag dims = rootTag.getCompoundOrEmpty("data").getCompoundOrEmpty("dimensions");
        CompoundTag owDim = dims.getCompoundOrEmpty("minecraft:overworld");
        int water = 20, gravel = 4;
        int stone = (seaTop - water - gravel) - (minY + 1) + 1;
        ListTag layers = new ListTag();
        for (Object[] l : new Object[][]{{"minecraft:bedrock", 1}, {"minecraft:stone", stone}, {"minecraft:gravel", gravel}, {"minecraft:water", water}}) {
            CompoundTag layer = new CompoundTag();
            layer.putString("block", (String) l[0]);
            layer.putInt("height", (Integer) l[1]);
            layers.add(layer);
        }
        CompoundTag settings = new CompoundTag();
        settings.putString("biome", "minecraft:cold_ocean");
        settings.putBoolean("features", false);
        settings.putBoolean("lakes", false);
        settings.put("layers", layers);
        settings.put("structure_overrides", new ListTag());
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:flat");
        generator.put("settings", settings);
        owDim.put("generator", generator);
        NbtIo.writeCompressed(rootTag, wgs);

        Path wb = sow.resolve("data").resolve("minecraft").resolve("world_border.dat");
        CompoundTag border = Files.exists(wb) ? NbtIo.readCompressed(wb, NbtAccounter.unlimitedHeap()) : new CompoundTag();
        CompoundTag d = border.getCompoundOrEmpty("data");
        double cx = (minCx + maxCx + 1) * 8.0, cz = (minCz + maxCz + 1) * 8.0;
        double size = Math.max(maxCx - minCx + 1, maxCz - minCz + 1) * 16.0;
        d.putDouble("center_x", cx);
        d.putDouble("center_z", cz);
        d.putDouble("size", size);
        d.putDouble("lerp_target", size);
        d.putLong("lerp_time", 0L);
        border.put("data", d);
        Files.createDirectories(wb.getParent());
        NbtIo.writeCompressed(border, wb);

        Path pack = stage.resolve("datapacks").resolve("orbis-display-lock");
        Files.createDirectories(pack.resolve("data/orbis_lock/function"));
        Files.createDirectories(pack.resolve("data/minecraft/tags/function"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\n  \"pack\": {\n    \"description\": \"Orbis Terrarum: locks the museum and shop display frames\",\n"
                + "    \"min_format\": [107, 0],\n    \"max_format\": 107\n  }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("data/minecraft/tags/function/load.json"), "{\"values\": [\"orbis_lock:load\"]}\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("data/orbis_lock/function/load.mcfunction"), "schedule function orbis_lock:tick 20t replace\n", StandardCharsets.UTF_8);
        StringBuilder tick = new StringBuilder("# Once a second: lock the item frames Orbis Terrarum furnished (as vanilla structures lock theirs).\n");
        tick.append("execute as @e[type=item_frame,nbt=!{Fixed:1b},nbt={Facing:1b}] at @s if block ~ ~-1 ~ minecraft:quartz_pillar run data merge entity @s {Fixed:1b}\n");
        for (String item : new String[]{"diamond", "emerald", "gold_ingot", "iron_ingot", "redstone", "iron_pickaxe"}) {
            tick.append("execute as @e[type=item_frame,nbt=!{Fixed:1b},nbt={Item:{id:\"minecraft:").append(item)
                    .append("\"}}] at @s if block ~ ~-1 ~ minecraft:barrel run data merge entity @s {Fixed:1b}\n");
        }
        for (String item : new String[]{"diamond", "emerald"}) {
            tick.append("execute as @e[type=item_frame,nbt=!{Fixed:1b},nbt={Item:{id:\"minecraft:").append(item)
                    .append("\"}}] at @s if entity @e[type=item_frame,distance=..24,nbt={Facing:1b,Fixed:1b}] run data merge entity @s {Fixed:1b}\n");
        }
        tick.append("schedule function orbis_lock:tick 20t replace\n");
        Files.writeString(pack.resolve("data/orbis_lock/function/tick.mcfunction"), tick.toString(), StandardCharsets.UTF_8);
    }

    private static void trimMap(Path mapDir, Set<Long> keptRegions) throws IOException {
        if (!Files.isDirectory(mapDir)) return;
        Pattern bin = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.bin");
        try (Stream<Path> files = Files.list(mapDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher m = bin.matcher(f.getFileName().toString());
                if (m.matches() && !keptRegions.contains(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))))) Files.delete(f);
            }
        }
    }

    // ------------------------------------------------------------------ packaging and notes

    /** Zips a folder (paths relative to it, folder entries included as Aternos' own zips have them), leaving out one subfolder's files. */
    private static void zipTree(Path base, Path zip, Path leaveOut) throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(base)) {
            s.forEach(all::add);
        }
        all.sort((a, b) -> {
            boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
            if (da != db) return da ? -1 : 1;
            return a.toString().compareTo(b.toString());
        });
        try (ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(zip), 1 << 20))) {
            z.setLevel(9);
            for (Path p : all) {
                if (p.equals(base)) continue;
                String arc = base.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    z.putNextEntry(new ZipEntry(arc + "/"));
                    z.closeEntry();
                    continue;
                }
                if (leaveOut != null && p.getParent().equals(leaveOut)) continue;
                z.putNextEntry(new ZipEntry(arc));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    /** A pixel per chunk: green in the upload, amber generated but left out (the budget), dark nothing. */
    private static void coverage(Path png, List<Chunk> candidates, Set<Long> keep) throws IOException {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Chunk c : candidates) {
            minX = Math.min(minX, c.cx());
            maxX = Math.max(maxX, c.cx());
            minZ = Math.min(minZ, c.cz());
            maxZ = Math.max(maxZ, c.cz());
        }
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        if ((long) w * h > 64_000_000L) return;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0x1E2227);
        for (Chunk c : candidates) img.setRGB(c.cx() - minX, c.cz() - minZ, keep.contains(key(c.cx(), c.cz())) ? 0x4ADE80 : 0xF59E0B);
        javax.imageio.ImageIO.write(img, "png", png.toFile());
    }

    private static void instructions(Path file, String folder, Path zip, List<Path> batches, boolean forOrbis, int chunks,
                                     int minCx, int maxCx, int minCz, int maxCz) throws IOException {
        StringBuilder t = new StringBuilder();
        t.append("Orbis Terrarum -> Aternos: ").append(folder).append("\n\n");
        t.append(String.format(Locale.ROOT, "%,d chunks, blocks %d..%d x %d..%d (coverage.png: green = in the upload, amber = generated but left out).%n%n",
                chunks, minCx * 16, maxCx * 16 + 15, minCz * 16, maxCz * 16 + 15));
        t.append(forOrbis
                ? "Made for a server that runs Orbis Terrarum (same version as here): the world keeps its generator and settings.\n\n"
                : "Made for a plain Minecraft server (no Orbis needed; the server needs the same Minecraft version): outside the upload the world is flat\n"
                + "open sea, the world border goes round the uploaded area, and a small datapack locks the museum and shop display frames.\n\n");
        t.append("1. On aternos.org, stop the server, then Worlds -> Upload and choose ").append(zip.getFileName()).append(".\n");
        t.append("   (Uploading replaces the server's world; download a backup of it first under Backups if you want to keep it.)\n");
        if (!batches.isEmpty()) {
            t.append("2. The zip holds everything but the terrain files, which are too big for one upload. Open Files -> world ->\n");
            t.append("   dimensions -> minecraft -> overworld -> region, and upload the files of each folder, one folder at a time:\n");
            for (Path b : batches) t.append("     ").append(b.getFileName()).append("\n");
            t.append("   (select every file in the folder at once in the upload dialog). Keep \"Optimize\" in the world options OFF:\n");
            t.append("   it deletes chunks nobody has visited yet.\n");
            t.append("3. Start the server.\n");
        } else {
            t.append("2. Start the server.\n");
        }
        Files.writeString(file, t.toString(), StandardCharsets.UTF_8);
    }

    private static String human(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            List<Path> all = new ArrayList<>();
            s.forEach(all::add);
            all.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path x : all) Files.deleteIfExists(x);
        }
    }
}
