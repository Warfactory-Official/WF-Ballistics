package com.wf.wflib.drone.flight;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/**
 * How a rotorcraft is currently leaning, and how hard it is pushing.
 *
 * @param throttle thrust as a multiple of what it takes to hover unladen, so 1.0 is a stationary hover, less
 *      is sinking and more is climbing or leaning
 */
public record FlightAttitude(double tiltX, double tiltZ, double throttle) {

    /**
     * Level and holding a hover.
     */
    public static final FlightAttitude LEVEL = new FlightAttitude(0.0, 0.0, 1.0);
    /**
     * Level with the rotors stopped, for a drone that is not flying at all.
     */
    public static final FlightAttitude STOPPED = new FlightAttitude(0.0, 0.0, 0.0);

    /**
     * @return the total lean from vertical, radians.
     */
    public double tilt() {
        return Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
    }

    /**
     * @return the unit vector the thrust actually points along. This is the whole of a multirotor's steering:
     *      it has no control surfaces and no vectoring, so every force it can produce is along this axis.
     */
    public Vec3 thrustAxis() {
        double tilt = tilt();
        if (tilt < 1.0E-9) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        double spread = Math.sin(tilt) / tilt;
        return new Vec3(tiltX * spread, Math.cos(tilt), tiltZ * spread);
    }

    /**
     * @param yaw the airframe's heading, radians about {@code +Y}
     * @return lean about the nose axis: positive is right wing down.
     */
    public float roll(float yaw) {
        return (float) (tiltX * Math.cos(yaw) - tiltZ * Math.sin(yaw));
    }

    /**
     * @param yaw the airframe's heading, radians about {@code +Y}
     * @return lean about the wing axis: positive is nose down.
     */
    public float pitch(float yaw) {
        return (float) (tiltX * Math.sin(yaw) + tiltZ * Math.cos(yaw));
    }

    /** The rotation that tips the world's up axis over into the lean direction. */
    public static Quaternionf leanRotation(double tiltX, double tiltZ) {
        return leanRotation(tiltX, tiltZ, new Quaternionf());
    }

    /** The same rotation written into {@code dest}. */
    public static Quaternionf leanRotation(double tiltX, double tiltZ, Quaternionf dest) {
        double tilt = Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
        if (tilt < 1.0E-6) {
            return dest.identity();
        }
        return dest.fromAxisAngleRad(
                (float) (tiltZ / tilt), 0.0f, (float) (-tiltX / tilt), (float) tilt);
    }

    /**
     * The same rotation at double precision, for the hitbox.
     */
    public static Quaterniond leanRotationPrecise(double tiltX, double tiltZ) {
        return leanRotationPrecise(tiltX, tiltZ, new Quaterniond());
    }

    /**
     * The hitbox counterpart of {@link #leanRotation(double, double, Quaternionf)}, rebuilt every tick for every
     * drone that moved.
     */
    public static Quaterniond leanRotationPrecise(double tiltX, double tiltZ, Quaterniond dest) {
        double tilt = Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
        if (tilt < 1.0E-6) {
            return dest.identity();
        }
        return dest.fromAxisAngleRad(tiltZ / tilt, 0.0, -tiltX / tilt, tilt);
    }

    public Quaternionf leanRotation() {
        return leanRotation(tiltX, tiltZ);
    }

    /**
     * Relax toward level, for a drone that has lost power and is no longer holding an attitude.
     */
    public FlightAttitude relax(double rate) {
        double factor = Mth.clamp(1.0 - rate, 0.0, 1.0);
        return new FlightAttitude(tiltX * factor, tiltZ * factor, 0.0);
    }

    /**
     * Tip further over, for a wreck tumbling out of the sky.
     */
    public FlightAttitude tumble(double rate, double limit) {
        double tilt = tilt();
        double x = tilt < 1.0E-4 ? 1.0 : tiltX / tilt;
        double z = tilt < 1.0E-4 ? 0.0 : tiltZ / tilt;
        double next = Math.min(limit, tilt + rate);
        return new FlightAttitude(x * next, z * next, 0.0);
    }

    /**
     * Tip over toward a direction rather than further along the lean it already has: a wreck goes over the side its
     * lift stopped on, which is not necessarily the way it happened to be leaning when it was hit.
     *
     * @param x world-space X of the direction to drop, need not be normalised
     * @param z world-space Z of the same
     * @param rate radians per tick
     * @param limit how far over it can end up
     */
    public FlightAttitude tumbleToward(double x, double z, double rate, double limit) {
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-6) {
            return tumble(rate, limit);
        }
        double ux = x / length;
        double uz = z / length;
        double along = tiltX * ux + tiltZ * uz;
        double next = Math.min(limit, along + rate);
        return new FlightAttitude(ux * next, uz * next, 0.0);
    }
}
