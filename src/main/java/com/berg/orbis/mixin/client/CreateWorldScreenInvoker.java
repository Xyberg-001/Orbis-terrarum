package com.berg.orbis.mixin.client;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {

    @Invoker("onCreate")
    void orbis$invokeOnCreate();

    /** The folder whose data packs become the new world's; created on demand. */
    @Invoker("getOrCreateTempDataPackDir")
    Path orbis$tempDataPackDir();

    /** A pack repository over that folder, selected according to the configuration. */
    @Invoker("getDataPackSelectionSettings")
    Pair<Path, PackRepository> orbis$dataPackSelection(WorldDataConfiguration configuration);

    /** Reloads the world-creation context with the repository's selected packs (what the data-pack screen does). */
    @Invoker("tryApplyNewDataPacks")
    void orbis$applyDataPacks(PackRepository repository, boolean reset, Consumer<WorldDataConfiguration> then);
}
