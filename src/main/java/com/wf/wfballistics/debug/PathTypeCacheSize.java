package com.wf.wfballistics.debug;

/**
 * How big vanilla's shared path-type cache should be.
 *
 * <p>{@code PathTypeCache} is a direct-mapped table of exactly 4096 entries, shared by every mob on a level
 * and keyed by {@code HashCommon.mix(packedPos) & 4095}. There is no chaining: a colliding position evicts
 * the previous one outright. That is ample for the handful of mobs vanilla expects and nowhere near enough
 * for a swarm — three hundred glyphids issue tens of thousands of distinct position queries per tick, so the
 * table thrashes on capacity and conflicts together.
 *
 * <p>Measured symptom: 2.1 block reads per node expanded when the swarm is packed and its working set fits,
 * against 189.3 when it is spread out and does not. Same code, same mobs, ninety-fold difference — that is a
 * cache falling off a cliff, not terrain being harder.
 *
 * <p>Sized in megabytes because that is the unit the trade-off is actually in. An entry is a {@code long}
 * plus an object reference: eight bytes plus four under compressed oops, so twelve. Rounded down to a power
 * of two, since the index is a mask rather than a modulo.
 */
public final class PathTypeCacheSize {

    /**
     * Bytes per entry: one {@code long} position plus one {@code PathType} reference.
     */
    private static final int BYTES_PER_ENTRY = 12;

    /**
     * Vanilla's size, kept as the floor so a silly config cannot make things worse than not patching at all.
     */
    public static final int VANILLA_ENTRIES = 4096;

    private static volatile int entries = entriesForMegabytes(1);
    private static volatile int mask = entries - 1;

    private PathTypeCacheSize() {
    }

    public static int entries() {
        return entries;
    }

    /**
     * Read on the hot path by the mixin's replacement index function, so it is a plain field read rather than
     * anything cleverer.
     */
    public static int mask() {
        return mask;
    }

    public static int entriesForMegabytes(double megabytes) {
        long wanted = (long) (megabytes * 1024.0 * 1024.0 / BYTES_PER_ENTRY);
        int size = VANILLA_ENTRIES;
        while ((long) size * 2L <= wanted) {
            size *= 2;
        }
        return size;
    }

    public static double megabytesFor(int entries) {
        return (double) entries * BYTES_PER_ENTRY / (1024.0 * 1024.0);
    }

    /**
     * @return the entry count actually adopted, which is the requested size rounded down to a power of two
     */
    public static int setMegabytes(double megabytes) {
        int size = entriesForMegabytes(megabytes);
        entries = size;
        mask = size - 1;
        return size;
    }
}
