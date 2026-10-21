package com.berg.orbis.client;

import com.berg.orbis.biome.ClimateMap;
import com.berg.orbis.net.SeasonInfoPayload;
import com.berg.orbis.osm.CoordinateMapper;
import com.berg.orbis.sky.ClimateNormals;
import com.berg.orbis.sky.Seasons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each place's own season on the grass and the leaves, for players with Orbis: the season pack gives the whole world the
 * season of its origin (Norway's autumn in the Sahara too) and changes only when the world is opened again; here the
 * colour of every block comes from the season of its own place, today, and changes as the days go by.
 *
 * A place's months come from the climate pack in the jar (Köppen-Geiger and CHELSA, as the biomes do): the year's mean
 * and the coldest quarter's mean make a year of temperatures (coldest in mid-January north of the equator, mid-July
 * south of it), brought to the block's own height at 6.5 C a kilometre; the Köppen class says which months are dry (the
 * warm ones of a Mediterranean "s" climate, the cool ones of a monsoon or savanna "w"). The season rules are the
 * pack's ({@link Seasons}), from the vanilla colour of the biome, so the pack's own colour shift is replaced. Between
 * mid-months the colours blend day by day. A block's height comes from its chunk's height map, read as the chunk
 * arrives; its real height is the world's sea level and metres per block (above the relief knee, where a relative-mode
 * world lowers whole regions, at least the climate cell's mean height).
 *
 * The world's server says whether it has real seasons ({@link SeasonInfoPayload}); without it (a plain server, seasons
 * off) nothing changes.
 */
public final class LocalSeasons {
    private static volatile SeasonInfoPayload info;
    private static volatile CoordinateMapper mapper;
    private static volatile boolean climateReady;
    private static volatile boolean loading;
    private static volatile LocalDate day = LocalDate.now();

    /** Per chunk (x, z packed): the top block's height of each column, 16 x 16. */
    private static final Map<Long, short[]> HEIGHTS = new ConcurrentHashMap<>();
    /** Per 32-block cell: {sea-level mean C, sea-level coldest quarter C, precipitation mm, Köppen class, cell height m, latitude}. */
    private static final Map<Long, double[]> CLIMATE = new ConcurrentHashMap<>();
    /** Per biome: {vanilla grass, vanilla foliage, 1 when the game recolours its grass itself (swamp, dark forest)}. */
    private static final Map<Biome, int[]> BIOMES = new ConcurrentHashMap<>();

    private LocalSeasons() {
    }

    /** The server's word on seasons (as a player joins). */
    public static void setInfo(SeasonInfoPayload p) {
        info = p;
        mapper = null;
        CLIMATE.clear();
        if (p != null && p.on() && !climateReady && !loading) {
            loading = true;
            Thread t = new Thread(() -> {
                try {
                    ClimateMap.get(); // 25 MB: read once, off the render thread
                    climateReady = true;
                    Minecraft.getInstance().execute(LocalSeasons::recolour);
                } catch (RuntimeException e) {
                    System.err.println("[orbis] Local seasons: the climate pack could not be read (" + e + ")");
                } finally {
                    loading = false;
                }
            }, "Orbis local seasons");
            t.setDaemon(true);
            t.start();
        } else {
            Minecraft.getInstance().execute(LocalSeasons::recolour);
        }
    }

    public static void reset() {
        info = null;
        mapper = null;
        HEIGHTS.clear();
        CLIMATE.clear();
        BIOMES.clear();
    }

    public static void chunkLoaded(LevelChunk chunk) {
        if (info == null || !info.on()) return;
        short[] h = new short[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) h[z * 16 + x] = (short) chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        HEIGHTS.put(chunk.getPos().pack(), h);
    }

    public static void chunkUnloaded(LevelChunk chunk) {
        HEIGHTS.remove(chunk.getPos().pack());
    }

