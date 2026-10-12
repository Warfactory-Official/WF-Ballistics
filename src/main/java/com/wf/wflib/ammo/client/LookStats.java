package com.wf.wflib.ammo.client;

/**
 * Look draw cost (harness reads): {@code RoundsVisual} frame ns + look instances placed; LOD sprites drawn; tracer
 * pass ns (sprites + tracer streaks, CPU incl. {@code endBatch}). Render thread only.
 */
public final class LookStats {
    public static long frames;
    public static long nanos;
    public static long placed;
    public static long sprites;
    public static long tracerNanos;

    private LookStats() {
    }

    public static void reset() {
        frames = nanos = placed = sprites = tracerNanos = 0;
    }
}
