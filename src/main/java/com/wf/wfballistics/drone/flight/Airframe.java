package com.wf.wfballistics.drone.flight;

/**
 * The physical characteristics of a rotorcraft, in blocks and ticks. Everything the flight model needs to
 * turn "I want to be going that way" into "this is how far I can lean, how hard I can push, and how fast I
 * therefore end up going".
 *
 * <p>These are not tuning knobs layered on top of an arbitrary mover: they are the inputs to the standard
 * near-hover multirotor model in {@link Multirotor}, so the performance envelope (top speed, climb rate,
 * cruise attitude, power draw) falls out of them rather than being declared separately. Change
 * {@link #maxTilt} and the top speed changes with it, because on a multirotor those are the same fact.
 *
 * @param gravity           downward acceleration, blocks/tick²
 * @param maxThrustToWeight peak thrust as a multiple of the unladen weight; 1.0 could only ever hover
 * @param maxTilt           how far the airframe may lean from vertical, radians. This alone sets the top
 *                          speed, since horizontal force is {@code thrust * sin(tilt)}
 * @param tiltRate          how fast the attitude loop can move the lean, radians/tick. The reason a drone
 *                          eases into a turn instead of snapping onto a new heading
 * @param linearDrag        drag proportional to speed, per tick
 * @param quadraticDrag     drag proportional to speed², per tick. Together with {@link #maxTilt} this is
 *                          what makes the top speed finite
 * @param velocityGain      proportional gain of the horizontal velocity controller: how hard the drone
 *                          corrects a velocity error. Higher is snappier and twitchier
 * @param verticalGain      the same for the vertical axis, and deliberately gentler. Horizontally the drone
 *                          has a whole lean's worth of force to play with; vertically it only has the margin
 *                          between hovering and full throttle, so the same gain would spend the flight
 *                          bouncing off zero and maximum thrust
 * @param maxClimbRate      vertical rate limits, blocks/tick, standing in for the rotor efficiency losses
 * @param maxDescentRate    the descent limit is lower than the climb limit: a multirotor descending fast
 *                          settles into its own downwash and stops flying
 * @param hoverRotorSpeed   degrees/tick each rotor turns while hovering unladen. Thrust goes as ω², so
 *                          {@link #rotorSpeed} scales this by the square root of the thrust
 */
public record Airframe(double gravity, double maxThrustToWeight, double maxTilt, double tiltRate,
                       double linearDrag, double quadraticDrag, double velocityGain, double verticalGain,
                       double maxClimbRate, double maxDescentRate, double hoverRotorSpeed) {

    /**
     * The stock delivery quadcopter. Tuned so that the drone defaults line up with a sensible envelope:
     * cruising at 0.75 b/t sits near a 20° lean, the 1.2 b/t attack run is a hard 34° lean with headroom
     * left, and the airframe tops out around 1.4 b/t.
     */
    /**
     * Fraction of its theoretical braking force a drone plans around. Chosen against the flight model rather
     * than guessed: the shortfall is the time the attitude loop spends swinging the thrust axis round.
     */
    private static final double BRAKE_MARGIN = 0.30;

    public static final Airframe QUADCOPTER = new Airframe(
            0.08, 2.4, Math.toRadians(40.0), 0.10,
            0.030, 0.012, 0.55, 0.20,
            0.9, 0.7, 62.0);

    /**
     * @param massFactor total mass as a multiple of the unladen airframe
     * @return the acceleration full throttle can produce, before gravity. Loading a drone up costs it
     * exactly this much performance, which is why a laden drone climbs worse rather than merely costing more.
     */
    public double maxSpecificThrust(double massFactor) {
        return gravity * maxThrustToWeight / Math.max(1.0E-3, massFactor);
    }

    /**
     * How hard the airframe can be relied on to slow down, blocks/tick².
     *
     * <p>Well under the {@code g·tan(maxTilt)} it could theoretically pull, because reaching that means
     * having already tipped the thrust axis all the way over the other way, and {@link #tiltRate} says that
     * takes time the drone spends still travelling. Treating the theoretical figure as available is what
     * makes a drone sail through the point it was trying to stop on.
     */
    public double brakingAccel() {
        return gravity * Math.tan(maxTilt) * BRAKE_MARGIN;
    }

    /**
     * @return how much room this airframe needs to come to a stop from {@code speed}, from {@code v²/2a}.
     * What decides how far out a drone has to begin its approach if it means to arrive stationary.
     */
    public double stoppingDistance(double speed) {
        return speed * speed / (2.0 * brakingAccel());
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
     * component of a full lean. Solved rather than declared, so it always agrees with the model.
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
     *
     * <p>Rotor thrust goes as the square of the speed, so producing {@code n} times hover thrust means
     * turning {@code √n} times as fast. That one relation is the whole of the rotor animation: the discs
     * visibly wind up as a drone lifts a crate or claws over a ridge and coast back down as it settles, with
     * no animation state of its own to keep in step with anything.
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
