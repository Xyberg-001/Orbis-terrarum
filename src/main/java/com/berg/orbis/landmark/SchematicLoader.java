package com.berg.orbis.landmark;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads WorldEdit/FAWE Sponge schematics (.schem, versions 1-3) and vanilla
 * structure-block files (.nbt) into a {@link Schematic}. Both are gzipped
 * NBT; the only difference is the tag layout.
 */
public final class SchematicLoader {

    private SchematicLoader() {}

    public static Schematic load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);

        Optional<CompoundTag> spongeV3 = root.getCompound("Schematic");
        if (spongeV3.isPresent() && spongeV3.get().contains("Blocks")) {
            return loadSpongeV3(name, spongeV3.get());
        }
        if (root.contains("BlockData") && root.contains("Palette")) {
            return loadSpongeV1V2(name, root);
        }
        if (root.contains("blocks") && root.contains("size")) {
            return loadVanilla(name, root);
        }
        if (root.contains("Blocks") && root.contains("Data") && root.contains("Width")) {
            throw new IOException(name + " is a legacy MCEdit .schematic with numeric block IDs; re-save it with WorldEdit as a Sponge .schem");
        }
        throw new IOException("Unrecognised schematic format in " + name + (lower.endsWith(".schem") || lower.endsWith(".nbt") ? "" : " (expected .schem or .nbt)"));
    }

    // ---- Sponge v1 / v2 -------------------------------------------------------

    private static Schematic loadSpongeV1V2(String name, CompoundTag root) throws IOException {
        int width = root.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = root.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = root.getShortOr("Length", (short) 0) & 0xFFFF;
        CompoundTag paletteTag = root.getCompoundOrEmpty("Palette");
        byte[] data = root.getByteArray("BlockData").orElseThrow(() -> new IOException("Missing BlockData"));
        int[] offset = root.getIntArray("Offset").orElse(new int[]{0, 0, 0});
        return decodeSponge(name, width, height, length, paletteTag, data, offset);
    }

    // ---- Sponge v3 ------------------------------------------------------------

    private static Schematic loadSpongeV3(String name, CompoundTag schem) throws IOException {
        int width = schem.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schem.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schem.getShortOr("Length", (short) 0) & 0xFFFF;
        CompoundTag blocks = schem.getCompoundOrEmpty("Blocks");
        CompoundTag paletteTag = blocks.getCompoundOrEmpty("Palette");
        byte[] data = blocks.getByteArray("Data").orElseThrow(() -> new IOException("Missing Blocks.Data"));
        int[] offset = schem.getIntArray("Offset").orElse(new int[]{0, 0, 0});
        return decodeSponge(name, width, height, length, paletteTag, data, offset);
    }

    private static Schematic decodeSponge(String name, int width, int height, int length, CompoundTag paletteTag,
                                          byte[] data, int[] offset) throws IOException {
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Schematic has zero size");
        int max = 0;
        Map<Integer, String> byId = new HashMap<>();
        for (String key : paletteTag.keySet()) {
            int id = paletteTag.getIntOr(key, -1);
            if (id < 0) continue;
            byId.put(id, key);
            max = Math.max(max, id);
        }
        BlockState[] palette = new BlockState[max + 1];
        for (int i = 0; i <= max; i++) {
            String key = byId.get(i);
            palette[i] = key == null ? Blocks.AIR.defaultBlockState() : parseState(key);
        }
        int total = width * height * length;
        int[] blocks = new int[total];
        int pos = 0;
        for (int i = 0; i < total; i++) {
            // varint
            int value = 0, shift = 0;
            while (true) {
                if (pos >= data.length) throw new IOException("BlockData truncated at block " + i);
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("Corrupt varint in BlockData");
            }
            blocks[i] = value < palette.length ? value : 0;
        }
        // Sponge order is (y * length + z) * width + x -- the same as ours.
        return new Schematic(name, width, height, length, palette, blocks, offset[0], offset[1], offset[2]);
    }

    // ---- vanilla structure block (.nbt) --------------------------------------

    private static Schematic loadVanilla(String name, CompoundTag root) throws IOException {
        ListTag size = root.getListOrEmpty("size");
        if (size.size() < 3) throw new IOException("Missing size");
        int width = size.getIntOr(0, 0), height = size.getIntOr(1, 0), length = size.getIntOr(2, 0);
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Structure has zero size");

        ListTag paletteList;
        Optional<ListTag> palettes = root.getList("palettes");
        if (palettes.isPresent() && !palettes.get().isEmpty()) {
            paletteList = palettes.get().getListOrEmpty(0);
        } else {
            paletteList = root.getListOrEmpty("palette");
        }
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag entry = paletteList.getCompoundOrEmpty(i);
            String blockName = entry.getStringOr("Name", "minecraft:air");
            StringBuilder sb = new StringBuilder(blockName);
            Optional<CompoundTag> props = entry.getCompound("Properties");
            if (props.isPresent() && !props.get().isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (String k : props.get().keySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(k).append('=').append(props.get().getStringOr(k, ""));
                }
                sb.append(']');
            }
            palette.add(parseState(sb.toString()));
        }
        int[] blocks = new int[width * height * length];
        java.util.Arrays.fill(blocks, -1);
        ListTag blockList = root.getListOrEmpty("blocks");
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag b = blockList.getCompoundOrEmpty(i);
            ListTag pos = b.getListOrEmpty("pos");
            if (pos.size() < 3) continue;
            int x = pos.getIntOr(0, 0), y = pos.getIntOr(1, 0), z = pos.getIntOr(2, 0);
            int state = b.getIntOr("state", 0);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length || state < 0 || state >= palette.size()) continue;
            blocks[(y * length + z) * width + x] = state;
        }
        // Structure void = "leave world alone".
        BlockState[] pal = palette.toArray(new BlockState[0]);
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] >= 0 && pal[blocks[i]].is(Blocks.STRUCTURE_VOID)) blocks[i] = -1;
        }
        return new Schematic(name, width, height, length, pal, blocks, 0, 0, 0);
    }

    private static BlockState parseState(String key) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
        } catch (CommandSyntaxException | RuntimeException e) {
            // Block from another mod / renamed in a newer version: substitute
            // something neutral rather than failing the whole landmark.
            return Blocks.STONE.defaultBlockState();
        }
    }

    static boolean isCompound(Tag t) {
        return t instanceof CompoundTag;
    }
}
