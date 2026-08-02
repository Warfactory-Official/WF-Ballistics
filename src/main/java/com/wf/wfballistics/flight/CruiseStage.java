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
        double maxSpeed = missile.getCruiseSpeed();
        double vy = verticalVelocity(missile, ctx);
        return new Vec3(ctx.nx() * maxSpeed, vy, ctx.nz() * maxSpeed);
    }

    /**
     * Eased, deadbanded vertical guidance toward the terrain-safe (or fixed) cruise altitude. Shared with
     * {@link ApproachStage} so a directional run holds the same smooth altitude while it steers horizontally.
     */
    public static double verticalVelocity(MissileEntity missile, FlightContext ctx) {
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
        return Mth.lerp(VERTICAL_SMOOTHING, missile.getDeltaMovement().y, desiredVy);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return ctx.horizontalDist() < handoffRange(missile, ctx) ? MissileEntity.Phase.ATTACK : null;
    }

    /**
     * The horizontal range at which to hand off to the terminal attack: the straight-dive horizontal
     * ({@code height/tan}) plus, only for a terminal stage that flies a curved pitch-over (see
     * {@link FlightStage#needsPitchoverLead}), the pitch-over lead ({@code r·tan(theta/2)}, r = terminal speed /
     * turn rate) so that stage can reach the aim on the resolved dive angle without a turn tighter than its
     * radius. A stage that dives straight in adds no lead, so its handoff (and its terminal evasion) doesn't
     * start out ahead of the real descent. Floored at {@link #BRAKING_RANGE}.
     */
    private static double handoffRange(MissileEntity missile, FlightContext ctx) {
        double theta = Math.toRadians(missile.resolveDiveAngle(ctx));
        double tan = Math.tan(theta);
        double height = missile.getY() - ctx.target().y;
        if (tan <= 1.0e-4 || height <= 0.0) {
            return BRAKING_RANGE;
        }
        double lead = 0.0;
        if (missile.attackStage().needsPitchoverLead()) {
            double turnRate = missile.getMaxTurnRate();
            double radius = turnRate > 1.0e-4 ? AttackStage.terminalSpeed(missile) / turnRate : 0.0;
            lead = radius * Math.tan(theta / 2.0);
        }
        double required = height / tan + lead;
        return Math.max(BRAKING_RANGE, required);
    }

    @Override
    public String id() {
        return "cruise";
    }
}
