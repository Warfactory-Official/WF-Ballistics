package com.wf.wflib.recon.example;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.Countermeasure;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;

/**
 * A corner reflector: makes something small look enormous.
 *
 * @param factor multiplier on radar cross-section, expected to be greater than 1
 */
public record CornerReflector(float factor) implements Countermeasure {

    /**
     * Enough to make a decoy read like a full-size target: detection range goes as the fourth root, so this buys
     * the carrier a bit over 3x the range it is seen at.
     */
    public static final CornerReflector STANDARD = new CornerReflector(100.0f);

    @Override
    public Band band() {
        return Band.RADAR;
    }

    @Override
    public Signature modify(Signature in, TargetSnapshot self, SensorSnapshot sensor) {
        return in.scaled(Band.RADAR, factor);
    }
}
