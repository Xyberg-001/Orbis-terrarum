package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.BackgroundLocate;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A structure search that has found a likely start loads the start's chunk to check it. On the background search thread
 * ({@link BackgroundLocate}) the chunk is asked for without the server thread waiting for it (vanilla's call would hand it to the server
 * thread, which would then stop ticking until the chunk is generated).
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {

    @Redirect(method = "getStructureGeneratingAt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelReader;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private static ChunkAccess orbisterrarum$chunkWithoutWaitingServer(LevelReader level, int chunkX, int chunkZ, ChunkStatus status) {
        return BackgroundLocate.onSearchThread() ? BackgroundLocate.chunk(level, chunkX, chunkZ, status) : level.getChunk(chunkX, chunkZ, status);
    }
}
