package com.wf.wfballistics.drone.flight;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The flight model: turns a desired velocity into the attitude, thrust and resulting motion of a real multirotor.
 */
public final class Multirotor {

    /** Floor on the vertical component of the thrust axis when solving for throttle. */
    private static final double MIN_LIFT_FRACTION = 0.35;

    private Multirotor() {
    }

    /** Fly one tick, with nothing fed forward. */
    public static Step step(Vec3 velocity, FlightAttitude attitude, Vec3 desired, Airframe frame,
                            double massFactor) {
        return step(velocity, attitude, desired, Vec3.ZERO, frame, massFactor);
    }

    /**
     * Fly one tick.
     *
     * @param velocity current velocity, blocks/tick
     * @param attitude current lean and throttle
     * @param desired the velocity the guidance layer wants
     * @param feedForward what the guidance layer's own reference is accelerating at, blocks/tick². Added to
     *      the commanded acceleration rather than being something the drone is asked to
     *      achieve, so the airframe leans into a manoeuvre on the guidance layer's say-so
     *      instead of waiting to be proved wrong by its own tracking error
     * @param frame the airframe's physical limits
     * @param massFactor total mass as a multiple of the unladen airframe; a slung crate raises it
     * @return the velocity and attitude after this tick
     */
    public static Step step(Vec3 velocity, FlightAttitude attitude, Vec3 desired, Vec3 feedForward,
                            Airframe frame, double massFactor) {
        double desiredSpeed = desired.length();
        Vec3 dragHold = desiredSpeed > 1.0E-6
                ? desired.scale(frame.dragAt(desiredSpeed) / desiredSpeed) : Vec3.ZERO;
        Vec3 error = desired.subtract(velocity);
        Vec3 commanded = new Vec3(error.x * frame.velocityGain(),
                error.y * frame.verticalGain(),
                error.z * frame.velocityGain())
                .add(dragHold)
                .add(feedForward);

        double liftDemand = commanded.y + frame.gravity();
        double sideDemand = Math.sqrt(commanded.x * commanded.x + commanded.z * commanded.z);

        double wantTilt = Math.min(frame.maxTilt(),
                Math.atan2(sideDemand, Math.max(liftDemand, frame.gravity() * MIN_LIFT_FRACTION)));
        double dirX = sideDemand > 1.0E-9 ? commanded.x / sideDemand : 0.0;
        double dirZ = sideDemand > 1.0E-9 ? commanded.z / sideDemand : 0.0;

        double deltaX = dirX * wantTilt - attitude.tiltX();
        double deltaZ = dirZ * wantTilt - attitude.tiltZ();
        double delta = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double tiltX = dirX * wantTilt;
        double tiltZ = dirZ * wantTilt;
        if (delta > frame.tiltRate()) {
            double scale = frame.tiltRate() / delta;
            tiltX = attitude.tiltX() + deltaX * scale;
            tiltZ = attitude.tiltZ() + deltaZ * scale;
        }
        FlightAttitude achieved = new FlightAttitude(tiltX, tiltZ, 0.0);

        Vec3 axis = achieved.thrustAxis();
        double thrust = Mth.clamp(liftDemand / Math.max(MIN_LIFT_FRACTION, axis.y),
                0.0, frame.maxSpecificThrust(massFactor));
        double throttle = thrust / frame.gravity();

        double speed = velocity.length();
        Vec3 drag = speed > 1.0E-6 ? velocity.scale(frame.dragAt(speed) / speed) : Vec3.ZERO;
        Vec3 next = velocity
                .add(axis.scale(thrust))
                .subtract(0.0, frame.gravity(), 0.0)
                .subtract(drag);
        next = new Vec3(next.x, Mth.clamp(next.y, -frame.maxDescentRate(), frame.maxClimbRate()), next.z);
        return new Step(next, new FlightAttitude(tiltX, tiltZ, throttle));
    }

    /**
     * @return the lean a drone must hold to cruise at {@code speed}, radians. Exposed for the readouts and
     *      the self-test, so what is reported is solved from the same model that flies the drone.
     */
    public static double cruiseTilt(Airframe frame, double speed) {
        return Math.min(frame.maxTilt(), Math.atan2(frame.dragAt(speed), frame.gravity()));
    }

    /**
     * @return the throttle needed to hold level flight at {@code speed}. Above 1.0 for any speed at all,
     *      because leaning over spends lift.
     */
    public static double cruiseThrottle(Airframe frame, double speed) {
        return 1.0 / Math.cos(cruiseTilt(frame, speed));
    }

    /**
     * One tick of flight.
     */
    public record Step(Vec3 velocity, FlightAttitude attitude) {
    }
}
