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
    // Slowest a drone dive (VerticalDiveStage/guideDive) may fly relative to cruise speed; see turnLimitedSpeed.
    private static final double TERMINAL_SPEED_FRACTION = 0.75;
    // BALANCED: floor the terminal speed at this fraction of cruise when shedding to make the dive angle fit.
    private static final double BALANCED_MIN_FRACTION = 0.85;
    // LOFT: give up on the vertical over-the-top and fall back to SPEED once the lofted path exceeds this
    // multiple of the straight-line distance to the aim.
    private static final double LOFT_MAX_FACTOR = 2.5;
    private static final double LOFT_LOOKAHEAD = 16.0;

    private AttackStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        // Anti-orbit commit safeguard (shared with the drone path): once flown into the aim while descending,
        // fly straight on the current heading rather than re-solving the dive every tick.
        if (missile.isDiveCommitted()) {
            return holdHeading(missile, ctx, APPROACH_SPEED);
        }
        if (reachedAimDescending(missile, ctx)) {
            missile.setDiveCommitted(true);
            return holdHeading(missile, ctx, APPROACH_SPEED);
        }
        return guideByProfile(missile, ctx);
    }

    /**
     * Speed-preserving terminal guidance. Every profile flies a vertical-plane trajectory to the aim at a dive
     * angle that fits the airframe's turn radius, so the missile never has to shed speed for a self-inflicted
     * hard pitch.
     */
    private static Vec3 guideByProfile(MissileEntity missile, FlightContext ctx) {
        double fullSpeed = terminalSpeed(missile);
        double turnRate = missile.getMaxTurnRate();
        double pref = missile.resolveDiveAngle(ctx);
        double h = ctx.position().y - ctx.target().y;
        double d = ctx.horizontalDist();

        if (turnRate <= 1.0E-4 || h <= 1.0E-3) {
            return pursueDiveLine(missile, ctx, pref, fullSpeed);
        }

        AttackProfile profile = missile.getAttackProfile();
        if (profile == AttackProfile.LOFT) {
            Vec3 lofted = guideLoft(missile, ctx, pref, fullSpeed, h, d, fullSpeed / turnRate);
            if (lofted != null) {
                return lofted;
            }
        }
        if (profile == AttackProfile.BALANCED && !feasible(h, d, fullSpeed / turnRate, pref)) {
            double floorSpeed = missile.getCruiseSpeed() * BALANCED_MIN_FRACTION;
            double speed = Mth.clamp(requiredSpeed(h, d, turnRate, pref), floorSpeed, fullSpeed);
            double angle = feasibleDiveAngle(h, d, speed / turnRate, pref);
            return pursueDiveLine(missile, ctx, angle, speed);
        }

        double angle = feasibleDiveAngle(h, d, fullSpeed / turnRate, pref);
        return pursueDiveLine(missile, ctx, angle, fullSpeed);
    }

    /**
     * Steepest dive angle (deg) at or above {@code prefDeg} whose level -> pitch-over -> dive trajectory fits in
     * the horizontal distance {@code d} at turn radius {@code r} (see {@link #feasible}); falls back to near
     * vertical when even that overflies (target closer than the turn radius).
     */
    private static double feasibleDiveAngle(double h, double d, double r, double prefDeg) {
        double pref = Mth.clamp(prefDeg, 1.0, 89.0);
        for (double phi = pref; phi <= 89.0; phi += 4.0) {
            if (feasible(h, d, r, phi)) {
                return phi;
            }
        }
        return 89.0;
    }

    /**
     * @return whether a level run, a pitch-over of radius {@code r} onto {@code phiDeg}, then a straight dive
     * reaches an aim {@code h} below and {@code d} ahead. The pitch-over starts {@code H/tan phi + r*tan(phi/2)}
     * out and drops {@code r(1 - cos phi)} during the arc, both of which must fit.
     */
    private static boolean feasible(double h, double d, double r, double phiDeg) {
        double p = Math.toRadians(phiDeg);
        double tanP = Math.tan(p);
        if (tanP < 1.0E-6) {
            return false;
        }
        if (r * (1.0 - Math.cos(p)) > h) {
            return false;
        }
        return h / tanP + r * Math.tan(p / 2.0) <= d;
    }

    /** Speed at which the preferred angle's pitch-over just fits (turn radius = speed/turnRate); see feasible. */
    private static double requiredSpeed(double h, double d, double turnRate, double prefDeg) {
        double p = Math.toRadians(prefDeg);
        double tanP = Math.tan(p);
        double t2 = Math.tan(p / 2.0);
        if (tanP < 1.0E-6 || t2 < 1.0E-6) {
            return Double.MAX_VALUE;
        }
        double rNeeded = (d - h / tanP) / t2;
        return rNeeded <= 0.0 ? 0.0 : rNeeded * turnRate;
    }

    /**
     * Vertical-plane over-the-top setup: a {@link DubinsPath} in the (horizontal-distance, height) plane from
     * the current pitch to the aim at the preferred dive angle, followed at full speed. Returns {@code null} (so
     * the caller uses the SPEED path) when the preferred angle already fits monotonically or the loft would be
     * longer than {@link #LOFT_MAX_FACTOR} times the straight-line distance.
     */
    private static Vec3 guideLoft(MissileEntity missile, FlightContext ctx, double prefDeg,
                                  double speed, double h, double d, double r) {
        if (feasible(h, d, r, prefDeg)) {
            return null;
        }
        Vec3 vel = missile.getDeltaMovement();
        double towardTarget = vel.x * ctx.nx() + vel.z * ctx.nz();
        double th0 = Math.atan2(vel.y, towardTarget);
        DubinsPath path = DubinsPath.plan(0.0, h, th0, d, 0.0, Math.toRadians(-prefDeg), r);
        if (path == null || path.length() > LOFT_MAX_FACTOR * Math.sqrt(d * d + h * h)) {
            return null;
        }
        double[] pt = path.sample(Math.min(Math.max(LOFT_LOOKAHEAD, r), path.length()));
        Vec3 pos = ctx.position();
        double dx = ctx.nx() * pt[0];
        double dy = (ctx.target().y + pt[1]) - pos.y;
        double dz = ctx.nz() * pt[0];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return len < 1.0E-4 ? vel : new Vec3(dx / len * speed, dy / len * speed, dz / len * speed);
    }

    /**
     * The speed the terminal dive is flown at: the missile spools up from cruise to a fast plunge (so it stays
     * ahead of interceptors and its evasion boost, which doubles this, is punchy). Shared with
     * {@link CruiseStage#handoffRange} so the turn radius used to time the pitch-over matches the dive speed.
     */
    public static double terminalSpeed(MissileEntity missile) {
        return Math.max(missile.getCruiseSpeed(), APPROACH_SPEED);
    }

    /** Pure-pursuit onto the dive line at {@code angleDeg}, flown at exactly {@code speed} (no shedding). */
    static Vec3 pursueDiveLine(MissileEntity missile, FlightContext ctx, double angleDeg, double speed) {
        double theta = Math.toRadians(angleDeg);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        Vec3 target = ctx.target();
        double hx = ctx.nx();
        double hz = ctx.nz();
        Vec3 travel = missile.getAttackTravelDir();
        if (travel != null) {
            hx = travel.x;
            hz = travel.z;
        }
        Vec3 dir = new Vec3(hx * cos, -sin, hz * cos);
        Vec3 pos = ctx.position();
        double along = pos.subtract(target).dot(dir);
        double carrotParam = Math.min(0.0, along + LOOKAHEAD);
        Vec3 carrot = target.add(dir.scale(carrotParam));
        Vec3 toCarrot = carrot.subtract(pos);
        double len = toCarrot.length();
        return len < 1.0E-4 ? dir.scale(speed) : toCarrot.scale(speed / len);
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
        double hx = ctx.nx();
        double hz = ctx.nz();
        Vec3 travel = missile.getAttackTravelDir();
        if (travel != null) {
            hx = travel.x;
            hz = travel.z;
        }
        Vec3 dir = new Vec3(hx * cos, -sin, hz * cos);
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
     * Cap the commanded terminal speed to the fastest the airframe can both turn onto the aim and still shed
     * speed for, so a run that has to swing onto the aim curves in instead of sailing past at full dive speed.
     * {@code turnSpeed} is the speed whose turn radius (from {@link MissileEntity#getMaxTurnRate()}) fits the
     * pure-pursuit arc onto the carrot ({@code radius = len / (2 sin eta)}, eta = angle off the carrot). The
     * returned {@code brakeSpeed} then lets the missile hold more speed while it still has the along-track
     * distance to decelerate to {@code turnSpeed} at its {@link MissileEntity#getDeceleration() deceleration}
     * limit, relaxing to {@code turnSpeed} as the turn goes broadside so braking is anticipatory, never a
     * reactive lag. An already-aligned handoff keeps full speed; an aim more than 90 degrees off the heading
     * drops to the floor ({@link #TERMINAL_SPEED_FRACTION} of cruise speed), the tightest, slowest pass.
     */
    private static double turnLimitedSpeed(MissileEntity missile, Vec3 toCarrot, double len, double speed) {
        double turnRate = missile.getMaxTurnRate();
        Vec3 vel = missile.getDeltaMovement();
        double vel2 = vel.lengthSqr();
        if (turnRate <= 1.0E-4 || turnRate >= Math.PI || vel2 < 1.0E-8 || len < 1.0E-4) {
            return speed;
        }
        double floor = missile.getCruiseSpeed() * TERMINAL_SPEED_FRACTION;
        double cosEta = Mth.clamp(vel.dot(toCarrot) / (Math.sqrt(vel2) * len), -1.0, 1.0);
        if (cosEta <= 0.0) {
            return floor;
        }
        double sinEta = Math.sqrt(1.0 - cosEta * cosEta);
        if (sinEta < 1.0E-4) {
            return speed;
        }
        double turnSpeed = turnRate * (len / (2.0 * sinEta)) * TURN_SPEED_SAFETY;
        double brakeSpeed = Math.sqrt(turnSpeed * turnSpeed + 2.0 * missile.getDeceleration() * len * cosEta);
        return Mth.clamp(brakeSpeed, floor, speed);
    }

    @Override
    public boolean needsPitchoverLead() {
        return true;
    }

    @Override
    public String id() {
        return "attack";
    }
}
