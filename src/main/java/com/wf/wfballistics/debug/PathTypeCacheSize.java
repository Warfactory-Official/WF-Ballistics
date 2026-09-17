package com.wf.wfballistics.debug;

/** How big vanilla's shared path-type cache should be. */
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
