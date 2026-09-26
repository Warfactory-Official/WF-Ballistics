package com.wf.wflib.rail.excavate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;

/** Reads and rewrites the paletted block data of one chunk section, as it is stored on disk. */
public final class SectionNbt {

    public static final String BLOCK_STATES = "block_states";
    public static final String PALETTE = "palette";
    public static final String DATA = "data";
    public static final String NAME = "Name";
    public static final String PROPERTIES = "Properties";
    public static final String AIR = "minecraft:air";

    /** Cells in a 16x16x16 section. */
    public static final int CELLS = 4096;

    private SectionNbt() {
    }

    /** Which cells of a section to clear, in section-local coordinates. */
    @FunctionalInterface
    public interface CellMask {
        boolean test(int x, int y, int z);

        /** @return whether every cell is selected, letting a full section collapse to a single-value palette. */
        default boolean coversAll() {
            return false;
        }
    }

    /**
     * @param cleared cells actually changed to air
     * @param rewritten whether the section tag was modified
     * @param refusal why the section was left alone, or null when it was understood
     */
    public record Result(int cleared, boolean rewritten, String refusal) {

        static final Result NOTHING = new Result(0, false, null);

        static Result refused(String why) {
            return new Result(0, false, why);
        }
    }

    /** @return the index MC packs a section's block states at. */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /**
     * @return the bit width a palette of this size is serialised at, for section block states.
     */
    public static int bitsForPalette(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        return paletteSize <= 16 ? 4 : Mth.ceillog2(paletteSize);
    }

    /** @return how many longs 4096 cells occupy at this bit width, entries never straddling a long. */
    public static int longsForBits(int bits) {
        int perLong = 64 / bits;
        return (CELLS + perLong - 1) / perLong;
    }

    /**
     * Point every cell the mask selects at air.
     *
     * @param section one entry of the chunk tag's {@code sections} list; mutated in place
     */
    public static Result clear(CompoundTag section, CellMask mask) {
        return fill(section, mask, AIR);
    }

    /**
     * Point every cell the mask selects at one block.
     *
     * <p>Only blocks with no state properties, which is what a tunnel lining is. A block that needs
     * properties would have to carry them through the palette entry and match on them too, and nothing
     * here needs that.</p>
     *
     * @param blockName a registry id such as {@code minecraft:deepslate_bricks}
     */
    public static Result fill(CompoundTag section, CellMask mask, String blockName) {
        if (!section.contains(BLOCK_STATES, Tag.TAG_COMPOUND)) {
            if (AIR.equals(blockName)) {
                // No block data stored: the section is air already.
                return Result.NOTHING;
            }
            // An all-air section still has to be able to take a lining.
            CompoundTag states = new CompoundTag();
            ListTag palette = new ListTag();
            palette.add(entryFor(AIR));
            states.put(PALETTE, palette);
            section.put(BLOCK_STATES, states);
        }
        CompoundTag states = section.getCompound(BLOCK_STATES);
        ListTag palette = states.getList(PALETTE, Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            return Result.refused("empty palette");
        }

        boolean hasData = states.contains(DATA, Tag.TAG_LONG_ARRAY);
        if (!hasData) {
            return palette.size() == 1
                    ? fillUniform(states, palette, mask, blockName)
                    : Result.refused("palette of " + palette.size() + " with no data array");
        }

        long[] data = states.getLongArray(DATA);
        int bits = bitsForPalette(palette.size());
        if (bits == 0 || longsForBits(bits) != data.length) {
            // Stored width disagrees with the palette; decoding it would corrupt every cell.
            return Result.refused("palette " + palette.size() + " implies " + bits
                    + " bits (" + (bits == 0 ? 0 : longsForBits(bits)) + " longs) but found " + data.length);
        }

        SimpleBitStorage storage = new SimpleBitStorage(bits, CELLS, data);
        int target = indexOf(palette, blockName);

        int[] targets = new int[CELLS];
        int count = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!mask.test(x, y, z)) {
                        continue;
                    }
                    int cell = index(x, y, z);
                    if (target < 0 || storage.get(cell) != target) {
                        targets[count++] = cell;
                    }
                }
            }
        }
        if (count == 0) {
            return Result.NOTHING;
        }

        if (target < 0) {
            palette.add(entryFor(blockName));
            target = palette.size() - 1;
        }
        int wideBits = bitsForPalette(palette.size());
        SimpleBitStorage out = wideBits == bits ? storage : widen(storage, wideBits);
        for (int i = 0; i < count; i++) {
            out.set(targets[i], target);
        }
        states.put(DATA, new LongArrayTag(out.getRaw()));
        return new Result(count, true, null);
    }

    /** A section stored as one repeated state: either already the target, or expanded so part of it can change. */
    private static Result fillUniform(CompoundTag states, ListTag palette, CellMask mask, String blockName) {
        if (is(palette.getCompound(0), blockName)) {
            return Result.NOTHING;
        }
        if (mask.coversAll()) {
            ListTag replacement = new ListTag();
            replacement.add(entryFor(blockName));
            states.put(PALETTE, replacement);
            states.remove(DATA);
            return new Result(CELLS, true, null);
        }

        // Expand to a two-entry palette so the selected cells can differ from the rest.
        palette.add(entryFor(blockName));
        SimpleBitStorage storage = new SimpleBitStorage(4, CELLS);
        int cleared = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (mask.test(x, y, z)) {
                        storage.set(index(x, y, z), 1);
                        cleared++;
                    }
                }
            }
        }
        if (cleared == 0) {
            palette.remove(palette.size() - 1);
            return Result.NOTHING;
        }
        states.put(DATA, new LongArrayTag(storage.getRaw()));
        return new Result(cleared, true, null);
    }

    /** Copy every cell into a storage of a wider bit width, which appending to the palette can force. */
    private static SimpleBitStorage widen(SimpleBitStorage from, int bits) {
        SimpleBitStorage to = new SimpleBitStorage(bits, CELLS);
        for (int cell = 0; cell < CELLS; cell++) {
            to.set(cell, from.get(cell));
        }
        return to;
    }

    private static int indexOf(ListTag palette, String blockName) {
        for (int i = 0; i < palette.size(); i++) {
            if (is(palette.getCompound(i), blockName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a palette entry is this block in its default state.
     *
     * <p>A {@code Properties} tag means it is some other state of the same block, which must not be
     * treated as already correct: {@code water[level=3]} is not {@code water}, and a lining that
     * skipped it would leave a flowing cell in the middle of a wall.</p>
     */
    private static boolean is(CompoundTag entry, String blockName) {
        return blockName.equals(entry.getString(NAME)) && !entry.contains(PROPERTIES, Tag.TAG_COMPOUND);
    }

    private static CompoundTag entryFor(String blockName) {
        CompoundTag entry = new CompoundTag();
        entry.putString(NAME, blockName);
        return entry;
    }
}
