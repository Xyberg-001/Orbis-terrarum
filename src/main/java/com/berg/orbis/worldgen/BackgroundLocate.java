package com.berg.orbis.worldgen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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

    /**
     * What keeps a chunk the search waits for loading: vanilla's request holds it with a ticket that lasts one tick (vanilla's caller makes
     * the server thread wait meanwhile), so a chunk that took longer was dropped half made and the request failed. This one lasts a minute
     * and is renewed while the search waits.
     */
    private static TicketType ticket;
    private static final long TICKET_TICKS = 1200;
    /** How long the search waits for one chunk before giving up (map data for far places can take minutes to download). */
    private static final long CHUNK_WAIT_MS = 10 * 60_000L;

    private BackgroundLocate() {}

    /** Registers the search's ticket type (at startup, while the registries are open). */
    public static void register() {
        ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("orbisterrarum", "locate"),
                new TicketType(TICKET_TICKS, TicketType.FLAG_LOADING));
    }

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
     * A chunk for the search, which generates it if need be: asked of the server's chunk system without the server thread ever waiting for it
     * (vanilla's getChunk from another thread hands the wait to the server thread, which then stops ticking until the chunk is made: the
     * watchdog ended the server when a far chunk's map data took a minute). The search's own ticket keeps the chunk loading meanwhile.
     */
    public static ChunkAccess chunk(LevelReader level, int chunkX, int chunkZ, ChunkStatus status) {
        if (!(level instanceof ServerLevel serverLevel) || ticket == null) return level.getChunk(chunkX, chunkZ, status);
        MinecraftServer server = serverLevel.getServer();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        int ticketLevel = ChunkLevel.byStatus(status);
        long giveUp = System.currentTimeMillis() + CHUNK_WAIT_MS;
        while (System.currentTimeMillis() < giveUp) {
            // (re)new the ticket, then ask; a request still running is the same one each time
            server.submit(() -> serverLevel.getChunkSource().addTicket(new Ticket(ticket, ticketLevel), pos)).join();
            try {
                ChunkResult<ChunkAccess> result = serverLevel.getChunkSource().getChunkFuture(chunkX, chunkZ, status, true)
                        .get(20, TimeUnit.SECONDS);
                ChunkAccess chunk = result.orElse(null);
                if (chunk != null) return chunk;
                Thread.sleep(1000); // not made this time (unloaded meanwhile): ask again
            } catch (TimeoutException e) {
                // still being made: renew the ticket and keep waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("search interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("chunk " + pos + " could not be made: " + e.getCause(), e.getCause());
            }
        }
        throw new IllegalStateException("chunk " + pos + " was not made within " + CHUNK_WAIT_MS / 60_000 + " minutes (map data still downloading?)");
    }
}
