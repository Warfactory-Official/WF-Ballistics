package com.wf.wflib.recon;

/** Emission control: whether a target is choosing to be quiet, and by how much. */
public record EmconState(boolean radioSilent, float emitterScale) {

    /**
     * Emitting normally. The default for anything that has not been told otherwise.
     */
    public static final EmconState ACTIVE = new EmconState(false, 1.0f);
    /**
     * Fully dark: nothing active at all.
     */
    public static final EmconState SILENT = new EmconState(true, 0.0f);

    /**
     * Apply this state to a signature. Reflective bands are untouched by design.
     */
    public Signature apply(Signature in) {
        if (!radioSilent && emitterScale >= 1.0f) {
            return in;
        }
        float scale = radioSilent ? 0.0f : Math.max(0.0f, emitterScale);
        return in.scaled(Band.EM, scale);
    }
}
