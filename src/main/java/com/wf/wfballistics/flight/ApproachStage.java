package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Cruise-phase stage for a directional strike: get the missile onto the commanded attack bearing well before it
 * reaches the target, then run straight down that bearing into the terminal dive. The attack line is the ray
 * from the target outward along the approach side; the missile curves onto that line at a {@link #joinDistance}
 * that is a fraction of the way in (so the reorientation happens far from the target's defended vicinity, not
 * circling on top of it), using a {@link DubinsPath} at its minimum turn radius to pick the shortest
 * turn-feasible way there. Once aligned and on the line it flies straight to the target and hands off to the
 * terminal dive at the normal cruise range. With no attack bearing set the stage is transparent - it defers
 * wholly to {@link CruiseStage}. The curved run is longer than a straight one; {@link #approachHorizontalDistance}
 * is the shared geometry the fuel/ETA estimator uses to charge for it.
 */
public final class ApproachStage implements FlightStage {

    public static final ApproachStage INSTANCE = new ApproachStage();

    // Where on the attack line to aim the join: this fraction of the straight-line distance to the target,
    // floored so the pivot never collapses onto the target and capped per-missile (missile.getApproachJoinCap).
    private static final double JOIN_FRACTION = 0.45;
    private static final double MIN_JOIN = 64.0;
    // Turn radius safety: fly the Dubins arcs a touch wider than the theoretical minimum so the per-tick
    // heading limiter can actually track them.
    private static final double RADIUS_SAFETY = 1.15;
    // Carrot along the fresh path each tick: far enough to steer smoothly, floored so it never collapses.
    private static final double LOOKAHEAD_FACTOR = 2.0;
    private static final double MIN_LOOKAHEAD = 16.0;
    // Considered "joined" (switch from curving to a straight run-in) within this lateral offset of the line.
    private static final double JOIN_TOLERANCE = 12.0;
    private static final double ALIGN_COS = 0.906; // cos(25 degrees)

    private ApproachStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        Vec3 approach = missile.getAttackApproachDir();
        if (approach == null) {
            return CruiseStage.INSTANCE.guide(missile, ctx);
        }

        double maxSpeed = missile.getCruiseSpeed();
        Vec3 pos = ctx.position();
        Vec3 target = ctx.target();
        double vy = CruiseStage.verticalVelocity(missile, ctx);

        double r = turnRadius(missile);
        double dirX;
        double dirZ;
        if (onBearing(missile, ctx, approach, r)) {
            double tx = target.x - pos.x;
            double tz = target.z - pos.z;
            double tl = Math.sqrt(tx * tx + tz * tz);
            dirX = tl > 1.0E-4 ? tx / tl : -approach.x;
            dirZ = tl > 1.0E-4 ? tz / tl : -approach.z;
        } else {
            double join = joinDistance(ctx.horizontalDist(), missile.getApproachJoinCap());
            double jx = target.x + approach.x * join;
            double jz = target.z + approach.z * join;
            double goalHeading = Math.atan2(-approach.z, -approach.x);

            Vec3 vel = missile.getDeltaMovement();
            double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            double heading = vh > 1.0E-4 ? Math.atan2(vel.z, vel.x) : Math.atan2(jz - pos.z, jx - pos.x);

            double steerX;
            double steerZ;
            DubinsPath path = DubinsPath.plan(pos.x, pos.z, heading, jx, jz, goalHeading, r);
            if (path != null) {
                double look = Math.min(Math.max(LOOKAHEAD_FACTOR * r, MIN_LOOKAHEAD), path.length());
                double[] pt = path.sample(look);
                steerX = pt[0];
                steerZ = pt[1];
            } else {
                steerX = jx;
                steerZ = jz;
            }

            double hx = steerX - pos.x;
            double hz = steerZ - pos.z;
            double hlen = Math.sqrt(hx * hx + hz * hz);
            dirX = hlen > 1.0E-4 ? hx / hlen : -approach.x;
            dirZ = hlen > 1.0E-4 ? hz / hlen : -approach.z;
        }

        return new Vec3(dirX * maxSpeed, vy, dirZ * maxSpeed);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        Vec3 approach = missile.getAttackApproachDir();
        if (approach == null) {
            return CruiseStage.INSTANCE.next(missile, ctx);
        }
        if (ctx.horizontalDist() <= CruiseStage.BRAKING_RANGE) {
            return MissileEntity.Phase.ATTACK; // failsafe: never orbit the target itself
        }
        Vec3 vel = missile.getDeltaMovement();
        double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean aligned = vh > 1.0E-4 && (vel.x * -approach.x + vel.z * -approach.z) / vh >= ALIGN_COS;
        return aligned ? CruiseStage.INSTANCE.next(missile, ctx) : null;
    }

    private static boolean onBearing(MissileEntity missile, FlightContext ctx, Vec3 approach, double r) {
        Vec3 pos = ctx.position();
        Vec3 target = ctx.target();
        double wx = pos.x - target.x;
        double wz = pos.z - target.z;
        double along = wx * approach.x + wz * approach.z;
        if (along <= 0.0) {
            return false;
        }
        double perpX = wx - along * approach.x;
        double perpZ = wz - along * approach.z;
        double lateral = Math.sqrt(perpX * perpX + perpZ * perpZ);
        if (lateral > Math.max(JOIN_TOLERANCE, 0.2 * r)) {
            return false;
        }
        Vec3 vel = missile.getDeltaMovement();
        double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        return vh > 1.0E-4 && (vel.x * -approach.x + vel.z * -approach.z) / vh >= ALIGN_COS;
    }

    /**
     * Distance from the target, along the attack line, at which the missile aims to be established on the
     * commanded bearing: a fraction of the straight-line range (so it scales with distance), floored clear of the
     * target and capped at {@code cap} (per-missile ceiling, see {@link MissileEntity#getApproachJoinCap()}).
     */
    public static double joinDistance(double horizDistToTarget, double cap) {
        return Mth.clamp(horizDistToTarget * JOIN_FRACTION, MIN_JOIN, Math.max(MIN_JOIN, cap));
    }

    /**
     * Horizontal ground distance the missile actually covers to strike {@code target} from the {@code approachDir}
     * side (via the join point on the attack line), versus the straight-line distance when {@code approachDir} is
     * null. Shared with {@link ArrivalEstimator} so the fuel/ETA charge matches the curved run this stage flies;
     * {@code cap} is the per-missile join ceiling.
     */
    public static double approachHorizontalDistance(Vec3 from, Vec3 target, @Nullable Vec3 approachDir, double cap) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double direct = Math.sqrt(dx * dx + dz * dz);
        if (approachDir == null) {
            return direct;
        }
        double an = Math.sqrt(approachDir.x * approachDir.x + approachDir.z * approachDir.z);
        if (an < 1.0E-4) {
            return direct;
        }
        double ax = approachDir.x / an;
        double az = approachDir.z / an;
        double join = joinDistance(direct, cap);
        double jx = target.x + ax * join;
        double jz = target.z + az * join;
        double fx = jx - from.x;
        double fz = jz - from.z;
        return Math.sqrt(fx * fx + fz * fz) + join;
    }

    private static double turnRadius(MissileEntity missile) {
        double turnRate = missile.getMaxTurnRate();
        if (turnRate < 1.0E-4) {
            return 1.0E4;
        }
        return missile.getCruiseSpeed() / turnRate * RADIUS_SAFETY;
    }

    @Override
    public String id() {
        return "approach";
    }
}
