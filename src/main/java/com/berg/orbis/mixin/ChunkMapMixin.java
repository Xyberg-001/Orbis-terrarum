package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.FastPregen;
import com.berg.orbis.worldgen.HardLimit;
import com.berg.orbis.worldgen.PregenTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * The chunk map, loading and unloading chunks:
 * <ul>
 *   <li>an empty chunk the hard limit left that is inside the allowed area now reads as missing, so it generates;</li>
 *   <li>a chunk fast pre-generation built ahead (FastPregen) is handed over instead of being generated, but only
 *   when the disk has no chunk there (the disk always wins);</li>
 *   <li>while a pre-generation runs, chunks keep unloading. Minecraft unloads only in the time a tick has left over,
 *   unless more than 2,000 are waiting, and then only the excess; a sweep fills every tick, so finished chunks piled
 *   up at 2,000, still counted as loaded, and the sweep spent most of its time "waiting for unloads" (16 to 29
 *   chunks a second after starting at 105). An unload costs the server thread about 0.1 ms.</li>
 *   <li>saving: in Orbis worlds a chunk's save data, built on Minecraft's background threads, is also compressed
 *   there (worldgen/ChunkCompression), so the one disk thread only copies it into the region file.</li>
 * </ul>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Redirect(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Object> orbisterrarum$compressToo(java.util.function.Supplier<Object> build, java.util.concurrent.Executor executor) {
        if (!com.berg.orbis.worldgen.ChunkCompression.on(level.getServer())) return CompletableFuture.supplyAsync(build, executor);
        return CompletableFuture.supplyAsync(() -> {
            Object data = build.get();
            if (data instanceof CompoundTag tag) com.berg.orbis.worldgen.ChunkCompression.prepare(tag);
            return data;
        }, executor);
    }

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    @Final
    private Queue<Runnable> unloadQueue;

    @Shadow
    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow
    private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow
    private byte markPosition(ChunkPos pos, ChunkType type) {
        throw new AssertionError();
    }

    @Inject(method = "readChunk", at = @At("HEAD"), cancellable = true)
    private void orbis$regenerateEmptied(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (level.dimension() == Level.OVERWORLD && HardLimit.regenerate(pos.x(), pos.z())) {
            cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
        }
    }

    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void orbis$takeFastBuilt(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ProtoChunk built = FastPregen.claim(level, pos);
        if (built == null) return;
        // As vanilla does for a chunk that is not on disk (prefetch its points of interest, note it as a proto chunk),
        // with the built chunk in place of a new empty one; on disk after all, the usual load (the claim is gone).
        // Like vanilla's, these steps run on the server thread: the chunk-type table is a plain hash map, and
        // writing it from the background threads corrupted it (a crash, "Exception chunk generation/loading").
        cir.setReturnValue(readChunk(pos)
                .handle((saved, error) -> error == null && saved.isEmpty())
                .thenComposeAsync(notOnDisk -> notOnDisk
                        ? poiManager.prefetch(pos).thenApplyAsync(ignored -> {
                            markPosition(pos, ChunkType.PROTOCHUNK);
                            return (ChunkAccess) built;
                        }, mainThreadExecutor)
                        : scheduleChunkLoad(pos), mainThreadExecutor));
    }

    /** Vanilla: forced = max(0, queued - 2000). Returned here: queued - N, so at least N are unloaded this tick. */
    @ModifyConstant(method = "processUnloads", constant = @Constant(intValue = 2000))
    private int orbisterrarum$unloadDuringPregen(int vanilla) {
        int n = PregenTask.unloadsPerTick(level);
        return n <= 0 ? vanilla : Math.max(0, Math.min(vanilla, unloadQueue.size() - n));
    }
}
