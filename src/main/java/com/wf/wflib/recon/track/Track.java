package com.wf.wflib.recon.track;

import com.wf.wflib.recon.ContactClass;

/**
 * A thing the network believes is out there: where it is, where it is going, how sure it is, and how wrong it might
 * be.
 *
 * @param errorRadius blocks. Shrinks as the target closes and grows while it coasts unseen.
 * @param countEstimate how many things the network thinks are in here. Greater than one when returns merged
 *      inside a resolution cell, and reliably an undercount for anything packed tightly.
 * @param bandMask which bands have contributed, a bit per {@code Band} ordinal. A contact held on radar
 *      <em>and</em> seismic is a far stronger statement than one held on either, and since the
 *      picture is fused this is the only place that distinction survives.
 * @param hops relay links the freshest measurement crossed. Not a cost the consumer has to apply
 *      (it is already paid, in this track's error and its quality ceiling), but a display that
 *      shows it is showing the player which limb of the grid to defend.
 * @param lastSeenTick the game tick of the last plot that updated this. Everything after that is dead
 *      reckoning.
 */
public record Track(TrackId id, double x, double y, double z,
                    double vx, double vy, double vz,
                    double errorRadius, TrackQuality quality,
                    ContactClass guess, float confidence,
                    int countEstimate, IffState iff, int bandMask, int hops, long lastSeenTick) {

    /**
     * @return true if more than one band holds this. Nothing in the game can defeat two bands at once (radar
     *      absorbency does nothing about the noise of digging), so corroboration is the one signal a defender can
     *      trust against a target that is actively trying to be something else.
     */
    public boolean corroborated() {
        return Integer.bitCount(bandMask) > 1;
    }

    /** Where this would be now if it kept doing what it was doing. */
    public double predictedX(long now) {
        return x + vx * (now - lastSeenTick);
    }

    public double predictedY(long now) {
        return y + vy * (now - lastSeenTick);
    }

    public double predictedZ(long now) {
        return z + vz * (now - lastSeenTick);
    }

    /**
     * @return the error radius grown by however long this has been coasting. A track nobody has looked at for
     *      a while is not wrong, it is vague, and the difference matters to anything deciding whether to shoot.
     */
    public double predictedError(long now) {
        return errorRadius + speed() * Math.max(0, now - lastSeenTick) * 0.5;
    }

    /**
     * @return speed in blocks per tick.
     */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /**
     * @return closing speed toward a point, in blocks per tick. Positive means inbound.
     */
    public double closingOn(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1.0E-6) {
            return 0.0;
        }
        return (vx * dx + vy * dy + vz * dz) / d;
    }

    public double distanceSqTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
