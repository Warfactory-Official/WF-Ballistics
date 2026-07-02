package com.wf.wfballistics.aef.nuke;

/**
 * Tunables for the multi-tick nuclear explosion (ported from HBM's {@code ConfigBomb}). Plain static fields
 * for now — wire them to a real Forge config if you want them user-editable.
 */
public final class NukeConfig {

    /**
     * Which ray algorithm {@link com.wf.wfballistics.entity.EntityNukeExplosionMK5} uses:
     * {@code 0} = legacy batched, {@code 1} = threaded DDA, {@code 2} = threaded DDA + damage accumulation.
     */
    public static int explosionAlgorithm = 2;

    /**
     * Allow the parallelized algorithm to snapshot (and generate) not-yet-loaded chunks.
     */
    public static boolean chunkloading = false;

    /**
     * Blocks cleared per destruction-tick budget (parallelized algorithm).
     */
    public static int blastSpeed = 1024;

    /**
     * Milliseconds per tick budgeted to the parallelized algorithm's caching + destruction work.
     */
    public static int mk5BlastTime = 50;

    private NukeConfig() {
    }
}