    /** Once a minute or so (client ticks): a new day recolours the world. */
    public static void tick() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            if (active()) recolour();
        }
    }

    /** Switched off for a comparison (the self test): the season pack's colours show. */
    static volatile boolean suspended;

    static boolean active() {
        SeasonInfoPayload p = info;
        return !suspended && p != null && p.on() && climateReady && OrbisClient.worldInfo != null;
    }

    /** What a place's season is today and why (the self test's report). */
    static String describe(int bx, int bz) {
        double[] c = climateAt(bx, bz);
        if (c == null) return "no climate";
        double h = heightM(bx, bz, c[4]);
        ClimateNormals.Months m = months(c, h);
        int month = day.getMonthValue() - 1;
        double[] ll = mapper() == null ? new double[]{Double.NaN, Double.NaN} : mapper().toLatLonExact(bx, bz);
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f Koppen %s, height %.0f m, this month %.1f C %.0f mm: %s", ll[0], ll[1], koppen((int) c[3]), h,
                m.temperatureC()[month], m.precipitationMm()[month], com.berg.orbis.sky.Seasons.lookName(m, month));
    }

    /** Every chunk drawn again with fresh colours. */
    static void recolour() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.clearTintCaches();
        mc.levelExtractor.allChanged();
    }

    /** The grass colour of a block by its place's season, or the game's colour when local seasons are off. */
    public static int grass(Biome biome, double x, double z, int game) {
        if (!active()) return game;
        int[] b = biomeColours(biome);
        if (b == null || b[2] == 1) return game;
        return colour(x, z, b[0], false, game);
    }

    /** The leaf colour of a block by its place's season, or the game's colour when local seasons are off. */
    public static int foliage(Biome biome, double x, double z, int game) {
        if (!active()) return game;
        int[] b = biomeColours(biome);
        if (b == null) return game;
        return colour(x, z, b[1], true, game);
    }

    private static int colour(double x, double z, int vanilla, boolean foliage, int game) {
        try {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            double[] c = climateAt(bx, bz);
            if (c == null) return game;
            ClimateNormals.Months months = months(c, heightM(bx, bz, c[4]));
            // mid-month the month's own colours; between, the neighbour's blended in day by day
            LocalDate d = day;
            int m = d.getMonthValue() - 1, len = d.lengthOfMonth();
            double f = (d.getDayOfMonth() - 0.5) / len - 0.5; // -0.5 .. 0.5
            int here = Seasons.colourIn(months, m, vanilla, foliage);
            int other = Seasons.colourIn(months, Math.floorMod(m + (f < 0 ? -1 : 1), 12), vanilla, foliage);
            return (mix(here, other, Math.abs(f)) & 0xFFFFFF) | (game & 0xFF000000); // the game's alpha
        } catch (RuntimeException e) {
            return game;
        }
    }

    /** The real height of a block column, metres: from its chunk's height map, or the climate cell's mean. */
    private static double heightM(int bx, int bz, double cellM) {
        SeasonInfoPayload p = info;
        short[] h = HEIGHTS.get(net.minecraft.world.level.ChunkPos.pack(bx >> 4, bz >> 4));
        if (h == null || p == null) return cellM;
        double m = (h[(bz & 15) * 16 + (bx & 15)] - p.seaLevelY()) * p.metresPerBlock();
        if (p.relative() && m > 0.8 * p.reliefKneeMeters()) m = Math.max(m, cellM); // the region may be lowered
        return Math.max(0, m);
    }

    private static double[] climateAt(int bx, int bz) {
        long key = (long) (bx >> 5) << 32 ^ ((bz >> 5) & 0xffffffffL);
        double[] c = CLIMATE.get(key);
        if (c != null) return c;
        CoordinateMapper map = mapper();
        if (map == null) return null;
        double[] ll = map.toLatLonExact(bx, bz);
        ClimateMap cm = ClimateMap.get();
        c = new double[]{cm.seaLevelMeanC(ll[0], ll[1]), cm.seaLevelColdC(ll[0], ll[1]), cm.precipitationMm(ll[0], ll[1]), cm.koppen(ll[0], ll[1]),
                cm.cellElevationM(ll[0], ll[1]), ll[0]};
        if (Double.isNaN(c[0]) || Double.isNaN(c[1])) return null;
        if (CLIMATE.size() > 50_000) CLIMATE.clear();
        CLIMATE.put(key, c);
        return c;
    }

    private static CoordinateMapper mapper() {
        CoordinateMapper m = mapper;
        if (m != null) return m;
        var w = OrbisClient.worldInfo;
        if (w == null) return null;
        m = new CoordinateMapper(w.originLat(), w.originLon(), w.metersPerBlock(), CoordinateMapper.Projection.of(w.projection()));
        mapper = m;
        return m;
    }

    /**
     * A place's year at a height: temperatures on a yearly wave through the coldest quarter's mean (its three months
     * average 0.911 of the swing), precipitation shared out with the Köppen class's dry months nearly dry.
     */
    static ClimateNormals.Months months(double[] c, double heightM) {
        double mean = c[0] - 0.0065 * heightM, cold = c[1] - 0.0065 * heightM, lat = c[5];
        double swing = Math.max(0, mean - cold) / 0.911;
        int coldest = lat >= 0 ? 0 : 6;
        double[] t = new double[12], p = new double[12];
        Integer[] order = new Integer[12];
        for (int m = 0; m < 12; m++) {
            t[m] = mean - swing * Math.cos(2 * Math.PI * (m - coldest) / 12.0);
            order[m] = m;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(t[a], t[b])); // coldest first
        String k = koppen((int) c[3]);
        java.util.Set<Integer> dry = new java.util.HashSet<>();
        if (k.length() >= 2 && (k.charAt(0) == 'C' || k.charAt(0) == 'D') && k.charAt(1) == 's') for (int i = 8; i < 12; i++) dry.add(order[i]); // dry summer
        else if (k.length() >= 2 && k.charAt(1) == 'w' || k.equals("Aw")) for (int i = 0; i < 5; i++) dry.add(order[i]); // dry winter
        else if (k.equals("Am")) for (int i = 0; i < 4; i++) dry.add(order[i]);
        double total = Math.max(0, c[2]);
        if (k.startsWith("B")) dry.clear(); // dry the year round: no wet season to tell from
        if (dry.isEmpty()) {
            for (int m = 0; m < 12; m++) p[m] = total / 12;
        } else {
            // the wet months' rain peaks opposite the dry season (in winter after a dry summer, in summer after a dry
            // winter): spread evenly, Los Angeles' 380 mm made no month wet enough to tell a dry season from
            boolean drySummer = dry.contains(order[11]);
            int peak = drySummer ? order[0] : order[11];
            double[] w = new double[12];
            double sum = 0;
            for (int m = 0; m < 12; m++) {
                if (dry.contains(m)) continue;
                w[m] = 1 + 0.6 * Math.cos(2 * Math.PI * (m - peak) / 12.0);
                sum += w[m];
            }
            double rest = Math.max(0, total - 5.0 * dry.size());
            for (int m = 0; m < 12; m++) p[m] = dry.contains(m) ? 5 : rest * w[m] / sum;
        }
        return new ClimateNormals.Months(t, p);
    }

    private static final String[] KOPPEN = {"-", "Af", "Am", "Aw", "BWh", "BWk", "BSh", "BSk", "Csa", "Csb", "Csc", "Cwa", "Cwb", "Cwc", "Cfa", "Cfb", "Cfc",
            "Dsa", "Dsb", "Dsc", "Dsd", "Dwa", "Dwb", "Dwc", "Dwd", "Dfa", "Dfb", "Dfc", "Dfd", "ET", "EF"};

    private static String koppen(int k) {
        return k >= 0 && k < KOPPEN.length ? KOPPEN[k] : "-";
    }

    private static int[] biomeColours(Biome biome) {
        int[] b = BIOMES.get(biome);
        if (b != null) return b;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        Identifier id = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome);
        int[] c = id == null ? null : com.berg.orbis.map.MapColours.biomeColours().get(id.toString());
        if (c == null) return null;
        boolean modified = biome.getSpecialEffects().grassColorModifier() != BiomeSpecialEffects.GrassColorModifier.NONE;
        b = new int[]{c[0], c[1], modified ? 1 : 0};
        BIOMES.put(biome, b);
        return b;
    }

    private static int mix(int a, int b, double by) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * by);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * by);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * by);
        return (a & 0xFF000000) | r << 16 | g << 8 | bl;
    }
}
