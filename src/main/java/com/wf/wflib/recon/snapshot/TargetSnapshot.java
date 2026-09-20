package com.wf.wflib.recon.snapshot;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.Countermeasure;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.Signature;

/**
 * One thing a sensor might detect, condensed to primitives so a worker can look at it.
 *
 * @param sourceId stable identity <em>for deduplication inside one pass only</em>. It is never handed
 *      out past the detection layer, and tracks are associated by position, not by this.
 * @param iffCode this target's transponder reply, or {@code 0} for no transponder. A network reads it
 *      as friendly only on an exact match with its own id; someone else's code is as opaque
 *      as no code at all.
 * @param buried true if the target is inside something radar cannot see through at any range: under rock,
 *      or under water. A running torpedo is as opaque as a digging glyphid, and both the radar
 *      and the thermal model already gate on this.
 * @param inWater true if the target is in contact with water. A surface ship is, a submerged torpedo is,
 *      a helicopter hovering a block above the waves is not: this, and not {@link #buried}, is
 *      what makes something a sonar target.
 * @param countermeasure everything this target carries. Array rather than a list because it is read once per
 *      sensor per sweep and is almost always empty; note this gives the record identity
 *      equality, which is correct here: two snapshots are never compared.
 */
public record TargetSnapshot(long sourceId, ContactClass kind,
                             double x, double y, double z,
                             double vx, double vy, double vz,
                             Signature signature,
                             Countermeasure[] countermeasure,
                             EmconState emcon,
                             long iffCode,
                             boolean buried,
                             boolean inWater) {

    public static final Countermeasure[] NO_COUNTERMEASURES = new Countermeasure[0];

    /** Anything on dry land, which is everything that was written before sonar existed. */
    public TargetSnapshot(long sourceId, ContactClass kind,
                          double x, double y, double z,
                          double vx, double vy, double vz,
                          Signature signature, Countermeasure[] countermeasure, EmconState emcon,
                          long iffCode, boolean buried) {
        this(sourceId, kind, x, y, z, vx, vy, vz, signature, countermeasure, emcon, iffCode, buried, false);
    }

    /**
     * The common case: something that carries nothing and hides nothing.
     */
    public static TargetSnapshot of(long sourceId, ContactClass kind,
                                    double x, double y, double z,
                                    double vx, double vy, double vz,
                                    Signature signature, long iffCode, boolean buried) {
        return new TargetSnapshot(sourceId, kind, x, y, z, vx, vy, vz,
                signature, NO_COUNTERMEASURES, EmconState.ACTIVE, iffCode, buried, false);
    }

    /** What this target actually looks like to one sensor, after everything it is carrying has had its say. */
    public Signature presentedTo(SensorSnapshot sensor) {
        Signature out = signature;
        Band band = sensor.spec().band();
        for (Countermeasure cm : countermeasure) {
            if (cm.band() == band) {
                out = cm.modify(out, this, sensor);
            }
        }
        return emcon.apply(out);
    }

    public double distanceSqTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * @return speed in blocks per tick.
     */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }
}
