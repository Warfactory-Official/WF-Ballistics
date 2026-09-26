package com.wf.wflib.api;

import com.wf.wflib.recon.Band;

/** Entity that seduces seekers in a band (flare = THERMAL, chaff = RADAR). */
public interface SeekerDecoy {

    /** Competes with a target's {@link com.wf.wflib.recon.Signature#on} in the same band; 0 = inert. */
    float decoyStrength(Band band);
}
