package com.wf.wfballistics.drone;

import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.Multirotor;

/**
 * How fast a drone's battery drains.
 *
 * @param idleDrain charge/tick parked on the ground
 * @param hoverDrain charge/tick holding a stationary hover, unladen. The reference point everything else
 *      is scaled from
 * @param transitDrain charge/tick in level cruise at {@link #REFERENCE_SPEED}. Only used to derive the
 *      drag term
 * @param cargoMultiplier how much more a laden drone costs to hover. Converted to a mass ratio by
 *      {@link #massFactor}, because that is what it physically is
 */
public record PowerProfile(double idleDrain, double hoverDrain, double transitDrain, double cargoMultiplier) {

    public static final PowerProfile DEFAULT = new PowerProfile(0.0, 0.6, 1.0, 1.35);

    /**
     * The cruise speed {@link #transitDrain} is quoted at, matching the stock drone's own cruise setting.
     */
    public static final double REFERENCE_SPEED = 0.75;

    /**
     * @return total mass as a multiple of the unladen airframe.
     */
    public double massFactor(boolean loaded) {
        return loaded ? Math.cbrt(cargoMultiplier * cargoMultiplier) : 1.0;
    }

    /**
     * @return the drag coefficient of the battery: whatever cruising costs over and above hovering, spread
     *      over speed².
     */
    public double parasiticDrain() {
        return Math.max(0.0, transitDrain - hoverDrain) / (REFERENCE_SPEED * REFERENCE_SPEED);
    }

    /**
     * What this tick actually costs.
     *
     * @param throttle thrust as a multiple of unladen hover, straight off the flight model
     * @param massFactor total mass as a multiple of the unladen airframe
     * @param speed how fast it is going, blocks/tick
     */
    public double drain(DroneState state, double throttle, double massFactor, double speed) {
        if (!state.airborne()) {
            return idleDrain;
        }
        if (state == DroneState.DEPLETED || state == DroneState.DOWNED) {
            return 0.0;
        }
        double thrust = Math.max(0.0, throttle * massFactor);
        return hoverDrain * Math.pow(thrust, 1.5) + parasiticDrain() * speed * speed;
    }

    /**
     * @return charge/tick in steady level flight at {@code speed}: the same calculation as {@link #drain},
     *      fed the throttle the flight model says that speed needs. This is what the mission planning below is
     *      built on, so a range estimate and the flight it predicts agree by construction.
     */
    public double cruiseDrain(Airframe frame, double speed, boolean loaded) {
        return drain(DroneState.TRANSIT, Multirotor.cruiseThrottle(frame, speed), massFactor(loaded), speed);
    }

    /**
     * @return charge burned per block of horizontal travel at {@code speed} blocks/tick.
     */
    public double costPerBlock(Airframe frame, double speed, boolean loaded) {
        return cruiseDrain(frame, speed, loaded) / Math.max(1.0E-3, speed);
    }

    public double costToTravel(Airframe frame, double distance, double speed, boolean loaded) {
        return Math.max(0.0, distance) * costPerBlock(frame, speed, loaded);
    }

    public double costToHover(int ticks, boolean loaded) {
        double massFactor = massFactor(loaded);
        return Math.max(0, ticks) * drain(DroneState.DELIVER, 1.0, massFactor, 0.0);
    }

    /**
     * @return how far the drone could still fly on {@code charge}, in blocks.
     */
    public double rangeFor(Airframe frame, double charge, double speed, boolean loaded) {
        return Math.max(0.0, charge) / Math.max(1.0E-6, costPerBlock(frame, speed, loaded));
    }
}
