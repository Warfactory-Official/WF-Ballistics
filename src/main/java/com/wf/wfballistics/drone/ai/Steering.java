package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.flight.Airframe;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure guidance math shared by the drone state handlers: given where a drone is and where it wants to be,
 * what velocity should it be asking for. A multirotor is steered as a horizontal velocity plus an independent
 * climb rate rather than a nose vector, which is why none of the missile guidance code is reused here.
 *
 * <p>This layer only ever expresses <em>intent</em>. Turning a wanted velocity into what the airframe can
 * actually do about it (the lean, the throttle, the lag) belongs to {@code Multirotor}, and keeping the two
 * apart is what lets a handler ask for something impossible without the drone doing something impossible.
 */
public final class Steering {

    /**
     * Radians per tick the airframe may yaw.
     */
    public static final float MAX_YAW_RATE = 0.25f;

    private Steering() {
    }

    /**
     * Level-flight steering: run at {@code speed} toward the target's horizontal position while closing on
     * {@code desiredY} no faster than {@code climbRate}. Slows down over the last few blocks so the drone
     * settles onto a waypoint instead of overshooting and oscillating.
     */
    public static Vec3 cruise(Vec3 from, Vec3 target, double desiredY, double speed, double climbRate) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(speed, horiz);
        if (horiz > 1.0E-4 && horiz < speed * 8.0) {
            step = Math.min(step, Math.max(speed * 0.15, horiz * 0.25));
        }
        double vx = horiz > 1.0E-4 ? dx / horiz * step : 0.0;
        double vz = horiz > 1.0E-4 ? dz / horiz * step : 0.0;
        return new Vec3(vx, verticalTo(from.y, desiredY, climbRate), vz);
    }

    /**
     * Attack-run steering: hold {@code speed} flat out toward the target with none of {@link #cruise}'s
     * arrival slowdown. A bombing run has to be <em>at</em> release speed when it pickles, so easing off on
     * the approach is exactly wrong here.
     */
    public static Vec3 dash(Vec3 from, Vec3 target, double desiredY, double speed, double climbRate) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4) {
            return new Vec3(0.0, verticalTo(from.y, desiredY, climbRate), 0.0);
        }
        return new Vec3(dx / horiz * speed, verticalTo(from.y, desiredY, climbRate), dz / horiz * speed);
    }

    /**
     * How far a payload released now would travel before it hits {@code targetY}, given the carrier's own
     * motion. This is what lets a drone drop early and let the fall do the rest, instead of flying over the
     * aim point and dropping straight down.
     *
     * @param height     drop height above the aim point
     * @param verticalUp the carrier's current vertical speed (positive climbing)
     * @param horizontal the carrier's horizontal speed
     * @param gravity    the payload's downward acceleration per tick
     * @return the horizontal lead distance, in blocks
     */
    public static double ballisticLead(double height, double verticalUp, double horizontal, double gravity) {
        if (height <= 0.0 || gravity <= 0.0) {
            return 0.0;
        }
        double u = -verticalUp;
        double t = (-u + Math.sqrt(u * u + 2.0 * gravity * height)) / gravity;
        return Math.max(0.0, horizontal * t);
    }

    /**
     * How hard a hovering drone pulls itself back to the point it is holding, as a fraction of the offset per
     * tick.
     */
    public static final double HOLD_GAIN = 0.35;

    /**
     * Hold a position: come to a stop over {@code target} and stay there while closing on {@code desiredY}.
     *
     * <p>This is a <em>position</em> controller, and that is the whole difference from {@link #cruise}.
     * Cruise commands a speed toward a waypoint and keeps a floor under it so a drone in transit never
     * crawls, which is right for getting somewhere and exactly wrong for stopping, because the floor means
     * the drone is still being told to move when it is already there. It drifts across the point, turns
     * round, and mills about. Here the commanded speed is proportional to the offset and goes to zero with
     * it, so the drone settles and holds, which is what lowering a crate onto a spot, or coming straight
     * down onto one, actually requires.
     *
     * <p>The approach speed is capped by what the airframe could still stop from: {@code v = √(2·a·d)},
     * the same arithmetic as a car's stopping distance. Without that a proportional gain is no help at all:
     * a drone one block out is still under its speed cap, so it is told to keep going at full approach speed
     * right up to the point, and physics does the rest.
     *
     * @param maxSpeed cap on the approach; pass 0 to command a dead stop
     * @param frame    the airframe, for how hard it can brake
     */
    public static Vec3 hold(Vec3 from, Vec3 target, double desiredY, double maxSpeed, double climbRate,
                            Airframe frame) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = verticalTo(from.y, desiredY, climbRate);
        if (horizontal < 1.0E-4) {
            return new Vec3(0.0, vertical, 0.0);
        }
        double want = Math.min(maxSpeed, horizontal * HOLD_GAIN);
        want = Math.min(want, Math.sqrt(2.0 * frame.brakingAccel() * horizontal));
        return new Vec3(dx / horizontal * want, vertical, dz / horizontal * want);
    }

    /**
     * How hard a formation follower pulls itself back onto its slot, as a fraction of the offset per tick.
     */
    public static final double STATION_GAIN = 0.35;

    /**
     * Station-keeping on a <em>moving</em> slot: fly what the slot is flying, and correct whatever offset is
     * left on top of that. This is what a follower holds formation with, and it is three separate ideas.
     *
     * <p><b>Feed-forward first, correction second.</b> A position controller alone cannot hold station on
     * something that is moving: the only way it commands any speed at all is by being out of position, so a
     * follower settles at exactly the offset whose correction happens to equal the leader's speed and then
     * trails there for the whole flight: further back the faster the squad flies, and further still every
     * time the leader accelerates. Starting from the slot's own velocity leaves the offset term with nothing
     * to do but cover the error, so a follower that is in its slot is told to fly the leader's velocity and
     * simply stays there.
     *
     * <p><b>The correction is limited by what the drone could brake from</b>, {@code v = √(2·a·d)}: the
     * same stopping-distance arithmetic as {@link #hold}, and for the same reason, except that here it was
     * the difference between a formation and a shambles. A bare proportional gain reaches the overspeed cap
     * about three blocks out, while the airframe needs some thirty-five blocks to shed that speed again: the
     * follower arrives at its slot flat out, sails a long way past, turns round and does it again, and never
     * at any point in the flight is it where it is supposed to be. That is a limit cycle, not a slow
     * convergence: it does not settle however long the leg is. Capped at the braking profile the approach
     * is one the drone can actually finish. The overspeed margin caps it again, because authority the
     * airframe cannot use within its own response time only buys overshoot: without that a slow-moving
     * squad hunts a couple of blocks either side of station indefinitely.
     *
     * <p><b>The vertical axis gets its own budget.</b> Scaling a single three-axis error down to one speed
     * limit makes climbing and catching up compete for it, and horizontal offsets are much the larger of the
     * two: a follower thirty blocks back and five low spends essentially all of its allowance on the chase
     * and climbs at a fraction of the rate the leader is climbing at, so it keeps sinking relative to a
     * squad it cannot rejoin until it has stopped sinking. Split, each axis closes its own error at its own
     * rate. It is also the honest model: a multirotor is limited horizontally by how far it can lean and
     * vertically by what the rotors have left over, and those were never one budget: the same reason this
     * whole layer steers in horizontal velocity plus climb rate. No braking limit is needed on the vertical:
     * the rotors answer within a tick, where the lean takes several, so a climb the drone cannot arrest
     * inside its own correction band is not reachable in the first place.
     *
     * @param slotVelocity how fast the slot itself is travelling: the leader's velocity, since the
     *                     formation is rigid about it
     * @param speed        the follower's cruise speed
     * @param maxOverspeed how far above that it may push to close the gap
     * @param climbRate    its nominal climb rate, which is also its catch-up authority on the vertical
     * @param frame        the airframe, for how hard it brakes and how fast it may climb and descend
     */
    public static Vec3 station(Vec3 from, Vec3 slot, Vec3 slotVelocity, double speed, double maxOverspeed,
                               double climbRate, Airframe frame) {
        double dx = slot.x - from.x;
        double dz = slot.z - from.z;
        double offset = Math.sqrt(dx * dx + dz * dz);
        double vx = slotVelocity.x;
        double vz = slotVelocity.z;
        if (offset > 1.0E-4) {
            double close = Math.min(offset * STATION_GAIN, Math.sqrt(2.0 * frame.brakingAccel() * offset));
            close = Math.min(close, speed * Math.max(0.0, maxOverspeed - 1.0));
            vx += dx / offset * close;
            vz += dz / offset * close;
        }
        double slotSpeed = Math.sqrt(slotVelocity.x * slotVelocity.x + slotVelocity.z * slotVelocity.z);
        double cap = Math.max(speed, slotSpeed) * maxOverspeed;
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal > cap) {
            vx *= cap / horizontal;
            vz *= cap / horizontal;
        }
        double vy = slotVelocity.y + Mth.clamp((slot.y - from.y) * STATION_GAIN, -climbRate, climbRate);
        return new Vec3(vx, Mth.clamp(vy, -frame.maxDescentRate(), frame.maxClimbRate()), vz);
    }

    /**
     * How quickly a drone closes an altitude error, as a fraction of it per tick. Full rate is reached about
     * {@code climbRate / VERTICAL_GAIN} blocks out, and inside that it eases in.
     */
    public static final double VERTICAL_GAIN = 0.25;

    /**
     * @return the vertical velocity that closes on {@code desiredY} without exceeding {@code climbRate}.
     *
     * <p>Proportional, not bang-bang. Clamping the raw error would ask for the full climb rate for any error
     * over one block and the full descent rate for any error under it, which reads fine when the velocity is
     * simply assigned but is a disaster once a real airframe has to produce it: chasing a terrain-following
     * route, the target altitude moves a little every tick, and a drone answering each of those with full
     * thrust or none spends the whole flight porpoising with its rotors flickering between stopped and
     * screaming. Easing in costs nothing on a long climb, the error out there is large enough to saturate
     * anyway, and buys a stable hover.
     */
    public static double verticalTo(double currentY, double desiredY, double climbRate) {
        return Mth.clamp((desiredY - currentY) * VERTICAL_GAIN, -climbRate, climbRate);
    }

    /**
     * Fraction of its cruise speed a drone must be making horizontally before its velocity is taken as
     * evidence of which way it is going.
     *
     * <p>Everything slower than this is drift, not travel: rounding, a squadmate shoving past, the terrain
     * clamp nudging it off a slope, a hold controller trickling it onto a drop point. Normalising a vector
     * that small gives a direction drawn out of a hat and a different one every tick. The old floor was a
     * thousandth of a block a tick, which is to say no floor at all: a drone climbing vertically out of a
     * pad with three hundredths of a block of sideways drift would swing its heading a quarter radian a tick
     * chasing it.
     *
     * <p>It matters far more than a spinning model, because a squad's whole formation is built on the
     * leader's heading. Six blocks of nothing at the leader becomes metres of swaying at a slot fifteen
     * blocks out on the end of the frame.
     */
    public static final double HEADING_MIN_SPEED = 0.25;

    /**
     * @return {@code current} yaw turned toward the direction of travel, rate limited. Holds the current
     * heading when the drone is not meaningfully travelling, so a hovering or climbing drone doesn't spin.
     *
     * @param minSpeed horizontal speed below which the velocity is treated as drift and the heading held.
     *                 Normally {@link #HEADING_MIN_SPEED} of the drone's cruise speed
     */
    public static float faceTravel(Vec3 velocity, float current, double minSpeed) {
        double horiz = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horiz < Math.max(1.0E-3, minSpeed)) {
            return current;
        }
        float target = (float) Mth.atan2(velocity.x, velocity.z);
        float delta = wrap(target - current);
        return wrap(current + Mth.clamp(delta, -MAX_YAW_RATE, MAX_YAW_RATE));
    }

    /**
     * @return {@code current} yaw turned toward a place rather than toward a velocity, rate limited the same
     * way {@link #faceTravel} is.
     *
     * <p>For the parts of a flight that are going somewhere without travelling yet: a drone climbing out, and
     * a flight holding over the pad waiting for the rest of itself. Both have a horizontal velocity made
     * almost entirely of squadmates shoving past, so a heading derived from it points wherever the last shove
     * came from, and because the formation frame is built on the leader's heading, the flight then assembles
     * facing a direction nobody chose. Measured on a staggered launch, a leader that would depart on a
     * bearing of 1.57 spent its whole climb and form-up facing 0.87, and the entire formation swung through
     * that difference the moment it set off, throwing a slot twenty-four blocks out by fifty-eight.
     *
     * <p>Pointing at the destination instead costs nothing, the drone is going there, and means the wedge
     * is built on the outbound heading from the start, so departure is a straight acceleration rather than a
     * turn the whole formation has to survive.
     */
    public static float faceToward(Vec3 from, Vec3 target, float current) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        if (dx * dx + dz * dz < 1.0E-6) {
            return current;
        }
        float want = (float) Mth.atan2(dx, dz);
        return wrap(current + Mth.clamp(wrap(want - current), -MAX_YAW_RATE, MAX_YAW_RATE));
    }

    /**
     * Wraps radians into [-PI, PI] so a turn across the seam stays short.
     */
    public static float wrap(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        angle %= twoPi;
        if (angle >= (float) Math.PI) {
            angle -= twoPi;
        } else if (angle < (float) -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }
}
