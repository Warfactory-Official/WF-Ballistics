package com.wf.wfballistics.recon.example;

import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.Countermeasure;
import com.wf.wfballistics.recon.Signature;
import com.wf.wfballistics.recon.snapshot.SensorSnapshot;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;

/**
 * Rubber tiles on a hull: the sonar half of {@link RadarAbsorbentCoating}, and the third leg of this band's
 * triangle.
 *
 * <p>It damps what the hull radiates, so it works against a set that is listening — and it does nothing at all
 * against one that is pinging, because {@code SonarPropagator} reads a hull's echo off the <em>raw</em>
 * signature. Hull size is physical; a surface treatment quietens a hull and does not shrink it. That is the
 * whole reason to accept a ping's cost and its counter-detection.
 *
 * @param factor multiplier on radiated noise. 1.0 is inert, 0.0 is silent.
 */
public record AnechoicCoating(float factor) implements Countermeasure {

    /**
     * A believable treatment: a fourfold cut. The band's law is one-way, so it halves the range the hull is
     * heard at — the same unit the whole stealth economy is quoted in, bought here for a square rather than
     * radar's fourth power.
     */
    public static final AnechoicCoating STANDARD = new AnechoicCoating(0.25f);

    @Override
    public Band band() {
        return Band.SONAR;
    }

    @Override
    public Signature modify(Signature in, TargetSnapshot self, SensorSnapshot sensor) {
        return in.scaled(Band.SONAR, factor);
    }
}
