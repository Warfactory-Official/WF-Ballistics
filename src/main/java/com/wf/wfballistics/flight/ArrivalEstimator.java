package com.wf.wfballistics.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Rough time-to-impact estimate for a missile, from its dominant flight terms
 */
public final class ArrivalEstimator {

    private static final double DESCENT_SPEED = 8.0;   // rough terminal dive speed

    private ArrivalEstimator() {
    }

    /**
     * @param from           launch or current position
     * @param target         aim point
     * @param cruiseSpeed    horizontal cruise speed (blocks/tick)
     * @param ascentSpeed    vertical boost speed during the climb (blocks/tick)
     * @param cruiseAltitude absolute Y the missile climbs to before cruising
     * @param loiterTicks    extra ticks spent loitering (0 for a straight-in attack)
     * @return estimated ticks to impact (never negative)
     */
    public static int estimateTicks(Vec3 from, Vec3 target, double cruiseSpeed, double ascentSpeed,
                                    double cruiseAltitude, int loiterTicks) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.max(0.05, cruiseSpeed);

        double climb = Math.max(0.0, cruiseAltitude - from.y) / Math.max(0.05, ascentSpeed);
        double transit = horiz / speed;
        double descent = Math.max(0.0, cruiseAltitude - target.y) / DESCENT_SPEED;
        double total = climb + transit + descent + Math.max(0, loiterTicks);
        return (int) Math.round(Math.max(0.0, total));
    }
}
