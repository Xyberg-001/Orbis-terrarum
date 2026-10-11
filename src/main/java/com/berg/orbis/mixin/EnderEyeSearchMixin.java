package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.StructureSearches;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An eye of ender in an Orbis world whose stronghold search is still running in the background (see {@link StructureSearches}) does
 * nothing, as vanilla's does when there is no stronghold: this says so, so that the player knows to try again in a moment.
 */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeSearchMixin {
    @Unique private static final ThreadLocal<Player> orbisterrarum$user = new ThreadLocal<>();

    @Inject(method = "use", at = @At("HEAD"))
    private void orbisterrarum$rememberUser(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        orbisterrarum$user.set(player);
    }

    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"))
    private BlockPos orbisterrarum$sayStillLooking(ServerLevel level, TagKey<Structure> target, BlockPos origin, int radius, boolean createReference) {
        BlockPos found = level.findNearestMapStructure(target, origin, radius, createReference);
        Player player = orbisterrarum$user.get();
        orbisterrarum$user.remove();
        if (found == null && player != null && StructureSearches.searching(level, target, origin, radius, createReference)) {
            player.sendOverlayMessage(Component.translatable("orbisterrarum.enderEye.searching"));
        }
        return found;
    }
}
