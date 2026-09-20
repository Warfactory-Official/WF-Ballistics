package com.wf.wflib.rail.excavate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The section rewriter is the one place an excavation can corrupt a save rather than merely fail, so the
 * cases that matter are the boundaries: a palette that grows past its bit width, and stored data whose layout
 * does not match what the palette implies.
 */
class SectionNbtTest {

    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    // ------------------------------------------------------------------ helpers

    /** Build a section tag the way the game stores one: palette plus packed indices, or a bare palette. */
    private static CompoundTag section(List<String> palette, int[] cells) {
        CompoundTag states = new CompoundTag();
        ListTag list = new ListTag();
        for (String name : palette) {
            CompoundTag entry = new CompoundTag();
            entry.putString(SectionNbt.NAME, name);
            list.add(entry);
        }
        states.put(SectionNbt.PALETTE, list);
        if (cells != null) {
            int bits = SectionNbt.bitsForPalette(palette.size());
            SimpleBitStorage storage = new SimpleBitStorage(bits, SectionNbt.CELLS);
            for (int i = 0; i < SectionNbt.CELLS; i++) {
                storage.set(i, cells[i]);
            }
            states.put(SectionNbt.DATA, new LongArrayTag(storage.getRaw()));
        }
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put(SectionNbt.BLOCK_STATES, states);
        return section;
    }

    /** Read the section back the way the game would. */
    private static int[] decode(CompoundTag section) {
        CompoundTag states = section.getCompound(SectionNbt.BLOCK_STATES);
        ListTag palette = states.getList(SectionNbt.PALETTE, Tag.TAG_COMPOUND);
        int[] out = new int[SectionNbt.CELLS];
        if (!states.contains(SectionNbt.DATA, Tag.TAG_LONG_ARRAY)) {
            return out;
        }
        int bits = SectionNbt.bitsForPalette(palette.size());
        SimpleBitStorage storage = new SimpleBitStorage(bits, SectionNbt.CELLS, states.getLongArray(SectionNbt.DATA));
        for (int i = 0; i < SectionNbt.CELLS; i++) {
            out[i] = storage.get(i);
        }
        return out;
    }

