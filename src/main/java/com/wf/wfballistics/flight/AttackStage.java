package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Steep terminal dive: bleed horizontal speed as the target nears while accelerating straight down. */
public final class AttackStage implements FlightStage {

    public static final AttackStage INSTANCE = new AttackStage();

    // Horizontal speed scales to zero as the missile closes this distance (matches the cruise hand-off range).
    private static final double DECEL_RANGE = 30.0;
    // Steep attack-dive speed (larger than cruise, as if under gravity).
    private static final float TERMINAL_FALL_VELOCITY = -8.0f;
    // How quickly vertical speed eases toward the dive velocity.
    private static final float VERTICAL_SMOOTHING = 0.01f;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double horizontalSpeed = missile.getCruiseSpeed() * (ctx.horizontalDist() / DECEL_RANGE);
        double vy = Mth.lerp(VERTICAL_SMOOTHING, (float) missile.getDeltaMovement().y, TERMINAL_FALL_VELOCITY);
        return new Vec3(ctx.nx() * horizontalSpeed, vy, ctx.nz() * horizontalSpeed);
    }

    // next() defaults to null: the attack run ends on impact, not on a phase change.

    @Override
    public String id() {
        return "attack";
    }
}
