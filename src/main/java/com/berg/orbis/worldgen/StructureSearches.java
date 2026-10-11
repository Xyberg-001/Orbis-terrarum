package com.berg.orbis.worldgen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The structure searches of play in an Orbis Terrarum world (an eye of ender looking for a stronghold, a dolphin for a shipwreck, an
 * explorer map from a cartographer or a chest) away from the server thread, as /locate's are ({@link BackgroundLocate}). Vanilla searches on
 * the server thread, and each place it checks may need map data not downloaded yet: the server stood still, and the watchdog ended it.
 * <p>
 * Asked on the server thread, a search for the area (a 256-block square) answers at once if one has finished there, else starts one and
 * answers "none yet": the eye of ender then does nothing (and says why), a dolphin looks again later, a map stays blank. An answer is kept
 * for a while for the next eye or dolphin there; one that marks the structure as found (an explorer map's) is taken once.
 */
public final class StructureSearches {
    private static final int CELL_SHIFT = 8;
    private static final long KEEP_MS = 10 * 60_000L;
    private static final long KEEP_FAILED_MS = 60_000L;

    /** {@code target}: a structure tag, or the set of structures an exploration map points to (the same object each time). */
    private record Key(ResourceKey<Level> dimension, Object target, int cellX, int cellZ, int radius, boolean createReference) {
    }

    private static final class Search {
        volatile boolean done;
        volatile BlockPos result;
        volatile long doneAt;
    }

    private static final Map<Key, Search> SEARCHES = new ConcurrentHashMap<>();

    /** What the server thread is answered: whether this handles the search, and if so its answer for now. */
    public record Answer(boolean handled, BlockPos pos) {
        static final Answer NOT_HANDLED = new Answer(false, null);
    }

    private StructureSearches() {}

    private static Key key(ServerLevel level, Object target, BlockPos origin, int radius, boolean createReference) {
        return new Key(level.dimension(), target, origin.getX() >> CELL_SHIFT, origin.getZ() >> CELL_SHIFT, radius, createReference);
    }

    /** For ServerLevel.findNearestMapStructure on the server thread of an Orbis world: a finished search's answer, or a search started. */
    public static Answer answer(ServerLevel level, TagKey<Structure> target, BlockPos origin, int radius, boolean createReference) {
        return answer(level, target, target.location().toString(), origin, radius, createReference,
                () -> level.findNearestMapStructure(target, origin, radius, createReference));
    }

    /** The same for a set of structures (an exploration map's). */
    public static Answer answer(ServerLevel level, net.minecraft.core.HolderSet<Structure> target, BlockPos origin, int radius, boolean createReference) {
        Object key = target.unwrapKey().map(Object.class::cast).orElse(target);
        return answer(level, key, String.valueOf(key), origin, radius, createReference, () -> {
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, target, origin, radius, createReference);
            return found == null ? null : found.getFirst();
        });
    }

    private static Answer answer(ServerLevel level, Object target, String name, BlockPos origin, int radius, boolean createReference,
                                 java.util.function.Supplier<BlockPos> searchTask) {
        if (BackgroundLocate.onSearchThread() || !level.getServer().isSameThread()
                || !(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) {
            return Answer.NOT_HANDLED;
        }
        Key key = key(level, target, origin, radius, createReference);
        Search search = SEARCHES.get(key);
        long now = System.currentTimeMillis();
        if (search != null && search.done) {
            long keep = search.result == null ? KEEP_FAILED_MS : KEEP_MS;
            if (createReference) {
                SEARCHES.remove(key, search); // the structure is now known: the next map looks for another
                return new Answer(true, search.result);
            }
            if (now - search.doneAt < keep) return new Answer(true, search.result);
            SEARCHES.remove(key, search);
            search = null;
        }
        if (search == null) {
            Search started = new Search();
            if (SEARCHES.putIfAbsent(key, started) == null) {
                BlockPos from = origin.immutable();
                long startedAt = System.currentTimeMillis();
                BackgroundLocate.<BlockPos>run(level.getServer(), searchTask,
                        found -> {
                            System.out.println("[orbis] Structure search for " + name + " near " + from.toShortString() + ": "
                                    + (found == null ? "none" : found.toShortString()) + " (" + (System.currentTimeMillis() - startedAt) / 1000 + " s)");
                            finish(started, found);
                        },
                        failure -> {
                            System.err.println("[orbis] Structure search for " + name + " near " + from.toShortString() + " failed: " + failure);
                            finish(started, null);
                        });
            }
        }
        return new Answer(true, null);
    }

    private static void finish(Search search, BlockPos found) {
        search.result = found;
        search.doneAt = System.currentTimeMillis();
        search.done = true;
    }

    /** Whether a search for the area is under way (an eye of ender says it is still looking). */
    public static boolean searching(ServerLevel level, TagKey<Structure> target, BlockPos origin, int radius, boolean createReference) {
        Search search = SEARCHES.get(key(level, target, origin, radius, createReference));
        return search != null && !search.done;
    }

    /** Forgets every search (the server stopped). */
    public static void clear() {
        SEARCHES.clear();
    }
}
