package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
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
        return estimateTicks(from, target, cruiseSpeed, ascentSpeed, cruiseAltitude, loiterTicks, null,
                MissileEntity.DEFAULT_APPROACH_JOIN_CAP);
    }

    /**
     * As {@link #estimateTicks(Vec3, Vec3, double, double, double, int)}, but charging for the longer curved run
     * of a directional strike: {@code approachDir} (from the target toward the commanded approach side, or null
     * for a straight-in attack) makes the transit follow {@link ApproachStage#approachHorizontalDistance}, with
     * {@code approachCap} the per-missile join ceiling.
     */
    public static int estimateTicks(Vec3 from, Vec3 target, double cruiseSpeed, double ascentSpeed,
                                    double cruiseAltitude, int loiterTicks, Vec3 approachDir, double approachCap) {
        double horiz = ApproachStage.approachHorizontalDistance(from, target, approachDir, approachCap);
        double speed = Math.max(0.05, cruiseSpeed);

        double climb = Math.max(0.0, cruiseAltitude - from.y) / Math.max(0.05, ascentSpeed);
        double transit = horiz / speed;
        double descent = Math.max(0.0, cruiseAltitude - target.y) / DESCENT_SPEED;
        double total = climb + transit + descent + Math.max(0, loiterTicks);
        return (int) Math.round(Math.max(0.0, total));
    }

    /**
     * Ticks of powered flight needed to actually reach the target: the estimated flight time plus 25% headroom
     * and a small fixed buffer, so a missile auto-fuelled to this value arrives under power instead of running
     * dry and coasting the last leg. Fuel burns at 1 tick of flight per tick.
     */
    public static int fuelToReach(Vec3 from, Vec3 target, double cruiseSpeed, double ascentSpeed,
                                  double cruiseAltitude, int loiterTicks) {
        return fuelToReach(from, target, cruiseSpeed, ascentSpeed, cruiseAltitude, loiterTicks, null,
                MissileEntity.DEFAULT_APPROACH_JOIN_CAP);
    }

    /**
     * As {@link #fuelToReach(Vec3, Vec3, double, double, double, int)}, but sizing the tank for the longer curved
     * run of a directional strike (see {@code approachDir}, {@code approachCap}), so the detour doesn't leave it
     * coasting dry.
     */
    public static int fuelToReach(Vec3 from, Vec3 target, double cruiseSpeed, double ascentSpeed,
                                  double cruiseAltitude, int loiterTicks, Vec3 approachDir, double approachCap) {
        int est = estimateTicks(from, target, cruiseSpeed, ascentSpeed, cruiseAltitude, loiterTicks, approachDir,
                approachCap);
        return (int) Math.ceil(est * 1.25) + 40;
    }
}
