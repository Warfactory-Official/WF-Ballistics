package com.wf.wfballistics.drone.ai.state;

/**
 * Shared thresholds for the stock drone state handlers.
 */
public final class Tuning {

    /**
     * Horizontal distance at which a waypoint counts as reached.
     */
    public static final double ARRIVAL_RADIUS = 3.0;
    /**
     * Height above the ground the crate is released from.
     */
    public static final double DROP_HEIGHT = 3.0;
    /**
     * How far above the block it is working on a drone hovers.
     *
     * <p>Higher than {@link #DROP_HEIGHT} by an airframe's worth, because a drone placing a block is going to
     * be sitting directly on top of where a new block appears: at three blocks the hull and the block would
     * be arguing. Four leaves the drone's belly clear of a full cube placed underneath it and still inside
     * comfortable reach.
     */
    public static final double WORK_HEIGHT = 4.0;
    /**
     * How far a drone may be from a block and still work it, measured from the hover point rather than the
     * ground. Generous enough to cover a drone drifting in a breeze of squadmate separation, tight enough
     * that it cannot reach into the next order along.
     */
    public static final double WORK_REACH = 6.0;
    /**
     * How close to directly over the destination a drone must be to let a crate go. Tighter than
     * {@link #ARRIVAL_RADIUS}, which only decides when to stop cruising and start settling: a drone holding
     * position can put itself far more precisely than one still flying a route.
     */
    public static final double RELEASE_RADIUS = 1.5;
    /**
     * Floor on how far from the destination a drone stops cruising and starts settling onto it. The real
     * figure is whichever is larger of this and the airframe's own stopping distance at the ordered cruise
     * speed: this only matters for a drone flying slowly enough that it could stop almost anywhere.
     */
    public static final double DELIVER_ENTRY_RADIUS = 16.0;
    /**
     * How close counts as having reached a staging waypoint. Generous: a dogleg corner is a turning point,
     * not a target, and there is nothing to be gained by flying to the exact spot.
     */
    public static final double LEG_ARRIVAL_RADIUS = 20.0;
    /**
     * How slowly it must be drifting before it lets go, blocks/tick. Releasing while still sliding sideways
     * throws the crate rather than placing it.
     */
    public static final double RELEASE_DRIFT = 0.08;
    /**
     * How long it will keep trying to settle before delivering anyway. A drone that cannot get steady (being
     * shoved by a squadmate, or fighting terrain) should still complete its mission rather than hover over
     * the drop until its battery runs out.
     */
    public static final int SETTLE_TIMEOUT = 100;
    /**
     * Height above the ground at which a descent counts as touchdown.
     */
    public static final double LAND_CONTACT = 0.35;
    /**
     * Fraction of the cruise altitude that ends the climb-out.
     */
    public static final double CLIMB_COMPLETE = 0.95;
    /**
     * How near its station a drone must be to count as formed up, as a fraction of the squad's spacing.
     *
     * <p>A <em>position</em> test and not a speed one, which took a measurement to get right. Asking that
     * everybody be nearly stationary reads like the stricter, safer condition and is in fact one a tight
     * formation can never satisfy: station-keeping pulls a drone in, {@link #SEPARATION_RADIUS} pushes it
     * back out, and at close spacing the two settle into a limit cycle rather than a fixed point. Measured,
     * eight drones at six blocks' spacing held a permanent 0.24–0.42 blocks/tick of jitter that never decayed,
     * so the flight sat over the pad until the timeout, every time, while being in perfectly good shape the
     * whole while.
     *
     * <p>Half the spacing is the honest bar: it means every drone is nearer its own place than anybody
     * else's, which is the entire question. Scaled off the spacing so it means the same thing whether the
     * flight was ordered tight or loose, with a floor at the width of the hull, below which the drones are
     * touching anyway.
     */
    public static final double MUSTER_IN_PLACE = 0.5;
    public static final double MUSTER_IN_PLACE_FLOOR = 3.0;
    /**
     * How long a flight will wait for itself before going anyway, in ticks.
     *
     * <p>Generous, a sixteen-drone launch at the default interval takes most of this just to get everyone
     * off the pad, but bounded, because the alternative is that one drone shot down on the pad, or a launch
     * queue lost to a reload, holds the entire flight in a hover until every battery is flat.
     */
    public static final int MUSTER_TIMEOUT = 1200;
    /**
     * Sink rate of an unpowered drone. Slower than free fall: the rotors still windmill.
     */
    public static final double SINK_RATE = 0.35;
    /**
     * How much of its horizontal momentum an unpowered drone keeps each tick.
     */
    public static final double SINK_DRAG = 0.92;
    /**
     * How far above cruise speed a follower may push to regain its slot. Mirrors the missile formation cap.
     * Horizontal only: the vertical has its own allowance, for the reasons in {@code Steering#station}.
     */
    public static final double FORMATION_MAX_OVERSPEED = 1.6;
    /**
     * How close two squadmates may get before they actively push apart, and how hard.
     *
     * <p>Measured against the hull rather than against the formation, because the formation's spacing is a
     * per-mission setting and a radius pegged to it would mean a squad ordered to fly loose also started
     * avoiding at long range for no reason. What this is really about is the three blocks of airframe, and it
     * sits just wide enough of that to be a warning rather than a collision.
     *
     * <p>It exists for the moments the formation cannot help: climbing out from a shared launch point
     * before the wedge has opened up, or an attack run, where the drones break formation entirely and fly
     * their own courses.
     *
     * <p><b>It is a ceiling, not the figure.</b> This used to claim it sat under {@code Formation.MIN_SPACING}
     * so that even the tightest formation orderable put its drones in slots they were not fighting to hold.
     * The claim was simply false — {@code MIN_SPACING} is 4.0 and this is 4.5 — and the consequence was a
     * flight that never formed up: sixteen drones ordered into a grid at spacing 4 sit in slots the avoidance
     * rule is permanently shoving them out of, so {@code MusterHandler} never sees them on station and every
     * launch burned the whole {@link #MUSTER_TIMEOUT} before setting off. See
     * {@link Cruising#separationRadius}, which now holds the invariant instead of asserting it.
     */
    public static final double SEPARATION_RADIUS = 4.5;
    /**
     * The most of a squad's ordered spacing the avoidance rule may claim, when that is the tighter of the two.
     *
     * <p>Under 1, so a pair sitting exactly on neighbouring slots is not being pushed off them: the rule only
     * has an opinion about drones that are closer together than the formation itself put them. That is the
     * glyphid rule's {@code SPACING} in the same role — a swarm is allowed to overlap a little precisely so
     * that a packed one does not set into a lattice it spends the whole tick fighting.
     *
     * <p>At the 4-block floor this gives 3.2, which is still clear of the 3-block hull, so the tightest
     * formation anyone can order is one the drones can actually hold and still keeps them from touching.
     */
    public static final double SEPARATION_SPACING_SHARE = 0.8;
    public static final double SEPARATION_STRENGTH = 0.6;
    /**
     * Cap on the total push, so a drone hemmed in on all sides is nudged clear rather than fired out of the
     * squad.
     */
    public static final double SEPARATION_MAX = 0.9;
    /**
     * How much wider than the flight formation a strike's aim points are spread.
     *
     * <p>Drones on an attack run leave formation and each fly their own release solution, and if they all
     * solve for the same point they converge on it: arriving together, at the same height, over the same
     * spot, each dropping a live warhead through the others. Fanning the aim points out in the shape of the
     * formation keeps the whole run separated, all the way through the release, and lands the pattern where
     * the pattern was aimed.
     *
     * <p>A multiplier, so the pattern is drawn from whatever spacing the flight is actually holding. It came
     * down when the default spacing went up: what matters is the ground between two bomb bursts, and that is
     * this times the spacing, so leaving it alone would have quietly doubled every strike's footprint as a
     * side effect of loosening the formation.
     */
    public static final double PAYLOAD_AIM_SPREAD = 1.5;
    /**
     * Distance from the aim point at which a payload drone breaks off cruise and starts its attack run. Long
     * enough to build up to release speed and still have the lead distance in front of it.
     */
    public static final double PAYLOAD_RUN_IN = 140.0;
    /**
     * Fraction of the ordered release speed the drone must have reached before it will pickle, so it doesn't
     * drop while still accelerating.
     */
    public static final double RELEASE_SPEED_TOLERANCE = 0.9;
    /**
     * Downward acceleration of a released payload, matching {@code BombletEntity}. Used to solve the release
     * point; keep the two in step.
     */
    public static final double PAYLOAD_GRAVITY = 0.05;
    /**
     * How far above its cruise altitude a drone climbs after pickling before it counts as clear of the
     * weapon and rejoins the mission.
     *
     * <p>A bomb leaves the rack with the aircraft's own velocity and only pulls away from it at
     * {@link #PAYLOAD_GRAVITY}, so for the first second of its fall it is barely a body-length underneath:
     * a fraction of a block on the tick it is released. Anything that puts the drone into a descent in that
     * window flies it into its own weapon. Climbing out is what a real strike does with the same problem,
     * and unlike simply holding altitude it is a manoeuvre you can see from the ground.
     */
    public static final double BREAK_OFF_CLIMB = 12.0;
    /**
     * Yaw spin of a shot-down drone, radians/tick.
     */
    public static final float DOWNED_SPIN = 0.45f;
    /**
     * How fast a wreck tips over as it falls, radians/tick, and how far. With no rotors left there is nothing
     * holding it level, so it ends up on its side well before it lands.
     */
    public static final double DOWNED_TUMBLE = 0.05;
    public static final double DOWNED_TUMBLE_LIMIT = Math.toRadians(75.0);
    /**
     * How fast a drone that has merely run out of power returns to level, per tick. Unlike a wreck it still
     * has its rotors: they windmill in the airflow, which is enough to keep it roughly the right way up.
     */
    public static final double UNPOWERED_LEVELLING = 0.05;
    /**
     * Downward acceleration while spinning out, and how much horizontal momentum a falling wreck keeps.
     */
    public static final double DOWNED_GRAVITY = 0.06;
    public static final double DOWNED_DRAG = 0.96;
    public static final double DOWNED_TERMINAL = -1.6;

    private Tuning() {
    }
}
