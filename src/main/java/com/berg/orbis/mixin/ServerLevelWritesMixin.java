package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft's "sync chunk writes" (options.txt in singleplayer, server.properties on a server, on by default on
 * Windows) forces every saved chunk to the disk before the next. A level reads it once, when it is created; for an
 * Orbis world with the "Fast chunk writes" setting on it reads false (see {@link OrbisMod#fastChunkWrites}).
 * options.txt, server.properties and every other world stay as they are.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWritesMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;forceSynchronousWrites()Z"))
    private boolean orbisterrarum$fastWrites(MinecraftServer server) {
        return !OrbisMod.fastChunkWrites(server) && server.forceSynchronousWrites();
    }
}
