package com.berg.orbis.worldgen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * /locate in an Orbis Terrarum world, away from the server thread. Vanilla searches on the server thread, and here every place it looks at
 * may need map data and elevation tiles that are not downloaded yet: the server stopped ticking for minutes (the watchdog ended it after
 * one). The search runs on a thread of its own, one at a time, and its answer is given on the server thread when it is done; the chunks it
 * needs are asked for without making the server thread wait for them ({@link #chunk}).
 */
public final class BackgroundLocate {
    private static volatile Thread thread;
    private static final ExecutorService SEARCHES = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "Orbis-locate");
        t.setDaemon(true);
        thread = t;
        return t;
    });

    private BackgroundLocate() {}

    /** Whether this is the search thread. */
    public static boolean onSearchThread() {
        return Thread.currentThread() == thread;
    }

    /** Runs the search on the search thread, then {@code done} (or {@code failed}) with its result on the server thread. */
    public static <T> void run(MinecraftServer server, Supplier<T> search, Consumer<T> done, Consumer<Throwable> failed) {
        SEARCHES.execute(() -> {
            T result;
            try {
                result = search.get();
            } catch (Throwable t) {
                server.execute(() -> failed.accept(t));
                return;
            }
            server.execute(() -> done.accept(result));
        });
    }

    /**
     * A chunk for the search, which generates it if need be: asked of the server's chunk system without blocking the server thread (vanilla's
     * getChunk from another thread hands the call to the server thread, which then waits for the chunk).
     */
    public static ChunkAccess chunk(LevelReader level, int chunkX, int chunkZ, ChunkStatus status) {
        if (level instanceof ServerLevel serverLevel) {
            ChunkAccess chunk = serverLevel.getChunkSource().getChunkFuture(chunkX, chunkZ, status, true).join().orElse(null);
            if (chunk != null) return chunk;
        }
        return level.getChunk(chunkX, chunkZ, status);
    }
}
