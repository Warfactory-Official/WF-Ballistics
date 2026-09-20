package com.wf.wflib.entity.glyphid.flight;

import com.wf.wflib.drone.flight.Airframe;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** What a winged glyphid can do in the air. */
public final class GlyphidFlight {

    /** Tops out near 0.68 blocks/tick, seven times walking speed. */
    public static final Airframe WINGS = new Airframe(
            0.08, 2.0, Math.toRadians(50.0), 0.18,
            0.100, 0.060, 0.70, 0.25,
            0.6, 0.8, 200.0);

    /** Cruise speed, blocks/tick. Below the airframe's ceiling, leaving headroom to manoeuvre and climb. */
    public static final double CRUISE_SPEED = 0.55;

    /** How high above the terrain to hold: skimming, but clear of trees and buildings. */
    public static final int CLEARANCE = 7;

    /** How far ahead the terrain is sampled: about twenty ticks at cruise, which is what the climb needs. */
    public static final int LOOKAHEAD = 16;

    /** How many points along the path are sampled, beyond the one underfoot. */
    public static final int LOOKAHEAD_SAMPLES = 4;

    /** Gain on the altitude error. */
    private static final double ALTITUDE_GAIN = 0.08;

    /** Within this of the destination a glyphid stops flying at it and starts landing on it. */
    public static final double ARRIVAL_RANGE = 6.0;

    private GlyphidFlight() {
    }

    /**
     * The velocity the flight model should be asked for.
     *
     * @param position where the glyphid is
     * @param target where it is going; only the horizontal part is steered to, since the altitude is
     *      decided by terrain rather than by the destination
     * @param floorHeight the highest ground under and ahead of it, which is what it holds {@link #CLEARANCE}
     *      above
     * @param cruise speed to ask for, blocks/tick
     */
    public static Vec3 desiredVelocity(Vec3 position, Vec3 target, int floorHeight, double cruise) {
        double dx = target.x - position.x;
        double dz = target.z - position.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        double speed = distance < ARRIVAL_RANGE ? cruise * (distance / ARRIVAL_RANGE) : cruise;
        double vx = distance > 1.0E-6 ? dx / distance * speed : 0.0;
        double vz = distance > 1.0E-6 ? dz / distance * speed : 0.0;

        double wantY = floorHeight + CLEARANCE;
        double vy = Mth.clamp((wantY - position.y) * ALTITUDE_GAIN,
                -WINGS.maxDescentRate(), WINGS.maxClimbRate());

        return new Vec3(vx, vy, vz);
    }

    /**
     * @return the velocity that brings a glyphid straight down onto its landing spot.
     */
    public static Vec3 descentVelocity(Vec3 position, Vec3 target) {
        double dx = target.x - position.x;
        double dz = target.z - position.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double closing = Math.min(0.15, distance * 0.1);
        double vx = distance > 1.0E-6 ? dx / distance * closing : 0.0;
        double vz = distance > 1.0E-6 ? dz / distance * closing : 0.0;
        return new Vec3(vx, -0.35, vz);
    }
}
