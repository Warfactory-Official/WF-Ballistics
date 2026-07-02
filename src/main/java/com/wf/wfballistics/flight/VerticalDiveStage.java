package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Near-vertical top-attack dive: bleeds horizontal speed hard over a short range and plunges faster than the
 * standard {@link AttackStage} — a drone / loitering-munition strike straight down onto the target.
 */
public final class VerticalDiveStage implements FlightStage {

    public static final VerticalDiveStage INSTANCE = new VerticalDiveStage();

    // Horizontal speed is bled out over this (short) closing distance for a steep, near-vertical descent.
    private static final double DECEL_RANGE = 12.0;
    // Faster terminal fall than the standard dive.
    private static final float TERMINAL_FALL_VELOCITY = -14.0f;
    private static final float VERTICAL_SMOOTHING = 0.05f;

    private VerticalDiveStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double horizontalSpeed = missile.getCruiseSpeed() * Math.min(1.0, ctx.horizontalDist() / DECEL_RANGE);
        double vy = Mth.lerp(VERTICAL_SMOOTHING, (float) missile.getDeltaMovement().y, TERMINAL_FALL_VELOCITY);
        return new Vec3(ctx.nx() * horizontalSpeed, vy, ctx.nz() * horizontalSpeed);
    }

    @Override
    public String id() {
        return "dive";
    }
}