    private static List<String> paletteNames(CompoundTag section) {
        ListTag list = section.getCompound(SectionNbt.BLOCK_STATES).getList(SectionNbt.PALETTE, Tag.TAG_COMPOUND);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            names.add(list.getCompound(i).getString(SectionNbt.NAME));
        }
        return names;
    }

    private static SectionNbt.CellMask everything() {
        return new SectionNbt.CellMask() {
            @Override
            public boolean test(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean coversAll() {
                return true;
            }
        };
    }

    /** The bottom layer only - a partial mask that cannot collapse the section. */
    private static SectionNbt.CellMask bottomLayer() {
        return (x, y, z) -> y == 0;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("cell index matches the order the game packs a section in")
    void cellIndexOrder() {
        assertEquals(0, SectionNbt.index(0, 0, 0));
        assertEquals(1, SectionNbt.index(1, 0, 0));
        assertEquals(16, SectionNbt.index(0, 0, 1));
        assertEquals(256, SectionNbt.index(0, 1, 0));
        assertEquals(4095, SectionNbt.index(15, 15, 15));
    }

    @Test
    @DisplayName("bit width follows the palette: 4 up to sixteen entries, ceillog2 above")
    void bitWidths() {
        assertEquals(0, SectionNbt.bitsForPalette(1));
        assertEquals(4, SectionNbt.bitsForPalette(2));
        assertEquals(4, SectionNbt.bitsForPalette(16));
        assertEquals(5, SectionNbt.bitsForPalette(17));
        assertEquals(5, SectionNbt.bitsForPalette(32));
        assertEquals(6, SectionNbt.bitsForPalette(33));
    }

    @Nested
    @DisplayName("a section stored as one repeated block")
    class Uniform {

        @Test
        @DisplayName("collapses to a bare air palette when the whole section goes")
        void wholeSectionCollapses() {
            CompoundTag section = section(List.of(STONE), null);
            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertNull(result.refusal());
            assertEquals(SectionNbt.CELLS, result.cleared());
            assertEquals(List.of(SectionNbt.AIR), paletteNames(section));
            assertFalse(section.getCompound(SectionNbt.BLOCK_STATES).contains(SectionNbt.DATA, Tag.TAG_LONG_ARRAY),
                    "a single-entry palette carries no data array");
        }

        @Test
        @DisplayName("expands to two entries when only part of it goes")
        void partialExpands() {
            CompoundTag section = section(List.of(STONE), null);
            SectionNbt.Result result = SectionNbt.clear(section, bottomLayer());

            assertNull(result.refusal());
            assertEquals(256, result.cleared());
            assertEquals(List.of(STONE, SectionNbt.AIR), paletteNames(section));

            int[] cells = decode(section);
            assertEquals(1, cells[SectionNbt.index(0, 0, 0)], "bottom layer is now air");
            assertEquals(0, cells[SectionNbt.index(0, 1, 0)], "everything above is untouched stone");
        }

        @Test
        @DisplayName("an all-air section is left completely alone")
        void airIsNoOp() {
            CompoundTag section = section(List.of(SectionNbt.AIR), null);
            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertEquals(0, result.cleared());
            assertFalse(result.rewritten());
        }
    }

    @Nested
    @DisplayName("a section with a real palette")
    class Packed {

        @Test
        @DisplayName("reuses an air entry the palette already has")
        void reusesExistingAir() {
            int[] cells = new int[SectionNbt.CELLS];
            for (int i = 0; i < SectionNbt.CELLS; i++) {
                cells[i] = 1;
            }
            CompoundTag section = section(List.of(SectionNbt.AIR, STONE, DIRT), cells);
            SectionNbt.Result result = SectionNbt.clear(section, bottomLayer());

            assertNull(result.refusal());
            assertEquals(256, result.cleared());
            assertEquals(3, paletteNames(section).size(), "air was already there, so nothing is appended");

            int[] after = decode(section);
            assertEquals(0, after[SectionNbt.index(5, 0, 5)]);
            assertEquals(1, after[SectionNbt.index(5, 1, 5)]);
        }

        @Test
        @DisplayName("growing past sixteen entries repacks without disturbing any other cell")
        void repackWidensAndPreservesEverything() {
            // Sixteen distinct blocks, no air: appending air forces 4 bits to 5 and a full repack.
            List<String> palette = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                palette.add("minecraft:block_" + i);
            }
            int[] cells = new int[SectionNbt.CELLS];
            for (int i = 0; i < SectionNbt.CELLS; i++) {
                cells[i] = i % 16;
            }
            CompoundTag section = section(palette, cells);
            assertEquals(4, SectionNbt.bitsForPalette(16));

            SectionNbt.Result result = SectionNbt.clear(section, bottomLayer());

            assertNull(result.refusal());
            assertEquals(17, paletteNames(section).size());
            assertEquals(SectionNbt.AIR, paletteNames(section).get(16));
            assertEquals(5, SectionNbt.bitsForPalette(17), "the section is now five bits wide");

            int[] after = decode(section);
            for (int i = 0; i < SectionNbt.CELLS; i++) {
                int y = (i >> 8) & 15;
                if (y == 0) {
                    assertEquals(16, after[i], "carved cell " + i + " should point at the appended air");
                } else {
                    assertEquals(i % 16, after[i], "cell " + i + " survived the repack unchanged");
                }
            }
        }

        @Test
        @DisplayName("counts only cells that actually changed")
        void countsOnlyChanges() {
            int[] cells = new int[SectionNbt.CELLS];
            CompoundTag section = section(List.of(SectionNbt.AIR, STONE), cells);
            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertEquals(0, result.cleared(), "every cell was already air");
            assertFalse(result.rewritten());
        }
    }

    @Nested
    @DisplayName("stored data that does not match its palette")
    class Refusals {

        @Test
        @DisplayName("is refused rather than decoded at a guessed width")
        void wrongLengthRefused() {
            CompoundTag section = section(List.of(STONE, DIRT), new int[SectionNbt.CELLS]);
            CompoundTag states = section.getCompound(SectionNbt.BLOCK_STATES);
            long[] truncated = new long[7];
            states.put(SectionNbt.DATA, new LongArrayTag(truncated));

            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertNotNull(result.refusal(), "a mismatched width must be refused, never guessed");
            assertFalse(result.rewritten());
            assertEquals(7, states.getLongArray(SectionNbt.DATA).length, "the tag is left exactly as found");
        }

        @Test
        @DisplayName("a multi-entry palette with no data at all is refused")
        void missingDataRefused() {
            CompoundTag section = section(List.of(STONE, DIRT), null);
            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertNotNull(result.refusal());
            assertFalse(result.rewritten());
        }

        @Test
        @DisplayName("a section with no block data is already air")
        void noBlockStatesIsNoOp() {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) 3);
            SectionNbt.Result result = SectionNbt.clear(section, everything());

            assertNull(result.refusal());
            assertFalse(result.rewritten());
        }
    }

    @Test
    @DisplayName("a block named air but carrying properties is not air")
    void namedAirWithPropertiesIsNotAir() {
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag fake = new CompoundTag();
        fake.putString(SectionNbt.NAME, SectionNbt.AIR);
        fake.put(SectionNbt.PROPERTIES, new CompoundTag());
        palette.add(fake);
        states.put(SectionNbt.PALETTE, palette);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put(SectionNbt.BLOCK_STATES, states);

        SectionNbt.Result result = SectionNbt.clear(section, everything());

        assertTrue(result.rewritten(), "it must be replaced, not mistaken for air already there");
        assertEquals(SectionNbt.CELLS, result.cleared());
    }
}
