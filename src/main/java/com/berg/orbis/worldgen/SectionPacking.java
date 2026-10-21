package com.berg.orbis.worldgen;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Optional;

/**
 * Packing a section for saving when it holds one block (or biome) only: Minecraft's {@code PalettedContainer.pack} builds a
 * new palette, a 4,096-entry array and new storage for every section, even an empty one, and in an Orbis world 86% of the
 * sections are one block (82% air; Bergen, 122 sections a chunk). That was a third of everything a pre-generation
 * allocated (650 MB/s, 17 Oct 2026). Here such a section is seen from its storage (at most 256 longs compared, nothing
 * allocated) and given what {@code pack} would return: the one entry, no storage, the bits Minecraft's strategy gives a
 * palette of one. Anything else, or anything unexpected, goes to Minecraft's own packing (mixin/PalettedContainerPackMixin).
 */
public final class SectionPacking {
    private static final VarHandle DATA;
    private static final MethodHandle STORAGE, PALETTE, CONFIG_FOR_SIZE;
    private static volatile boolean broken;

    static {
        VarHandle data = null;
        MethodHandle storage = null, palette = null, config = null;
        try {
            Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            data = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup()).findVarHandle(PalettedContainer.class, "data", dataClass);
            MethodHandles.Lookup ld = MethodHandles.privateLookupIn(dataClass, MethodHandles.lookup());
            storage = ld.findVirtual(dataClass, "storage", MethodType.methodType(BitStorage.class));
            palette = ld.findVirtual(dataClass, "palette", MethodType.methodType(Palette.class));
            config = MethodHandles.privateLookupIn(Strategy.class, MethodHandles.lookup())
                    .findVirtual(Strategy.class, "getConfigurationForPaletteSize", MethodType.methodType(Configuration.class, int.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[orbis] Fast section packing off (" + e + "); Minecraft packs every section");
            broken = true;
        }
        DATA = data;
        STORAGE = storage;
        PALETTE = palette;
        CONFIG_FOR_SIZE = config;
    }

    private SectionPacking() {
    }

    /** What packing the container gives when it holds one value only, or null (pack it the usual way). */
    @SuppressWarnings("unchecked")
    public static <T> PalettedContainerRO.PackedData<T> uniform(PalettedContainer<T> container, Strategy<T> strategy) {
        if (broken) return null;
        try {
            Object data = DATA.getVolatile(container);
            BitStorage st = (BitStorage) STORAGE.invoke(data);
            Palette<T> pal = (Palette<T>) PALETTE.invoke(data);
            int bits = st.getBits(), id;
            if (bits == 0) {
                id = 0;
            } else {
                id = st.get(0);
                long[] raw = st.getRaw();
                int perLong = 64 / bits, size = st.getSize();
                long mask = bits == 64 ? -1L : (1L << bits) - 1, pattern = 0;
                for (int k = 0; k < perLong; k++) pattern |= ((long) id & mask) << (k * bits);
                int full = size / perLong, rest = size - full * perLong;
                if (raw.length < full + (rest > 0 ? 1 : 0)) return null;
                for (int i = 0; i < full; i++) if (raw[i] != pattern) return null;
                if (rest > 0) {
                    long m = rest * bits >= 64 ? -1L : (1L << (rest * bits)) - 1;
                    if ((raw[full] & m) != (pattern & m)) return null;
                }
            }
            T value = pal.valueFor(id);
            if (value == null) return null;
            int bitsOut = ((Configuration) CONFIG_FOR_SIZE.invoke(strategy, 1)).bitsInStorage();
            if (bitsOut != 0) return null; // would need storage after all: Minecraft's way
            return new PalettedContainerRO.PackedData<>(List.of(value), Optional.empty(), bitsOut);
        } catch (Throwable e) {
            if (!broken) {
                broken = true;
                System.err.println("[orbis] Fast section packing off (" + e + "); Minecraft packs every section");
            }
            return null;
        }
    }
}
