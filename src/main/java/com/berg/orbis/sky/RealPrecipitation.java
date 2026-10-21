package com.berg.orbis.sky;

/**
 * Rain or snow by the real temperature, while real weather runs. The season pack cools a whole month's biomes, so in a
 * mild spell of a cold month taiga had snow falling at +6 C, and in an autumn month rain at -3 C. The live report's
 * temperature at the players' place is taken as the air at the ground there, and up and down the world it cools by
 * 6.5 C a kilometre of real height: from 2 C the precipitation is rain, at 0 C and below snow, and in between the
 * biome decides as ever (sleet weather). It decides what falls (on the server, and on clients with Orbis installed,
 * which the server tells) and so where snow builds up; water freezing stays the biome's (a frosty night does not ice
 * the sea).
 *
 * Only the server's own thread and the client's render thread ask it: world generation, on its worker threads, never
 * sees it, so a chunk comes out the same whatever the weather was when it was made. The server and the client keep
 * their own readings (in a singleplayer or LAN game both live in one game): the client's is its player's own place
 * ({@link com.berg.orbis.client.LocalWeather}); the server's are each player's area (the readings RealSky gets for
 * every player's 5 km cell), a place taking the nearest within 30 km, and the players' mean beyond them, so snow
 * builds up by the temperature where it falls, not halfway between players far apart.
 */
public final class RealPrecipitation {
    /** Rain from this temperature (C), snow at or below SNOW_C, the biome's own choice between them. */
    public static final double RAIN_C = 2.0, SNOW_C = 0.0;
    /** A temperature this old no longer counts (the weather is asked every two minutes). */
    private static final long STALE_MS = 20 * 60_000L;

    /** Temperature at the reference block height, the height, and degrees lost per block up; NaN when unknown. */
    private record State(double tempC, double refY, double coolPerBlock, long at) {
    }

    private static volatile State state, clientState;

    /** A player area's reading: its block position and the reading there. */
    private record Area(int x, int z, State s) {
    }

    private static volatile java.util.List<Area> areas = java.util.List.of();
    private static volatile double reachSq = 0;
    private static volatile Thread serverThread, clientThread;

    private RealPrecipitation() {
    }

    public static void serverThread(Thread t) {
        serverThread = t;
    }

    public static void clientThread(Thread t) {
        clientThread = t;
    }

    /** A new reading (the server's): the temperature at block height {@code refY}, and the real metres one block of height stands for. */
    public static void set(double tempC, double refY, double metresPerBlock) {
        state = make(tempC, refY, metresPerBlock);
    }

    /** A new reading for the client's own drawing (what falls where its player is). */
    public static void setClient(double tempC, double refY, double metresPerBlock) {
        clientState = make(tempC, refY, metresPerBlock);
    }

    private static State make(double tempC, double refY, double metresPerBlock) {
        if (Double.isNaN(tempC) || Double.isNaN(refY) || !(metresPerBlock > 0)) return null;
        return new State(tempC, refY, 0.0065 * metresPerBlock, System.currentTimeMillis());
    }

    public static void clear() {
        state = null;
        areas = java.util.List.of();
    }

    /**
     * The players' areas' readings (the server's), each {block x, block z, temperature, its block height, metres per
     * block}; a place takes the nearest within {@code reachBlocks}. An empty list leaves only the mean.
     */
    public static void setAreas(java.util.List<double[]> readings, double reachBlocks) {
        java.util.List<Area> list = new java.util.ArrayList<>();
        for (double[] r : readings) {
            State s = make(r[2], r[3], r[4]);
            if (s != null) list.add(new Area((int) r[0], (int) r[1], s));
        }
        reachSq = reachBlocks * reachBlocks;
        areas = java.util.List.copyOf(list);
    }

    public static void clearClient() {
        clientState = null;
    }

    /** The reading for sending to clients: {tempC, refY, metres per block}, or null without one. */
    public static double[] current() {
        State s = state;
        if (s == null || System.currentTimeMillis() - s.at > STALE_MS) return null;
        return new double[]{s.tempC, s.refY, s.coolPerBlock / 0.0065};
    }

    /** At a block: TRUE rain, FALSE snow, null the biome's own choice (no reading, too near freezing, or another thread). */
    public static Boolean rainAt(int x, int y, int z) {
        Thread t = Thread.currentThread();
        State s = t == serverThread ? serverAt(x, z) : t == clientThread ? clientState : null;
        if (s == null) return null;
        if (System.currentTimeMillis() - s.at > STALE_MS) return null;
        double c = s.tempC - s.coolPerBlock * (y - s.refY);
        if (c >= RAIN_C) return Boolean.TRUE;
        if (c <= SNOW_C) return Boolean.FALSE;
        return null;
    }

    /** The server's reading for a place: the nearest player area's within reach, else the mean. */
    private static State serverAt(int x, int z) {
        java.util.List<Area> list = areas;
        State best = null;
        double bestSq = reachSq;
        long now = System.currentTimeMillis();
        for (Area a : list) {
            double dx = a.x - x, dz = a.z - z, d = dx * dx + dz * dz;
            if (d <= bestSq && now - a.s.at <= STALE_MS) {
                bestSq = d;
                best = a.s;
            }
        }
        return best != null ? best : state;
    }

    /** The temperature the server takes at a block (for the self test's report); NaN without a reading. */
    public static double serverTempAt(int x, int y, int z) {
        State s = serverAt(x, z);
        return s == null ? Double.NaN : s.tempC - s.coolPerBlock * (y - s.refY);
    }
}
