package com.wf.wfballistics.entity.glyphid.flight;

import com.wf.wfballistics.drone.flight.Airframe;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What a winged glyphid can do in the air.
 *
 * <p>Flight runs on the drone's {@link com.wf.wfballistics.drone.flight.Multirotor} model, which is not the
 * cheat it sounds like. That model is built around one fact — the airframe can only push along its own up
 * axis, so it has to tip over to go anywhere — and a flying insect works the same way: it banks into a turn
 * rather than sliding sideways. Feeding it a different {@link Airframe} is the whole difference between a
 * delivery quadcopter and a bug.
 *
 * <p>The bug airframe leans harder and snaps into the lean faster than the quadcopter, and drags more, so it
 * darts and stops rather than gliding. It also descends faster than it climbs, which is the opposite of the
 * quadcopter: a multirotor diving into its own downwash stops flying, and a bug folding its wings does not.
 *
 * <p>The point of all this is not only that it looks right. A flying glyphid does no pathfinding at all, and
 * pathfinding is 44% of a marching swarm's tick — so wings make a glyphid <em>cheaper</em>, not dearer.
 */
public final class GlyphidFlight {

    /**
     * Tops out near 0.68 blocks/tick, about seven times the walking speed, which is what makes flight worth
     * having rather than a reskin. Solved from the model rather than declared: see {@link Airframe#topSpeed}.
     */
    public static final Airframe WINGS = new Airframe(
            0.08, 2.0, Math.toRadians(50.0), 0.18,
            0.100, 0.060, 0.70, 0.25,
            0.6, 0.8, 200.0);

    /**
     * Cruise speed, blocks/tick. Below the airframe's ceiling so there is headroom to manoeuvre and to climb.
     */
    public static final double CRUISE_SPEED = 0.55;

    /**
     * How high above the terrain a glyphid tries to hold. Low enough to read as a bug skimming the ground
     * rather than an aircraft, high enough to clear trees and buildings.
     */
    public static final int CLEARANCE = 7;

    /**
     * How far ahead the terrain is sampled. A glyphid that only watched the ground beneath it would fly into
     * the side of every hill: at cruise it covers this distance in about twenty ticks, which is the time the
     * climb rate needs to clear a hill of the same height.
     */
    public static final int LOOKAHEAD = 16;

    /**
     * How many points along the path are sampled, beyond the one underfoot.
     *
     * <p>More than one because a single sample at the far end has a blind spot in the middle, and the blind
     * spot is exactly where a hill crest sits: with only "here" and "sixteen blocks ahead", a ridge eight
     * blocks away reads as clear ground on both sides of itself and the glyphid flies into it. Embedding
     * itself in a hillside is the one failure the flight model cannot recover from on its own, because a bug
     * inside rock has nowhere to move.
     */
    public static final int LOOKAHEAD_SAMPLES = 4;

    /**
     * Gain on the altitude error. Deliberately gentle -- the vertical axis only has the margin between hover
     * and full throttle to work with, so a stiff gain spends the flight bouncing between them.
     */
    private static final double ALTITUDE_GAIN = 0.08;

    /**
     * Within this of the destination a glyphid stops flying at it and starts landing on it.
     */
    public static final double ARRIVAL_RANGE = 6.0;

    private GlyphidFlight() {
    }

    /**
     * The velocity the flight model should be asked for.
     *
     * @param position    where the glyphid is
     * @param target      where it is going; only the horizontal part is steered to, since the altitude is
     *                    decided by terrain rather than by the destination
     * @param floorHeight the highest ground under and ahead of it, which is what it holds {@link #CLEARANCE}
     *                    above
     * @param cruise      speed to ask for, blocks/tick
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
