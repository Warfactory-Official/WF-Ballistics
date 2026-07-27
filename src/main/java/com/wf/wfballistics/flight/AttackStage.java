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
    // Distance (blocks, 3-D) to the aim point at which the run commits to flying straight (see guide).
    private static final double COMMIT_RADIUS = 2.0;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        // Safeguard: once the run has flown into its aim point while descending, stop re-resolving the dive
        // angle and just fly straight on the current heading until it hits something. Re-aiming every tick is
        // what lets a missile that reaches an aim point it can't sit on — e.g. one left hanging in the air —
        // orbit it forever; committing to the current (downward) heading drives it into whatever is below.
        if (missile.isDiveCommitted()) {
            return holdHeading(missile, ctx);
        }
        if (reachedAimDescending(missile, ctx)) {
            missile.setDiveCommitted(true);
            return holdHeading(missile, ctx);
        }
        return guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), APPROACH_SPEED);
    }

    /** @return true once the missile is within {@link #COMMIT_RADIUS} of its aim point on a descending pass. */
    private static boolean reachedAimDescending(MissileEntity missile, FlightContext ctx) {
        if (missile.getDeltaMovement().y > 0.0) {
            return false; // only commit while descending, so "straight ahead" carries it into the ground
        }
        double dy = ctx.position().y - ctx.target().y;
        double d2 = ctx.horizontalDist() * ctx.horizontalDist() + dy * dy;
        return d2 <= COMMIT_RADIUS * COMMIT_RADIUS;
    }

    /** Fly straight on the current heading at the approach speed; falls back to a fresh dive if stationary. */
    private static Vec3 holdHeading(MissileEntity missile, FlightContext ctx) {
        Vec3 v = missile.getDeltaMovement();
        double len = v.length();
        double speed = Math.max(missile.getCruiseSpeed(), APPROACH_SPEED);
        return len > 1.0E-4 ? v.scale(speed / len)
                : guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), APPROACH_SPEED);
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
