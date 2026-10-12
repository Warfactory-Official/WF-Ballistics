package com.wf.wflib.debug;

public final class PathTypeCacheSize {

    /** Bytes/entry: one {@code long} position + one {@code PathType} ref. */
    private static final int BYTES_PER_ENTRY = 12;

    /** Floor: config can't regress below unpatched vanilla. */
    public static final int VANILLA_ENTRIES = 4096;

    private static volatile int entries = entriesForMegabytes(1);
    private static volatile int mask = entries - 1;

    private PathTypeCacheSize() {
    }

    public static int entries() {
        return entries;
    }

    /** Hot-path read (mixin's index fn): keep plain field read, nothing cleverer. */
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

    /** @return entries actually adopted (requested size rounded down to pow2) */
    public static int setMegabytes(double megabytes) {
        int size = entriesForMegabytes(megabytes);
        entries = size;
        mask = size - 1;
        return size;
    }
}
