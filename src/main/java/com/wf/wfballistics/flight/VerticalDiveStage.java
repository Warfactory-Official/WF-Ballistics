package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Near-vertical top-attack dive: bleeds horizontal speed hard over a short range and plunges faster than the
 * standard {@link AttackStage} — a drone / loitering-munition strike straight down onto the target.
 */
public final class VerticalDiveStage implements FlightStage {

    public static final VerticalDiveStage INSTANCE = new VerticalDiveStage();

    private static final double DECEL_RANGE = 12.0;
    private static final double DIVE_GRAVITY = 0.1;
    private static final double TERMINAL_FALL_VELOCITY = -18.0;

    private VerticalDiveStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double horizontalSpeed = missile.getCruiseSpeed() * Math.min(1.0, ctx.horizontalDist() / DECEL_RANGE);
        double vy = Math.max(missile.getDeltaMovement().y - DIVE_GRAVITY, TERMINAL_FALL_VELOCITY);
        return new Vec3(ctx.nx() * horizontalSpeed, vy, ctx.nz() * horizontalSpeed);
    }

    @Override
    public String id() {
        return "dive";
    }
}
