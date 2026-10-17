package com.wf.wflib.radio;

/**
 * How a carrier's set sounds. {@code outside}: gain off board (cabin radios leak through the hull, low-passed);
 * aboard always 1, stereo.
 */
public enum RadioVoice {
    CABIN(24, 0.35F, true),
    SPEAKER(96, 1, false);

    private final float range;
    private final float outside;
    private final boolean hull;

    RadioVoice(float range, float outside, boolean hull) {
        this.range = range;
        this.outside = outside;
        this.hull = hull;
    }

    /** Blocks; no source beyond. */
    public float range() {
        return range;
    }

    public float outside() {
        return outside;
    }

    /** Heard through the hull off board. */
    public boolean hull() {
        return hull;
    }
}
