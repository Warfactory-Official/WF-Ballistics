package com.wf.wflib.missile;

import com.wf.wflib.recon.Band;
import org.jetbrains.annotations.Nullable;

/** How a missile keeps its lock after launch. */
public enum SeekerMode {
    /** Designated entity / aim point, no seeker physics (legacy + TV let-go). */
    DESIGNATED(null, false),
    /** Heat seeker: line of sight; smoke blocks; flares seduce. */
    INFRARED(Band.THERMAL, false),
    /** Contrast tracker: line of sight; smoke blocks; flares ignored. */
    OPTICAL(null, false),
    /** Rides the launcher's radar lock; chaff seduces. */
    SEMI_ACTIVE_RADAR(Band.RADAR, true),
    /** Launcher lock until {@code radarRange}, then own radar in a cone; chaff seduces. */
    ACTIVE_RADAR(Band.RADAR, true),
    /** Flies onto the launcher's guidance beam. */
    BEAM_RIDING(null, false);

    @Nullable
    private final Band band;
    private final boolean emits;

    SeekerMode(@Nullable Band band, boolean emits) {
        this.band = band;
        this.emits = emits;
    }

    /** Band decoys compete in; null = not decoyable. */
    @Nullable
    public Band band() {
        return this.band;
    }

    /** Detectable by the target (radar warning). */
    public boolean emits() {
        return this.emits;
    }
}
