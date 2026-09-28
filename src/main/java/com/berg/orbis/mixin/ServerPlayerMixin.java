package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server side of the sky fix, so it works for vanilla clients too.
 *
 * A vanilla client paints a black "void" disc under the horizon whenever the
 * camera is below Y 63, unless the server told it the world is flat; for
 * flat worlds it uses the dimension floor as the horizon instead, and only
 * darkens the fog within one block of that floor. Real sea level here is at
 * Y -1700, so every login/respawn packet for a Orbis Terrarum world is flagged
 * "flat". The client uses that flag for nothing else (horizon height and
 * void-darkness onset only), and the server never reads it back.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "createCommonSpawnInfo", at = @At("RETURN"), cancellable = true)
    private void orbisterrarum$flagFlatForHorizon(ServerLevel level, CallbackInfoReturnable<CommonPlayerSpawnInfo> cir) {
        if (!(level.getChunkSource().getGenerator() instanceof RealWorldChunkGenerator)) return;
        CommonPlayerSpawnInfo info = cir.getReturnValue();
        if (info == null || info.isFlat()) return;
        cir.setReturnValue(new CommonPlayerSpawnInfo(info.dimensionType(), info.dimension(), info.seed(), info.gameType(),
                info.previousGameType(), info.isDebug(), true, info.lastDeathLocation(), info.portalCooldown(), info.seaLevel()));
    }
}
