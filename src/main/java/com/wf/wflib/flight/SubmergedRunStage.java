package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link MissileEntity.Phase#CRUISE} stage of a torpedo: run for the target holding station inside the corridor
 * between the seabed and the surface.
 */
public final class SubmergedRunStage implements FlightStage {

    public static final SubmergedRunStage INSTANCE = new SubmergedRunStage();

    /** Low-pass easing the commanded depth toward the freshly scanned corridor floor each tick. */
    private static final double DEPTH_TARGET_SMOOTHING = 0.25;
    /** Depth error (blocks) tolerated before correcting; kills seabed-sample jitter. */
    private static final double DEPTH_DEADBAND = 1.0;
    /** Proportional-control range for depth corrections. */
    private static final double DEPTH_RANGE = 12.0;
    /** How quickly vertical speed eases toward its target (0..1). */
    private static final double VERTICAL_SMOOTHING = 0.3;
    /** Terminal handoff range floor (blocks), and the run time that range is also required to cover. */
    private static final double TERMINAL_RANGE = 12.0;
    private static final double TERMINAL_TICKS = 40.0;

    private SubmergedRunStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double speed = missile.getCruiseSpeed();
        return new Vec3(ctx.nx() * speed, depthVelocity(missile, ctx), ctx.nz() * speed);
    }

    /** Eased, deadbanded vertical guidance onto the corridor. */
    public static double depthVelocity(MissileEntity missile, FlightContext ctx) {
        double commanded = ctx.clampToCorridor(ctx.safeAltitude());
        double targetY = missile.getCruiseTargetY();
        if (Double.isNaN(targetY)) {
            targetY = commanded;
        } else {
            targetY += (commanded - targetY) * DEPTH_TARGET_SMOOTHING;
        }
        missile.setCruiseTargetY(targetY);

        double maxSpeed = missile.getCruiseSpeed();
        double error = targetY - missile.getY();
        double desiredVy;
        if (Math.abs(error) < DEPTH_DEADBAND) {
            desiredVy = 0.0;
        } else {
            double corrected = error - Math.copySign(DEPTH_DEADBAND, error);
            desiredVy = Mth.clamp(corrected / DEPTH_RANGE, -maxSpeed, maxSpeed);
        }
        return Mth.lerp(VERTICAL_SMOOTHING, missile.getDeltaMovement().y, desiredVy);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        double range = Math.max(TERMINAL_RANGE, missile.getCruiseSpeed() * TERMINAL_TICKS);
        return ctx.horizontalDist() < range ? MissileEntity.Phase.ATTACK : null;
    }

    @Override
    public String id() {
        return "torpedo_run";
    }
}
