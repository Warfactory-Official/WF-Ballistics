package com.wf.wflib.round.terrain;

import com.wf.wflib.round.pen.PenTable;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * One 16^3 section as a round sees it. Index {@code y << 8 | z << 4 | x}. Solid = full-cube collision; partial =
 * other non-empty collision, clipped by its state's shape; wet = non-empty fluid. Solid voxels carry their
 * {@link PenTable} resistance as resolved at decode: one for the section, else a palette of those present + rank bit
 * planes (1 plane for 2 values).
 */
public final class SectionSolidity {

    private static final long[] ALL = filled();
    private static final float[] NONE = new float[0];

    public static final SectionSolidity AIR = new SectionSolidity(null, null, null, NONE, null);
    public static final SectionSolidity WATER = new SectionSolidity(null, ALL, null, NONE, null);

    @Nullable
    private final long[] solid;
    @Nullable
    private final long[] wet;
    @Nullable
    private final Partials partial;
    /** Solid voxels' resistances, ascending; one => every solid voxel. */
    private final float[] palette;
    /** Rank bit k of a solid voxel in {@code planes[k]} (null = clear); null when {@code palette.length <= 1}. */
    @Nullable
    private final long[][] planes;

    private SectionSolidity(@Nullable long[] solid, @Nullable long[] wet, @Nullable Partials partial, float[] palette,
                            @Nullable long[][] planes) {
        this.solid = solid;
        this.wet = wet;
        this.partial = partial;
        this.palette = palette;
        this.planes = planes;
    }

    /**
     * Masks of 64 longs; all-zero => null. {@code palette}: distinct resistances of the solid voxels, ascending;
     * {@code ranks}: index into it per voxel, read iff two or more.
     */
    static SectionSolidity of(long[] solid, long[] wet, @Nullable Partials partial, @Nullable short[] ranks,
                              float[] palette) {
        long[] s = uniform(solid);
        long[] w = uniform(wet);
        if (s == null) {
            palette = NONE;
        }
        if (partial == null && s == null) {
            if (w == null) {
                return AIR;
            }
            if (w == ALL) {
                return WATER;
            }
        }
        if (palette.length <= 1) {
            return new SectionSolidity(s, w, partial, palette, null);
        }
        long[][] planes = new long[32 - Integer.numberOfLeadingZeros(palette.length - 1)][64];
        for (int i = 0; i < 4096; i++) {
            if ((s[i >> 6] & 1L << i) != 0L) {
                int r = ranks[i];
                for (int k = 0; k < planes.length; k++) {
                    if ((r & 1 << k) != 0) {
                        planes[k][i >> 6] |= 1L << i;
                    }
                }
            }
        }
        for (int k = 0; k < planes.length; k++) {
            planes[k] = uniform(planes[k]);
        }
        return new SectionSolidity(s, w, partial, palette, planes);
    }

    @Nullable
    private static long[] uniform(long[] bits) {
        boolean none = true;
        boolean all = true;
        for (long word : bits) {
            none &= word == 0L;
            all &= word == -1L;
        }
        return none ? null : all ? ALL : bits;
    }

    private static long[] filled() {
        long[] bits = new long[64];
        Arrays.fill(bits, -1L);
        return bits;
    }

    public static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    public boolean solid(int index) {
        return solid != null && (solid[index >> 6] & 1L << index) != 0L;
    }

    public boolean wet(int index) {
        return wet != null && (wet[index >> 6] & 1L << index) != 0L;
    }

    /** Solid voxel's resistance, mm/m. */
    public float resistance(int index) {
        if (planes == null) {
            return palette[0];
        }
        int r = 0;
        for (int k = 0; k < planes.length; k++) {
            long[] plane = planes[k];
            if (plane != null && (plane[index >> 6] & 1L << index) != 0L) {
                r |= 1 << k;
            }
        }
        return palette[r];
    }

    @Nullable
    public BlockState partial(int index) {
        return partial == null ? null : partial.at(index);
    }

    /** Heap owned by this section alone; shared masks cost 0. */
    public int bytes() {
        int n = 0;
        if (solid != null && solid != ALL) {
            n += 8 * 64;
        }
        if (wet != null && wet != ALL) {
            n += 8 * 64;
        }
        if (partial != null) {
            n += partial.bytes();
        }
        if (planes != null) {
            n += 16 + 4 * palette.length;
            for (long[] plane : planes) {
                n += plane != null && plane != ALL ? 8 * 64 : 0;
            }
        }
        return n;
    }

    /**
     * Partial voxels' states by rank ({@code states[rank - 1]}): {@code at == null} => rank per voxel in
     * {@code narrow} (<= 255 states) or {@code wide}, neither => every voxel {@code states[0]}; {@code at != null} =>
     * sorted voxel indices, ranks parallel (sparse).
     */
    record Partials(BlockState[] states, @Nullable short[] at, @Nullable byte[] narrow, @Nullable short[] wide) {

        @Nullable
        BlockState at(int index) {
            int k = index;
            if (at != null) {
                k = Arrays.binarySearch(at, (short) index);
                if (k < 0) {
                    return null;
                }
            }
            int r = narrow != null ? narrow[k] & 0xFF : wide != null ? wide[k] : 1;
            return r == 0 ? null : states[r - 1];
        }

        int bytes() {
            return 16 + 4 * states.length + (at != null ? 2 * at.length : 0)
                    + (narrow != null ? narrow.length : wide != null ? 2 * wide.length : 0);
        }
    }
}
