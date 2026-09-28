package com.wf.wflib.round.terrain;

import com.wf.wflib.round.pen.PenTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Saved chunk NBT (1.18+ {@code sections} layout, no DataFixer) -> sections. Missing section or
 * {@code block_states} = air, as vanilla's empty container. Shapes via {@code EmptyBlockGetter}: a block whose
 * collision reads neighbours decodes as if alone.
 */
public final class SolidityDecoder {

    private static final int VOLUME = 4096;
    private static final byte AIR = 0;
    private static final byte SOLID = 1;
    private static final byte PARTIAL = 2;

    private SolidityDecoder() {
    }

    /** @return {@code sections} entries, index {@code sectionY - minSection} */
    public static SectionSolidity[] decode(CompoundTag chunk, HolderGetter<Block> blocks, int minSection,
                                           int sections) throws IOException {
        if (chunk.contains("Level", Tag.TAG_COMPOUND)) {
            throw new IOException("pre-1.18 chunk layout");
        }
        SectionSolidity[] out = new SectionSolidity[sections];
        Arrays.fill(out, SectionSolidity.AIR);
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int s = 0; s < list.size(); s++) {
            CompoundTag section = list.getCompound(s);
            int slot = section.getByte("Y") - minSection;
            if (slot < 0 || slot >= sections || !section.contains("block_states", Tag.TAG_COMPOUND)) {
                continue;
            }
            out[slot] = section(section.getCompound("block_states"), blocks);
        }
        return out;
    }

    private static SectionSolidity section(CompoundTag states, HolderGetter<Block> blocks) throws IOException {
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        int n = palette.size();
        if (n == 0) {
            return SectionSolidity.AIR;
        }
        BlockState[] state = new BlockState[n];
        byte[] kind = new byte[n];
        boolean[] wet = new boolean[n];
        float[] resistance = new float[n];
        for (int p = 0; p < n; p++) {
            state[p] = NbtUtils.readBlockState(blocks, palette.getCompound(p));
            VoxelShape shape = state[p].getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            kind[p] = shape.isEmpty() ? AIR : Block.isShapeFullBlock(shape) ? SOLID : PARTIAL;
            wet[p] = !state[p].getFluidState().isEmpty();
            resistance[p] = kind[p] == SOLID ? PenTable.resistance(state[p]) : 0.0f;
        }
        long[] solidBits = new long[64];
        long[] wetBits = new long[64];
        if (n == 1) {
            SectionSolidity.Partials all = kind[0] == PARTIAL
                    ? new SectionSolidity.Partials(new BlockState[]{state[0]}, null, null, null) : null;
            Arrays.fill(solidBits, kind[0] == SOLID ? -1L : 0L);
            Arrays.fill(wetBits, wet[0] ? -1L : 0L);
            return SectionSolidity.of(solidBits, wetBits, all, null,
                    kind[0] == SOLID ? new float[]{resistance[0]} : new float[0]);
        }
        long[] data = states.getLongArray("data");
        int bits = bitsFor(n);
        if (data.length != longsFor(bits)) {
            throw new IOException("block_states: " + data.length + " longs for " + bits + " bits");
        }
        float[] values = distinctSorted(resistance, kind);
        short[] valueRank = new short[n];
        for (int p = 0; p < n; p++) {
            valueRank[p] = kind[p] == SOLID ? (short) Arrays.binarySearch(values, resistance[p]) : 0;
        }
        boolean[] present = new boolean[values.length];
        short[] ranks = values.length > 1 ? new short[VOLUME] : null;
        int[] rank = new int[n];
        int partials = 0;
        for (int p = 0; p < n; p++) {
            rank[p] = kind[p] == PARTIAL ? ++partials : 0;
        }
        byte[] narrow = null;
        short[] wide = null;
        boolean anyPartial = false;
        for (int i = 0; i < VOLUME; i++) {
            int p = paletteIndex(data, bits, i);
            if (p >= n) {
                throw new IOException("block_states: palette index " + p + " of " + n);
            }
            if (kind[p] == SOLID) {
                solidBits[i >> 6] |= 1L << i;
                present[valueRank[p]] = true;
                if (ranks != null) {
                    ranks[i] = valueRank[p];
                }
            } else if (kind[p] == PARTIAL) {
                if (!anyPartial) {
                    anyPartial = true;
                    if (partials <= 255) {
                        narrow = new byte[VOLUME];
                    } else {
                        wide = new short[VOLUME];
                    }
                }
                if (narrow != null) {
                    narrow[i] = (byte) rank[p];
                } else {
                    wide[i] = (short) rank[p];
                }
            }
            if (wet[p]) {
                wetBits[i >> 6] |= 1L << i;
            }
        }
        SectionSolidity.Partials partial = null;
        if (anyPartial) {
            BlockState[] partialStates = new BlockState[partials];
            for (int p = 0; p < n; p++) {
                if (rank[p] != 0) {
                    partialStates[rank[p] - 1] = state[p];
                }
            }
            partial = sparse(partialStates, narrow, wide);
        }
        return SectionSolidity.of(solidBits, wetBits, partial, ranks, present(values, present, ranks));
    }

    /** Resistances of the {@code SOLID} palette entries, ascending, unique. */
    private static float[] distinctSorted(float[] resistance, byte[] kind) {
        float[] v = new float[resistance.length];
        int k = 0;
        for (int p = 0; p < v.length; p++) {
            if (kind[p] == SOLID) {
                v[k++] = resistance[p];
            }
        }
        Arrays.sort(v, 0, k);
        int m = 0;
        for (int i = 0; i < k; i++) {
            if (m == 0 || v[i] != v[m - 1]) {
                v[m++] = v[i];
            }
        }
        return Arrays.copyOf(v, m);
    }

    /** {@code values} narrowed to those present; {@code ranks} remapped in place. */
    private static float[] present(float[] values, boolean[] present, @Nullable short[] ranks) {
        short[] remap = new short[values.length];
        int m = 0;
        for (int r = 0; r < values.length; r++) {
            remap[r] = (short) m;
            if (present[r]) {
                values[m++] = values[r];
            }
        }
        if (m == values.length) {
            return values;
        }
        if (ranks != null) {
            for (int i = 0; i < VOLUME; i++) {
                ranks[i] = remap[ranks[i]];
            }
        }
        return Arrays.copyOf(values, m);
    }

    /** Dense ranks -> sorted indices + ranks when that is smaller. */
    private static SectionSolidity.Partials sparse(BlockState[] states, @Nullable byte[] narrow,
                                                   @Nullable short[] wide) {
        int count = 0;
        for (int i = 0; i < VOLUME; i++) {
            count += (narrow != null ? narrow[i] : wide[i]) != 0 ? 1 : 0;
        }
        if (count * (narrow != null ? 3 : 4) >= VOLUME * (narrow != null ? 1 : 2)) {
            return new SectionSolidity.Partials(states, null, narrow, wide);
        }
        short[] at = new short[count];
        byte[] narrowAt = narrow != null ? new byte[count] : null;
        short[] wideAt = narrow != null ? null : new short[count];
        for (int i = 0, k = 0; i < VOLUME; i++) {
            int r = narrow != null ? narrow[i] : wide[i];
            if (r != 0) {
                at[k] = (short) i;
                if (narrowAt != null) {
                    narrowAt[k] = (byte) r;
                } else {
                    wideAt[k] = (short) r;
                }
                k++;
            }
        }
        return new SectionSolidity.Partials(states, at, narrowAt, wideAt);
    }

    /** Block-state palette width: at least 4 bits. */
    static int bitsFor(int paletteSize) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    static int longsFor(int bits) {
        int perLong = 64 / bits;
        return (VOLUME + perLong - 1) / perLong;
    }

    /** Entries never straddle longs (1.16+ packing). */
    static int paletteIndex(long[] data, int bits, int i) {
        int perLong = 64 / bits;
        return (int) (data[i / perLong] >>> (i % perLong) * bits & (1L << bits) - 1L);
    }
}
