package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.flight.Airframe;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure guidance math shared by the drone state handlers: given where a drone is and where it wants to be, what
 * velocity should it be asking for.
 */
public final class Steering {

    /**
     * Radians per tick the airframe may yaw.
     */
    public static final float MAX_YAW_RATE = 0.25f;

    private Steering() {
    }

    /**
     * Level-flight steering: run at {@code speed} toward the target's horizontal position while closing on {@code
     * desiredY} no faster than {@code climbRate}.
     */
    public static Vec3 cruise(Vec3 from, Vec3 target, double desiredY, double speed, double climbRate) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(speed, horiz);
        if (horiz > 1.0E-4 && horiz < speed * 8.0) {
            step = Math.min(step, Math.max(speed * 0.15, horiz * 0.25));
        }
        double vx = horiz > 1.0E-4 ? dx / horiz * step : 0.0;
        double vz = horiz > 1.0E-4 ? dz / horiz * step : 0.0;
        return new Vec3(vx, verticalTo(from.y, desiredY, climbRate), vz);
    }

    /**
     * Attack-run steering: hold {@code speed} flat out toward the target with none of {@link #cruise}'s arrival
     * slowdown.
     */
    public static Vec3 dash(Vec3 from, Vec3 target, double desiredY, double speed, double climbRate) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4) {
            return new Vec3(0.0, verticalTo(from.y, desiredY, climbRate), 0.0);
        }
        return new Vec3(dx / horiz * speed, verticalTo(from.y, desiredY, climbRate), dz / horiz * speed);
    }

    /**
     * How far a payload released now would travel before it hits {@code targetY}, given the carrier's own motion.
     *
     * @param height drop height above the aim point
     * @param verticalUp the carrier's current vertical speed (positive climbing)
     * @param horizontal the carrier's horizontal speed
     * @param gravity the payload's downward acceleration per tick
     * @return the horizontal lead distance, in blocks
     */
    public static double ballisticLead(double height, double verticalUp, double horizontal, double gravity) {
        if (height <= 0.0 || gravity <= 0.0) {
            return 0.0;
        }
        double u = -verticalUp;
        double t = (-u + Math.sqrt(u * u + 2.0 * gravity * height)) / gravity;
        return Math.max(0.0, horizontal * t);
    }

    /**
     * How hard a hovering drone pulls itself back to the point it is holding, as a fraction of the offset per tick.
     */
    public static final double HOLD_GAIN = 0.35;

    /**
     * Hold a position: come to a stop over {@code target} and stay there while closing on {@code desiredY}.
     *
     * @param maxSpeed cap on the approach; pass 0 to command a dead stop
     * @param frame the airframe, for how hard it can brake
     */
    public static Vec3 hold(Vec3 from, Vec3 target, double desiredY, double maxSpeed, double climbRate,
                            Airframe frame) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = verticalTo(from.y, desiredY, climbRate);
        if (horizontal < 1.0E-4) {
            return new Vec3(0.0, vertical, 0.0);
        }
        double want = Math.min(maxSpeed, horizontal * HOLD_GAIN);
        want = Math.min(want, frame.approachSpeed(horizontal));
        return new Vec3(dx / horizontal * want, vertical, dz / horizontal * want);
    }

    /**
     * How hard a formation follower pulls itself back onto its slot, as a fraction of the offset per tick.
     */
    public static final double STATION_GAIN = 0.35;

    /**
     * Station-keeping on a <em>moving</em> slot: fly what the slot is flying, and correct whatever offset is left
     * on top of that.
     *
     * @param slotVelocity how fast the slot itself is travelling: the leader's velocity, since the
     *      formation is rigid about it
     * @param speed the follower's cruise speed
     * @param maxOverspeed how far above that it may push to close the gap
     * @param climbRate its nominal climb rate, which is also its catch-up authority on the vertical
     * @param frame the airframe, for how hard it brakes and how fast it may climb and descend
     */
    public static Vec3 station(Vec3 from, Vec3 slot, Vec3 slotVelocity, double speed, double maxOverspeed,
                               double climbRate, Airframe frame) {
        double dx = slot.x - from.x;
        double dz = slot.z - from.z;
        double offset = Math.sqrt(dx * dx + dz * dz);
        double vx = slotVelocity.x;
        double vz = slotVelocity.z;
        if (offset > 1.0E-4) {
            double close = Math.min(offset * STATION_GAIN, frame.approachSpeed(offset));
            close = Math.min(close, speed * Math.max(0.0, maxOverspeed - 1.0));
            vx += dx / offset * close;
            vz += dz / offset * close;
        }
        double slotSpeed = Math.sqrt(slotVelocity.x * slotVelocity.x + slotVelocity.z * slotVelocity.z);
        double cap = Math.max(speed, slotSpeed) * maxOverspeed;
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal > cap) {
            vx *= cap / horizontal;
            vz *= cap / horizontal;
        }
        double vy = slotVelocity.y + Mth.clamp((slot.y - from.y) * STATION_GAIN, -climbRate, climbRate);
        return new Vec3(vx, Mth.clamp(vy, -frame.maxDescentRate(), frame.maxClimbRate()), vz);
    }

    /** How quickly a drone closes an altitude error, as a fraction of it per tick. */
    public static final double VERTICAL_GAIN = 0.25;

    /**
     * @return the vertical velocity that closes on {@code desiredY} without exceeding {@code climbRate}.
     */
    public static double verticalTo(double currentY, double desiredY, double climbRate) {
        return Mth.clamp((desiredY - currentY) * VERTICAL_GAIN, -climbRate, climbRate);
    }

    /**
     * Fraction of its cruise speed a drone must be making horizontally before its velocity is taken as evidence of
     * which way it is going.
     */
    public static final double HEADING_MIN_SPEED = 0.25;

    /**
     * @return {@code current} yaw turned toward the direction of travel, rate limited. Holds the current
     *      heading when the drone is not meaningfully travelling, so a hovering or climbing drone doesn't spin.
     * @param minSpeed horizontal speed below which the velocity is treated as drift and the heading held.
     *      Normally {@link #HEADING_MIN_SPEED} of the drone's cruise speed
     */
    public static float faceTravel(Vec3 velocity, float current, double minSpeed) {
        double horiz = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horiz < Math.max(1.0E-3, minSpeed)) {
            return current;
        }
        float target = (float) Mth.atan2(velocity.x, velocity.z);
        float delta = wrap(target - current);
        return wrap(current + Mth.clamp(delta, -MAX_YAW_RATE, MAX_YAW_RATE));
    }

    /**
     * @return {@code current} yaw turned toward a place rather than toward a velocity, rate limited the same
     *      way {@link #faceTravel} is.
     */
    public static float faceToward(Vec3 from, Vec3 target, float current) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        if (dx * dx + dz * dz < 1.0E-6) {
            return current;
        }
        float want = (float) Mth.atan2(dx, dz);
        return wrap(current + Mth.clamp(wrap(want - current), -MAX_YAW_RATE, MAX_YAW_RATE));
    }

    /**
     * Wraps radians into [-PI, PI] so a turn across the seam stays short.
     */
    public static float wrap(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        angle %= twoPi;
        if (angle >= (float) Math.PI) {
            angle -= twoPi;
        } else if (angle < (float) -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }
}
