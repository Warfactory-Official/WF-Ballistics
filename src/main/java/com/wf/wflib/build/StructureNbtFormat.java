package com.wf.wflib.build;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads vanilla structure NBT: what a structure block saves, and what {@code /place template} loads. */
public final class StructureNbtFormat implements BlueprintFormat {

    public static final StructureNbtFormat INSTANCE = new StructureNbtFormat();

    private StructureNbtFormat() {
    }

    @Override
    public String id() {
        return "structure";
    }

    @Override
    public String extension() {
        return "nbt";
    }

    @Override
    public Blueprint read(CompoundTag root, HolderGetter<Block> blocks, String name)
            throws BlueprintException {
        Vec3i size = readVec(root.getList("size", Tag.TAG_INT));
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new BlueprintException("not a structure template: missing or empty size");
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > Blueprint.MAX_VOLUME) {
            throw new BlueprintException("structure is " + volume + " cells, limit is "
                    + Blueprint.MAX_VOLUME);
        }

        ListTag paletteTag = root.contains("palette", Tag.TAG_LIST)
                ? root.getList("palette", Tag.TAG_COMPOUND)
                : root.getList("palettes", Tag.TAG_LIST).isEmpty()
                        ? new ListTag()
                        : (ListTag) root.getList("palettes", Tag.TAG_LIST).get(0);
        if (paletteTag.isEmpty()) {
            throw new BlueprintException("not a structure template: no palette");
        }
        if (paletteTag.size() > Blueprint.MAX_PALETTE) {
            throw new BlueprintException("structure has more than " + Blueprint.MAX_PALETTE
                    + " distinct block states");
        }

        PaletteReader reader = new PaletteReader(blocks, root.getInt("DataVersion"));
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        palette.put(Blocks.AIR.defaultBlockState(), 0);
        int[] remap = new int[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            BlockState state = reader.read(paletteTag.getCompound(i));
            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
                remap[i] = 0;
                continue;
            }
            Integer existing = palette.get(state);
            if (existing != null) {
                remap[i] = existing;
            } else {
                remap[i] = palette.size();
                palette.put(state, remap[i]);
            }
        }

        int[] cells = new int[(int) volume];
        int blockEntities = 0;
        ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag entry = blocksTag.getCompound(i);
            Vec3i at = readVec(entry.getList("pos", Tag.TAG_INT));
            if (at == null || at.getX() < 0 || at.getY() < 0 || at.getZ() < 0
                    || at.getX() >= size.getX() || at.getY() >= size.getY() || at.getZ() >= size.getZ()) {
                continue;
            }
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                blockEntities++;
            }
            int index = entry.getInt("state");
            cells[(at.getY() * size.getZ() + at.getZ()) * size.getX() + at.getX()] =
                    index >= 0 && index < remap.length ? remap[index] : 0;
        }

        return new Blueprint(name, size, List.copyOf(palette.keySet()), cells, blockEntities,
                reader.unresolved());
    }

    private static Vec3i readVec(ListTag list) {
        return list.size() == 3 ? new Vec3i(list.getInt(0), list.getInt(1), list.getInt(2)) : null;
    }
}
