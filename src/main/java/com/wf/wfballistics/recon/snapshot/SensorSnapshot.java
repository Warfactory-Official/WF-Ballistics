package com.wf.wfballistics.recon.snapshot;

import com.wf.wfballistics.recon.SensorSpec;
import com.wf.wfballistics.recon.env.Atmosphere;

/**
 * One sensor as a worker sees it: where it is, what it can do, and when it was looking.
 *
 * @param eyeY the height the sensor actually looks from: block centre plus its mast. Mast height is the one
 *      build decision that changes what a radar can see over, so it is a first-class field rather than
 *      something folded into the position.
 * @param hops relay links between this sensor and its hub. Zero for a hub-adjacent probe and for any sensor
 *      that is not on a grid at all: a turret's radar is bolted to the turret that reads it and has
 *      nothing to phone home about. A sensor with no route never reaches a pass, so this is never
 *      negative here.
 * @param atmos what the air is doing here, and how much of it this network measured. Carried on the snapshot
 *      rather than passed beside it for the same reason as {@code eyeY}: it is a property of where the
 *      sensor is standing at the moment it looked, and folding it in means a band lit up later
 *      inherits weather without {@code Propagator} ever growing a parameter for it.
 */
public record SensorSnapshot(long sensorId, double x, double eyeY, double z, SensorSpec spec, int hops,
                             Atmosphere atmos, long gameTime) {

    /** Blocks of extra error per relay hop, compounding. */
    public static final double HOP_ERROR = 1.15;

    /**
     * @return what this sensor's measurements are multiplied by before anything downstream sees them.
     */
    public double linkErrorScale() {
        return Math.pow(HOP_ERROR, Math.max(0, hops));
    }

    public double distanceSqTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = eyeY - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
