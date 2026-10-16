package com.berg.orbis.net;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Look-ups that may wait on the network (terrain tiles for a place, whether a service answers): two threads of their own. Not
 * Minecraft's background pool, which also generates the chunks (a slow download there takes a chunk worker away just as a
 * pre-generation starts), and never the server or render thread.
 */
public final class Lookups {
    public static final ExecutorService POOL = Executors.newFixedThreadPool(2, task -> {
        Thread t = new Thread(task, "Orbis-lookup");
        t.setDaemon(true);
        return t;
    });

    private Lookups() {
    }
}
