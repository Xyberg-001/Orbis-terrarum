package com.berg.orbis.mixin;

import java.time.Duration;
import java.util.Optional;

import com.berg.orbis.worldgen.BackgroundLocate;
import com.berg.orbis.worldgen.RealWorldChunkGenerator;
import com.google.common.base.Stopwatch;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /locate structure and /locate biome in an Orbis Terrarum world search in the background (see {@link BackgroundLocate}): the command
 * answers at once that it is searching, and gives vanilla's answer when the search is done. The command's own result is then 1 (started)
 * rather than the distance.
 */
@Mixin(LocateCommand.class)
public abstract class LocateCommandMixin {

    @Shadow
    private static Optional<? extends HolderSet.ListBacked<Structure>> getHolders(ResourceOrTagKeyArgument.Result<Structure> resourceOrTag,
                                                                                 Registry<Structure> registry) {
        throw new AssertionError();
    }

    private static boolean orbisterrarum$orbisLevel(CommandSourceStack source) {
        return source.getLevel().getChunkSource().getGenerator() instanceof RealWorldChunkGenerator;
    }

    @Inject(method = "locateStructure", at = @At("HEAD"), cancellable = true)
    private static void orbisterrarum$locateStructureInBackground(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> resourceOrTag,
                                                                CallbackInfoReturnable<Integer> cir) {
        if (!orbisterrarum$orbisLevel(source)) return;
        ServerLevel level = source.getLevel();
        Optional<? extends HolderSet.ListBacked<Structure>> holders = getHolders(resourceOrTag, level.registryAccess().lookupOrThrow(Registries.STRUCTURE));
        if (holders.isEmpty()) return; // vanilla reports it
        HolderSet<Structure> target = holders.get();
        BlockPos from = BlockPos.containing(source.getPosition());
        String name = resourceOrTag.asPrintable();
        orbisterrarum$searching(source, name);
        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        BackgroundLocate.<Pair<BlockPos, Holder<Structure>>>run(level.getServer(),
                () -> level.getChunkSource().getGenerator().findNearestMapStructure(level, target, from, 100, false),
                nearest -> {
                    if (nearest == null) {
                        source.sendFailure(Component.translatableEscape("commands.locate.structure.not_found", name));
                    } else {
                        LocateCommand.showLocateResult(source, resourceOrTag, from, nearest, "commands.locate.structure.success", false,
                                orbisterrarum$elapsed(stopwatch));
                    }
                },
                failure -> orbisterrarum$failed(source, name, failure));
        cir.setReturnValue(1);
    }

    @Inject(method = "locateBiome", at = @At("HEAD"), cancellable = true)
    private static void orbisterrarum$locateBiomeInBackground(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> elementOrTag,
                                                            CallbackInfoReturnable<Integer> cir) {
        if (!orbisterrarum$orbisLevel(source)) return;
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPos.containing(source.getPosition());
        String name = elementOrTag.asPrintable();
        orbisterrarum$searching(source, name);
        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        BackgroundLocate.<Pair<BlockPos, Holder<Biome>>>run(level.getServer(),
                () -> level.findClosestBiome3d(elementOrTag, from, 6400, 32, 64),
                nearest -> {
                    if (nearest == null) {
                        source.sendFailure(Component.translatableEscape("commands.locate.biome.not_found", name));
                    } else {
                        LocateCommand.showLocateResult(source, elementOrTag, from, nearest, "commands.locate.biome.success", true,
                                orbisterrarum$elapsed(stopwatch));
                    }
                },
                failure -> orbisterrarum$failed(source, name, failure));
        cir.setReturnValue(1);
    }

    private static void orbisterrarum$searching(CommandSourceStack source, String name) {
        source.sendSuccess(() -> Component.literal("Searching for " + name + "; the answer follows here (the map data of the places looked at may"
                + " need downloading first)"), false);
    }

    private static Duration orbisterrarum$elapsed(Stopwatch stopwatch) {
        stopwatch.stop();
        return stopwatch.elapsed();
    }

    private static void orbisterrarum$failed(CommandSourceStack source, String name, Throwable failure) {
        System.err.println("[orbis] Search for " + name + " failed: " + failure);
        source.sendFailure(Component.literal("The search for " + name + " failed: " + failure.getMessage()));
    }
}
