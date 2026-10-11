package com.berg.orbis.mixin;

import com.berg.orbis.biome.RealWorldBiomeSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Room for vanilla's jigsaw structures at vanilla's own heights while Orbis chooses structure starts.
 *
 * Vanilla assembles an Ancient City around Y -27 and a trial chamber around Y -40..-20, adding a piece only where it
 * fits between the world's lowest and highest Y. An Orbis world with a fitted height can end far below that (Bergen
 * at 1:2: Y -2032..-577), so only the first piece fitted and the city was its centre alone (4 Oct 2026). Orbis moves
 * these structures into the underground band afterwards, so while it chooses starts
 * ({@link RealWorldBiomeSource#VANILLA_FRAME}) the assembly sees the world's range widened to take in vanilla's
 * Y -64..319 as well. It is never narrowed.
 */
@Mixin(JigsawPlacement.class)
public abstract class JigsawPlacementMixin {

    private static final int VANILLA_MIN_Y = -64, VANILLA_MAX_Y = 319;

    @Redirect(method = "addPieces(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/Holder;Ljava/util/Optional;ILnet/minecraft/core/BlockPos;ZLjava/util/Optional;Lnet/minecraft/world/level/levelgen/structure/structures/JigsawStructure$MaxDistance;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/pools/DimensionPadding;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)Ljava/util/Optional;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;heightAccessor()Lnet/minecraft/world/level/LevelHeightAccessor;"))
    private static LevelHeightAccessor orbisterrarum$vanillaHeights(Structure.GenerationContext context) {
        LevelHeightAccessor real = context.heightAccessor();
        if (!RealWorldBiomeSource.VANILLA_FRAME.get()) return real;
        int min = Math.min(real.getMinY(), VANILLA_MIN_Y);
        int max = Math.max(real.getMaxY(), VANILLA_MAX_Y);
        if (min == real.getMinY() && max == real.getMaxY()) return real;
        return LevelHeightAccessor.create(min, max - min + 1);
    }
}
