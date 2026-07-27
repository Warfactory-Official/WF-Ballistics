package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Terminal dive: a pure-pursuit approach onto the line through the target at the missile's resolved dive angle
 * (an explicit {@link MissileEntity#getAttackAngle() preferred angle}, or one raycast-picked within the
 * missile's dive-angle range - see {@link MissileEntity#resolveDiveAngle}). Speed is held constant along the
 * approach, so the missile carves a fast arc onto the line instead of braking to a hover to pivot.
 */
public final class AttackStage implements FlightStage {

    public static final AttackStage INSTANCE = new AttackStage();

    // Carrot distance (blocks) ahead along the approach line for the angled pure-pursuit run.
    private static final double LOOKAHEAD = 12.0;
    // Commanded closing speed along the approach; the missile still spools toward it under thrust.
    private static final double APPROACH_SPEED = 14.0;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), APPROACH_SPEED);
    }

    static Vec3 guideAngled(MissileEntity missile, FlightContext ctx, double angleDeg, double minSpeed) {
        double theta = Math.toRadians(angleDeg);
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
        double speed = Math.max(missile.getCruiseSpeed(), minSpeed);
        return len < 1.0E-4 ? dir.scale(speed) : toCarrot.scale(speed / len);
    }

    @Override
    public String id() {
        return "attack";
    }
}
