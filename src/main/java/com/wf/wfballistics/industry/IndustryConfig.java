package com.wf.wfballistics.industry;

/**
 * Live industry-tracking tunables, copied out of {@code WFConfig} on load in the same way {@code MissileSimConfig}
 * is, so the hot paths read plain statics rather than config objects.
 */
public final class IndustryConfig {

    private static int cellChunks = 32;
    private static int clusterGapCells = 1;
    private static int clusterMinValue = 50;
    private static int recomputeDelayTicks = 100;
    private static boolean backfillEnabled = true;

    private IndustryConfig() {
    }

    /** Region cell size in chunks. */
    public static int cellChunks() {
        return cellChunks;
    }

    /**
     * How many empty cells may sit between two occupied ones before they count as separate bases.
     */
    public static int clusterGapCells() {
        return clusterGapCells;
    }

    /** Combined value a group of cells needs before it is a base rather than noise. */
    public static int clusterMinValue() {
        return clusterMinValue;
    }

    /** Ticks of quiet after the last change before clusters are recomputed. */
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
