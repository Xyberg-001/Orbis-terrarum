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
 * sees it, so a chunk comes out the same whatever the weather was when it was made.
 */
public final class RealPrecipitation {
    /** Rain from this temperature (C), snow at or below SNOW_C, the biome's own choice between them. */
    public static final double RAIN_C = 2.0, SNOW_C = 0.0;
    /** A temperature this old no longer counts (the weather is asked every two minutes). */
    private static final long STALE_MS = 20 * 60_000L;

    /** Temperature at the reference block height, the height, and degrees lost per block up; NaN when unknown. */
    private record State(double tempC, double refY, double coolPerBlock, long at) {
    }

    private static volatile State state;
    private static volatile Thread serverThread, clientThread;

    private RealPrecipitation() {
    }

    public static void serverThread(Thread t) {
        serverThread = t;
    }

    public static void clientThread(Thread t) {
        clientThread = t;
    }

    /** A new reading: the temperature at block height {@code refY}, and the real metres one block of height stands for. */
    public static void set(double tempC, double refY, double metresPerBlock) {
        if (Double.isNaN(tempC) || Double.isNaN(refY) || !(metresPerBlock > 0)) {
            clear();
            return;
        }
        state = new State(tempC, refY, 0.0065 * metresPerBlock, System.currentTimeMillis());
    }

    public static void clear() {
        state = null;
    }

    /** The reading for sending to clients: {tempC, refY, metres per block}, or null without one. */
    public static double[] current() {
        State s = state;
        if (s == null || System.currentTimeMillis() - s.at > STALE_MS) return null;
        return new double[]{s.tempC, s.refY, s.coolPerBlock / 0.0065};
    }

    /** At block height y: TRUE rain, FALSE snow, null the biome's own choice (no reading, too near freezing, or another thread). */
    public static Boolean rainAt(int y) {
        State s = state;
        if (s == null) return null;
        Thread t = Thread.currentThread();
        if (t != serverThread && t != clientThread) return null;
        if (System.currentTimeMillis() - s.at > STALE_MS) return null;
        double c = s.tempC - s.coolPerBlock * (y - s.refY);
        if (c >= RAIN_C) return Boolean.TRUE;
        if (c <= SNOW_C) return Boolean.FALSE;
        return null;
    }
}
