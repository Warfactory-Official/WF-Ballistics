package com.wf.wflib.build;

/** Litematica's packed palette-index array. */
public final class PackedIndices {

    private PackedIndices() {
    }

    public static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** Straddling: entries span longs with no padding, so this is just the total bits rounded up. */
    public static int longsFor(int count, int bits) {
        return (int) (((long) count * bits + 63L) / 64L);
    }

    /** Short array => 0: a truncated file degrades to empty cells, not a mid-load exception. */
    public static int get(long[] data, int index, int bits) {
        long start = (long) index * bits;
        int first = (int) (start >> 6);
        int last = (int) ((start + bits - 1) >> 6);
        if (index < 0 || first < 0 || last >= data.length) {
            return 0;
        }
        int offset = (int) (start & 63L);
        long mask = (1L << bits) - 1L;
        if (first == last) {
            return (int) ((data[first] >>> offset) & mask);
        }
        return (int) (((data[first] >>> offset) | (data[last] << (64 - offset))) & mask);
    }
}
