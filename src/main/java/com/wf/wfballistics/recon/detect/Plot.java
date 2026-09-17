package com.wf.wfballistics.recon.detect;

import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.ContactClass;

/**
 * One return: something is roughly there, with this much error, and it is probably this sort of thing.
 *
 * @param bandMask every band that contributed, as a bit per {@link Band} ordinal. One bit before fusion.
 *      Two bands agreeing on a contact is the strongest statement this system can make, and
 *      without a mask a fused picture could not represent it.
 * @param bearingX horizontal unit vector from the sensor toward the target, X component. A direction, not
 *      the observer's position: the error ellipse is long across this and short along it, and
 *      that asymmetry is the entire reason two probes at a wide angle beat two side by side.
 * @param crossError blocks across the bearing (beam width times range, so it grows with distance
 * @param rangeError blocks along the bearing) constant, set by pulse width
 * @param strength signal-to-noise, normalised so {@code 1.0} is exactly at the sensor's detection range for
 *      this target. Above that it rises as the fourth power of closing.
 * @param hint what the sensor is prepared to say this is. {@link ContactClass#UNKNOWN} until the return
 *      is strong enough to classify, however certain the source that produced it was.
 * @param merged how many returns fused into this one. Two targets inside a resolution cell are one plot
 *      with a count estimate, which is why a packed swarm is systematically undercounted.
 * @param friendly whether a transponder answered this sensor's interrogation with its own network's code.
 *      Exactly one bit, correlated to a primary return by position, which is what secondary
 *      radar actually is, and it survives a merge only if <em>everything</em> in the cell
 *      replied, so tucking in behind something friendly is a real thing to do.
 * @param hops relay links this measurement crossed to get here: the worst of the contributors after a
 *      fusion, because a fix is only as trustworthy as its longest tail.
 */
public record Plot(Band band, int bandMask, double x, double y, double z,
                   double bearingX, double bearingZ,
                   double crossError, double rangeError, double strength,
                   ContactClass hint, int merged, boolean friendly, int hops, long gameTime) {

    /**
     * One sensor's single return, with the band mask derived rather than passed so the two can never disagree.
     */
    public static Plot single(Band band, double x, double y, double z,
                              double bearingX, double bearingZ,
                              double crossError, double rangeError, double strength,
                              ContactClass hint, boolean friendly, int hops, long gameTime) {
        return new Plot(band, 1 << band.ordinal(), x, y, z, bearingX, bearingZ,
                crossError, rangeError, strength, hint, 1, friendly, hops, gameTime);
    }

    /**
     * @return the radius inside which this plot cannot tell one position from another.
     */
    public double errorRadius() {
        return Math.max(crossError, rangeError);
    }

    /**
     * @return true if more than one band contributed. A radar return and a seismic event that agree are a very
     *      different claim from either alone, and this is the cheapest way for a consumer to notice.
     */
    public boolean corroborated() {
        return Integer.bitCount(bandMask) > 1;
    }

    /**
     * @return this plot with its error inflated by whatever the link home cost it.
     */
    public Plot degraded(double scale) {
        return scale <= 1.0 ? this
                : new Plot(band, bandMask, x, y, z, bearingX, bearingZ,
                crossError * scale, rangeError * scale, strength, hint, merged, friendly, hops, gameTime);
    }
}
