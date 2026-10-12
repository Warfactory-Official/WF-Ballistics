package com.wf.wflib.aef.nuke;

/** Tunables for the multi-tick nuclear explosion, ported from HBM's {@code ConfigBomb}. */
public final class NukeConfig {

    /**
     * {@link com.wf.wflib.entity.EntityNukeExplosionMK5} ray algorithm: {@code 0} legacy batched, {@code 1}
     * threaded DDA, {@code 2} threaded DDA + damage accumulation.
     */
    public static int explosionAlgorithm = 2;

    /** Parallelized algorithm: snapshot + generate not-yet-loaded chunks. */
    public static boolean chunkloading = false;

    /** Parallelized algorithm: blocks cleared per destruction-tick budget. */
    public static int blastSpeed = 1024;

    /** Parallelized algorithm: ms/tick budgeted to caching + destruction. */
    public static int mk5BlastTime = 50;

    private NukeConfig() {
    }
}
