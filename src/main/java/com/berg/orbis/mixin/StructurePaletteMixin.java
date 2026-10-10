package com.berg.orbis.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A structure template's blocks by kind, cached in a map that several threads can fill at once. Vanilla's is a plain HashMap, and placing
 * the same template from many chunks together (a cubic world decorates many chunks in parallel) threw ConcurrentModificationException,
 * leaving out that chunk's part of an ancient city or trial chambers.
 */
@Mixin(StructureTemplate.Palette.class)
public class StructurePaletteMixin {
    @Shadow @Final @Mutable private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void orbis$concurrentCache(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}
