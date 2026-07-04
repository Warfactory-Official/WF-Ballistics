package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Steep terminal dive: bleed horizontal speed as the target nears while accelerating straight down under
 * gravity. When the missile carries a {@link MissileEntity#getAttackAngle() desired attack angle} it instead
 * flies a pure-pursuit approach onto the line through the target at that angle, so it strikes at the commanded
 * angle below horizontal rather than the natural best-fit dive.
 */
public final class AttackStage implements FlightStage {

    public static final AttackStage INSTANCE = new AttackStage();

    // Horizontal speed scales to zero as the missile closes this distance (matches the cruise hand-off range).
    private static final double DECEL_RANGE = 30.0;
    // Downward acceleration accumulated each tick during the dive (gravity + a bit of thrust).
    private static final double DIVE_GRAVITY = 0.06;
    // Terminal (maximum) dive speed.
    private static final double TERMINAL_FALL_VELOCITY = -14.0;
    // Carrot distance (blocks) ahead along the approach line for the angled pure-pursuit run.
    private static final double LOOKAHEAD = 12.0;
    // Commanded closing speed along the angled approach; the missile still spools toward it under thrust.
    private static final double APPROACH_SPEED = 14.0;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double angle = missile.getAttackAngle();
        if (!Double.isNaN(angle)) {
            return guideAngled(missile, ctx, angle);
        }
        double horizontalSpeed = missile.getCruiseSpeed() * Math.min(1.0, ctx.horizontalDist() / DECEL_RANGE);
        double vy = Math.max(missile.getDeltaMovement().y - DIVE_GRAVITY, TERMINAL_FALL_VELOCITY);
        return new Vec3(ctx.nx() * horizontalSpeed, vy, ctx.nz() * horizontalSpeed);
    }

    private Vec3 guideAngled(MissileEntity missile, FlightContext ctx, double angleDeg) {
        double theta = Math.toRadians(Mth.clamp(angleDeg, MissileEntity.MIN_ATTACK_ANGLE, MissileEntity.MAX_ATTACK_ANGLE));
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        Vec3 target = ctx.target();
        Vec3 dir = new Vec3(ctx.nx() * cos, -sin, ctx.nz() * cos);
        Vec3 pos = ctx.position();
        double along = pos.subtract(target).dot(dir);
        double carrotParam = Math.min(0.0, along + LOOKAHEAD);
        Vec3 carrot = target.add(dir.scale(carrotParam));
        Vec3 toCarrot = carrot.subtract(pos);
        double len = toCarrot.length();
        double speed = Math.max(missile.getCruiseSpeed(), APPROACH_SPEED);
        return len < 1.0E-4 ? dir.scale(speed) : toCarrot.scale(speed / len);
    }

    @Override
    public String id() {
        return "attack";
    }
}
