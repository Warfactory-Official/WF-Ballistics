package com.wf.wfballistics.industry;

/**
 * Live industry-tracking tunables, copied out of {@code WFConfig} on load in the same way
 * {@code MissileSimConfig} is, so the hot paths read plain statics rather than config objects.
 */
public final class IndustryConfig {

    private static int cellChunks = 32;
    private static int clusterGapCells = 1;
    private static int clusterMinValue = 50;
    private static int recomputeDelayTicks = 100;
    private static boolean backfillEnabled = true;

    private IndustryConfig() {
    }

    /**
     * Region cell size in chunks. 32 chunks (512 blocks) by default: large enough that a single base
     * lands in one or two cells rather than being smeared across dozens.
     */
    public static int cellChunks() {
        return cellChunks;
    }

    /**
     * How many empty cells may sit between two occupied ones before they count as separate bases.
     */
    public static int clusterGapCells() {
        return clusterGapCells;
    }

    /**
     * Combined value a group of cells needs before it is a base rather than noise. The equivalent of
     * DBSCAN's {@code minPts}, in value rather than point count, so one dirty machine cluster counts for
     * more than a scattering of furnaces.
     */
    public static int clusterMinValue() {
        return clusterMinValue;
    }

    /**
     * Ticks of quiet after the last change before clusters are recomputed. Debouncing is the whole reason
     * this can be event-driven: laying down a base is hundreds of placements in a few seconds, and each
     * one must not queue its own scan.
     */
    public static int recomputeDelayTicks() {
        return recomputeDelayTicks;
    }

    /**
     * Whether chunks are swept once on load for machines that no event ever reported.
     */
    public static boolean backfillEnabled() {
        return backfillEnabled;
    }

    public static void apply(int cellChunksValue, int gapCells, int minValue, int delayTicks, boolean backfill) {
        cellChunks = Math.max(1, cellChunksValue);
        clusterGapCells = Math.max(0, gapCells);
        clusterMinValue = Math.max(1, minValue);
        recomputeDelayTicks = Math.max(1, delayTicks);
        backfillEnabled = backfill;
    }
}
