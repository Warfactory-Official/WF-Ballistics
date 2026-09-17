package com.wf.wfballistics.recon.example;

import com.wf.wfballistics.recon.Band;
import com.wf.wfballistics.recon.Countermeasure;
import com.wf.wfballistics.recon.Signature;
import com.wf.wfballistics.recon.snapshot.SensorSnapshot;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;

/**
 * Faceted shaping: nearly invisible head-on, ordinary from the side.
 *
 * @param frontal multiplier when flying straight at the sensor
 * @param broadside multiplier when crossing it, and the value used when the target is not moving
 * @param coneDeg half-angle of the frontal sector, in degrees; outside it the value eases to broadside
 */
public record AspectShaping(float frontal, float broadside, double coneDeg) implements Countermeasure {

    /** A faceted nose: two orders of magnitude head-on, barely anything from the beam. */
    public static final AspectShaping FACETED_NOSE = new AspectShaping(0.01f, 0.9f, 30.0);

    @Override
    public Band band() {
        return Band.RADAR;
    }

    @Override
    public Signature modify(Signature in, TargetSnapshot self, SensorSnapshot sensor) {
        double speed = self.speed();
        if (speed < 1.0E-4) {
            // No heading, no aspect. A parked aircraft does not get to claim it is pointing at you.
            return in.scaled(Band.RADAR, broadside);
        }
        double dx = sensor.x() - self.x();
        double dy = sensor.eyeY() - self.y();
        double dz = sensor.z() - self.z();
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (range < 1.0E-4) {
            return in.scaled(Band.RADAR, broadside);
        }
        // cos of the angle between where it is going and where the sensor is.
        double cos = (self.vx() * dx + self.vy() * dy + self.vz() * dz) / (speed * range);
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
        double t = Math.min(1.0, angleDeg / Math.max(1.0, coneDeg));
        float factor = (float) (frontal + (broadside - frontal) * t * t);
        return in.scaled(Band.RADAR, factor);
    }
}
