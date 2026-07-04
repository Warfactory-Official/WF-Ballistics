package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Steep terminal dive: bleed horizontal speed as the target nears while accelerating straight down under
 * gravity.
 */
public final class AttackStage implements FlightStage {

    public static final AttackStage INSTANCE = new AttackStage();

    // Horizontal speed scales to zero as the missile closes this distance (matches the cruise hand-off range).
    private static final double DECEL_RANGE = 30.0;
    // Downward acceleration accumulated each tick during the dive (gravity + a bit of thrust).
    private static final double DIVE_GRAVITY = 0.06;
    // Terminal (maximum) dive speed.
    private static final double TERMINAL_FALL_VELOCITY = -14.0;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double horizontalSpeed = missile.getCruiseSpeed() * (ctx.horizontalDist() / DECEL_RANGE);
        double vy = Math.max(missile.getDeltaMovement().y - DIVE_GRAVITY, TERMINAL_FALL_VELOCITY);
        return new Vec3(ctx.nx() * horizontalSpeed, vy, ctx.nz() * horizontalSpeed);
    }

    // next() defaults to null: the attack run ends on impact, not on a phase change.

    @Override
    public String id() {
        return "attack";
    }
}
