package com.wf.wflib.rail.excavate;

/** What a stored-chunk carve does about light. */
public enum LightingPolicy {

    /** Clear the chunk's {@code isLightOn} flag and let the engine relight it when it next loads. */
    DEFERRED,

    /** Leave the stored light arrays untouched and the chunk marked lit. */
    KEEP;

    /** @return whether the chunk should be marked for relight on its next load. */
    public boolean invalidates() {
        return this == DEFERRED;
    }
}
