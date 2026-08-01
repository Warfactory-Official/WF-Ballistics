package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
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
    // Fraction of the theoretical turn-limited speed the approach is actually flown at, so the arc curves inside
    // the aim rather than just grazing it. Below 1 the missile turns a little tighter than strictly required.
    private static final double TURN_SPEED_SAFETY = 0.7;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return guideDive(missile, ctx, APPROACH_SPEED);
    }

    /**
     * Shared terminal-dive guidance at the given approach speed (also used by {@link VerticalDiveStage}).
     * Safeguard: once the run has flown into its aim point while descending, stop re-resolving the dive angle
     * and just fly straight on the current heading until it hits something. Re-aiming every tick is what lets a
     * missile that reaches an aim point it can't sit on — e.g. one left hanging in the air, or a low-agility
     * drone that can't quite pull onto its target — orbit it forever; committing to the current (downward)
     * heading drives it into whatever is below.
     */
    static Vec3 guideDive(MissileEntity missile, FlightContext ctx, double approachSpeed) {
        if (missile.isDiveCommitted()) {
            return holdHeading(missile, ctx, approachSpeed);
        }
        if (reachedAimDescending(missile, ctx)) {
            missile.setDiveCommitted(true);
            return holdHeading(missile, ctx, approachSpeed);
        }
        return guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), approachSpeed);
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
    private static Vec3 holdHeading(MissileEntity missile, FlightContext ctx, double approachSpeed) {
        Vec3 v = missile.getDeltaMovement();
        double len = v.length();
        double speed = Math.max(missile.getCruiseSpeed(), approachSpeed);
        return len > 1.0E-4 ? v.scale(speed / len)
                : guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), approachSpeed);
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
        double speed = turnLimitedSpeed(missile, toCarrot, len, Math.max(missile.getCruiseSpeed(), minSpeed));
        return len < 1.0E-4 ? dir.scale(speed) : toCarrot.scale(speed / len);
    }

    /**
     * Cap the commanded terminal speed to what the airframe can actually turn: a run that has to swing hard onto
     * the aim (a loitering drone pitching down out of its orbit) is flown slow enough that its turn radius
     * ({@code speed / maxTurnRate}) fits the distance to the aim, so it curves onto the target instead of sailing
     * past at full dive speed; once it lines up the required turn vanishes and it accelerates into the plunge.
     * Missiles that reach the terminal already pointed at the target (a normal cruise handoff) see no change.
     */
    private static double turnLimitedSpeed(MissileEntity missile, Vec3 toCarrot, double len, double speed) {
        double turnRate = missile.getMaxTurnRate();
        Vec3 vel = missile.getDeltaMovement();
        if (turnRate <= 1.0E-4 || turnRate >= Math.PI || vel.lengthSqr() < 1.0E-8 || len < 1.0E-4) {
            return speed;
        }
        double cosPhi = Mth.clamp(vel.normalize().dot(toCarrot.scale(1.0 / len)), -1.0, 1.0);
        double chordFactor = 2.0 * Math.sin(Math.acos(cosPhi) / 2.0);
        if (chordFactor < 1.0E-4) {
            return speed; // already aligned: no turn needed, keep full speed
        }
        double turnRadius = len / chordFactor;
        double maxTurnSpeed = turnRate * turnRadius * TURN_SPEED_SAFETY;
        return Mth.clamp(maxTurnSpeed, missile.getCruiseSpeed(), speed);
    }

    @Override
    public String id() {
        return "attack";
    }
}
