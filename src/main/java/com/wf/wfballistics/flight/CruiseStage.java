package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Fly toward the target while holding a terrain-safe (or fixed) altitude, easing altitude corrections so the
 * missile doesn't twitch, until it closes inside {@link #BRAKING_RANGE} and hands off to the terminal dive.
 */
public final class CruiseStage implements FlightStage {

    public static final CruiseStage INSTANCE = new CruiseStage();
    /**
     * Horizontal distance to the target at which cruise hands off to the terminal attack.
     */
    public static final double BRAKING_RANGE = 30.0;
    // Low-pass factor easing the guidance altitude toward the raw heightmap scan each tick.
    private static final double TERRAIN_TARGET_SMOOTHING = 0.12;
    // Height error (blocks) tolerated before correcting; kills flat-ground jitter.
    private static final double ALTITUDE_DEADBAND = 3.0;
    // How quickly vertical speed eases toward its target (0..1); lower = gentler.
    private static final double VERTICAL_SMOOTHING = 0.15;
    // Proportional-control range for altitude corrections.
    private static final double DAMPENING_RANGE = 50.0;

    private CruiseStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        // Ease the guidance altitude toward the freshly scanned safe height so terrain-sample noise doesn't
        // turn into constant vertical twitching. cruiseTargetY is per-missile memory kept on the entity.
        double targetY = missile.getCruiseTargetY();
        if (Double.isNaN(targetY)) {
            targetY = ctx.safeAltitude();
        } else {
            targetY += (ctx.safeAltitude() - targetY) * TERRAIN_TARGET_SMOOTHING;
        }
        missile.setCruiseTargetY(targetY);

        double maxSpeed = missile.getCruiseSpeed();
        double error = targetY - missile.getY();
        double desiredVy;
        if (Math.abs(error) < ALTITUDE_DEADBAND) {
            // Within tolerance: hold level instead of chasing every small deviation.
            desiredVy = 0.0;
        } else {
            // Proportional control measured from the edge of the deadband (no step at the boundary).
            double corrected = error - Math.copySign(ALTITUDE_DEADBAND, error);
            desiredVy = Mth.clamp(corrected / DAMPENING_RANGE, -maxSpeed, maxSpeed);
        }
        double vy = Mth.lerp(VERTICAL_SMOOTHING, missile.getDeltaMovement().y, desiredVy);
        return new Vec3(ctx.nx() * maxSpeed, vy, ctx.nz() * maxSpeed);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return ctx.horizontalDist() < handoffRange(missile, ctx) ? MissileEntity.Phase.ATTACK : null;
    }

    /**
     * The horizontal range at which to hand off to the terminal attack. Best fit uses the fixed
     * {@link #BRAKING_RANGE}; a desired attack angle starts the run at the range where the angled approach
     * line becomes reachable from the current altitude, so a shallow angle gets room to descend onto it.
     */
    private static double handoffRange(MissileEntity missile, FlightContext ctx) {
        double angle = missile.getAttackAngle();
        if (Double.isNaN(angle)) {
            return BRAKING_RANGE;
        }
        double theta = Math.toRadians(Mth.clamp(angle, MissileEntity.MIN_ATTACK_ANGLE, MissileEntity.MAX_ATTACK_ANGLE));
        double height = missile.getY() - ctx.target().y;
        double required = height > 0.0 ? height / Math.tan(theta) : BRAKING_RANGE;
        return Math.max(BRAKING_RANGE, required);
    }

    @Override
    public String id() {
        return "cruise";
    }
}
