package com.wf.wfballistics.drone.flight;

/**
 * The physical characteristics of a rotorcraft, in blocks and ticks.
 *
 * @param gravity downward acceleration, blocks/tick²
 * @param maxThrustToWeight peak thrust as a multiple of the unladen weight; 1.0 could only ever hover
 * @param maxTilt how far the airframe may lean from vertical, radians. This alone sets the top
 *      speed, since horizontal force is {@code thrust * sin(tilt)}
 * @param tiltRate how fast the attitude loop can move the lean, radians/tick. The reason a drone
 *      eases into a turn instead of snapping onto a new heading
 * @param linearDrag drag proportional to speed, per tick
 * @param quadraticDrag drag proportional to speed², per tick. Together with {@link #maxTilt} this is
 *      what makes the top speed finite
 * @param velocityGain proportional gain of the horizontal velocity controller: how hard the drone
 *      corrects a velocity error. Higher is snappier and twitchier
 * @param verticalGain the same for the vertical axis, and deliberately gentler. Horizontally the drone
 *      has a whole lean's worth of force to play with; vertically it only has the margin
 *      between hovering and full throttle, so the same gain would spend the flight
 *      bouncing off zero and maximum thrust
 * @param maxClimbRate vertical rate limits, blocks/tick, standing in for the rotor efficiency losses
 * @param maxDescentRate the descent limit is lower than the climb limit: a multirotor descending fast
 *      settles into its own downwash and stops flying
 * @param hoverRotorSpeed degrees/tick each rotor turns while hovering unladen. Thrust goes as ω², so
 *      {@link #rotorSpeed} scales this by the square root of the thrust
 */
public record Airframe(double gravity, double maxThrustToWeight, double maxTilt, double tiltRate,
                       double linearDrag, double quadraticDrag, double velocityGain, double verticalGain,
                       double maxClimbRate, double maxDescentRate, double hoverRotorSpeed) {

    /** Fraction of its theoretical braking force a drone plans around. */
    private static final double BRAKE_MARGIN = 0.30;

    /** The Amazog: the delivery airframe, an eight-rotor lifter built to carry a crate and not much else. */
    public static final Airframe AMAZOG = new Airframe(
            0.08, 2.4, Math.toRadians(40.0), 0.10,
            0.030, 0.012, 0.55, 0.20,
            0.9, 0.7, 62.0);

    /**
     * The Mavic: a one-block reconnaissance quad, and deliberately a different aircraft rather than a smaller
     * drawing of the same one.
     */
    public static final Airframe MAVIC = new Airframe(
            0.08, 3.2, Math.toRadians(50.0), 0.18,
            0.045, 0.020, 0.80, 0.28,
            1.2, 0.9, 86.0);

    /**
     * @param massFactor total mass as a multiple of the unladen airframe
     * @return the acceleration full throttle can produce, before gravity. Loading a drone up costs it
     *      exactly this much performance, which is why a laden drone climbs worse rather than merely costing more.
     */
    public double maxSpecificThrust(double massFactor) {
        return gravity * maxThrustToWeight / Math.max(1.0E-3, massFactor);
    }

    /** How hard the airframe can be relied on to slow down, blocks/tick². */
    public double brakingAccel() {
        return gravity * Math.tan(maxTilt) * BRAKE_MARGIN;
    }

    /**
     * @return ticks to swing the thrust axis from a full lean one way to a full lean the other.
     */
    public double reversalTicks() {
        return 2.0 * maxTilt / tiltRate;
    }

    /**
     * @return how much room this airframe needs to come to a stop from {@code speed}.
     */
    public double stoppingDistance(double speed) {
        return speed * reversalTicks() + speed * speed / (2.0 * brakingAccel());
    }

    /**
     * @return the fastest this airframe may approach something {@code distance} away and still arrive
     *      stopped. The inverse of {@link #stoppingDistance}, solved for speed:
     *      {@code v = a(√(t² + 2d/a) − t)}.
     */
    public double approachSpeed(double distance) {
        if (distance <= 0.0) {
            return 0.0;
        }
        double a = brakingAccel();
        double t = reversalTicks();
        return a * (Math.sqrt(t * t + 2.0 * distance / a) - t);
    }

    /**
     * @return the deceleration drag applies at {@code speed}.
     */
    public double dragAt(double speed) {
        double s = Math.abs(speed);
        return linearDrag * s + quadraticDrag * s * s;
    }

    /**
     * @return the fastest this airframe can fly level: the speed at which drag exactly eats the horizontal
     *      component of a full lean. Solved rather than declared, so it always agrees with the model.
     */
    public double topSpeed(double massFactor) {
        double horizontal = Math.min(maxSpecificThrust(massFactor), gravity / Math.cos(maxTilt))
                * Math.sin(maxTilt);
        if (quadraticDrag <= 1.0E-9) {
            return horizontal / Math.max(1.0E-9, linearDrag);
        }
        double disc = linearDrag * linearDrag + 4.0 * quadraticDrag * horizontal;
        return (-linearDrag + Math.sqrt(disc)) / (2.0 * quadraticDrag);
    }

    /**
     * @return rotor speed as a multiple of the hover speed.
     */
    public double rotorScale(double throttle, double massFactor) {
        return Math.sqrt(Math.max(0.0, throttle * massFactor));
    }

    /**
     * @return how fast the rotors turn, degrees/tick, at this throttle and load.
     */
    public double rotorSpeed(double throttle, double massFactor) {
        return hoverRotorSpeed * rotorScale(throttle, massFactor);
    }
}
