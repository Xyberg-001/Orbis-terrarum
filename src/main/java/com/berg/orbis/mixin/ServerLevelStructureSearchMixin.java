package com.berg.orbis.mixin;

import com.berg.orbis.worldgen.StructureSearches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The structure searches of play (eyes of ender, dolphins, explorer maps) in an Orbis world run in the background: see {@link StructureSearches}. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelStructureSearchMixin {
    @Inject(method = "findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;",
            at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$searchInBackground(TagKey<Structure> target, BlockPos origin, int radius, boolean createReference,
                                                  CallbackInfoReturnable<BlockPos> cir) {
        StructureSearches.Answer answer = StructureSearches.answer((ServerLevel) (Object) this, target, origin, radius, createReference);
        if (answer.handled()) cir.setReturnValue(answer.pos());
    }

    /** 26.3's exploration maps ask with a set of structures (26.2 has no such method: skipped there). */
    @Inject(method = "findNearestMapStructure(Lnet/minecraft/core/HolderSet;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void orbisterrarum$searchSetInBackground(net.minecraft.core.HolderSet<Structure> target, BlockPos origin, int radius,
                                                     boolean createReference, CallbackInfoReturnable<BlockPos> cir) {
        StructureSearches.Answer answer = StructureSearches.answer((ServerLevel) (Object) this, target, origin, radius, createReference);
        if (answer.handled()) cir.setReturnValue(answer.pos());
    }
}
