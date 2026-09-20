package com.wf.wflib.drone.ai.state;

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
    /** How far above the block it is working on a drone hovers. */
    public static final double WORK_HEIGHT = 4.0;
    /**
     * How far a drone may be from a block and still work it, measured from the hover point rather than the ground.
     */
    public static final double WORK_REACH = 6.0;
    /** How close to directly over the destination a drone must be to let a crate go. */
    public static final double RELEASE_RADIUS = 1.5;
    /** Floor on how far from the destination a drone stops cruising and starts settling onto it. */
    public static final double DELIVER_ENTRY_RADIUS = 16.0;
    /** How close counts as having reached a staging waypoint. */
    public static final double LEG_ARRIVAL_RADIUS = 20.0;
    /** How slowly it must be drifting before it lets go, blocks/tick. */
    public static final double RELEASE_DRIFT = 0.08;
    /** How long it will keep trying to settle before delivering anyway. */
    public static final int SETTLE_TIMEOUT = 100;
    /**
     * Height above the ground at which a descent counts as touchdown.
     */
    public static final double LAND_CONTACT = 0.35;
    /**
     * Fraction of the cruise altitude that ends the climb-out.
     */
    public static final double CLIMB_COMPLETE = 0.95;
    /** How near its station a drone must be to count as formed up, as a fraction of the squad's spacing. */
    public static final double MUSTER_IN_PLACE = 0.5;
    public static final double MUSTER_IN_PLACE_FLOOR = 3.0;
    /** How long a flight will wait for itself before going anyway, in ticks. */
    public static final int MUSTER_TIMEOUT = 1200;
    /**
     * Sink rate of an unpowered drone. Slower than free fall: the rotors still windmill.
     */
    public static final double SINK_RATE = 0.35;
    /**
     * How much of its horizontal momentum an unpowered drone keeps each tick.
     */
    public static final double SINK_DRAG = 0.92;
    /** How far above cruise speed a follower may push to regain its slot. */
    public static final double FORMATION_MAX_OVERSPEED = 1.6;
    /** How close two squadmates may get before they actively push apart, and how hard. */
    public static final double SEPARATION_RADIUS = 4.5;
    /** The most of a squad's ordered spacing the avoidance rule may claim, when that is the tighter of the two. */
    public static final double SEPARATION_SPACING_SHARE = 0.8;
    public static final double SEPARATION_STRENGTH = 0.6;
    /**
     * Cap on the total push, so a drone hemmed in on all sides is nudged clear rather than fired out of the squad.
     */
    public static final double SEPARATION_MAX = 0.9;
    /** How much wider than the flight formation a strike's aim points are spread. */
    public static final double PAYLOAD_AIM_SPREAD = 1.5;
    /** Distance from the aim point at which a payload drone breaks off cruise and starts its attack run. */
    public static final double PAYLOAD_RUN_IN = 140.0;
    /**
     * Fraction of the ordered release speed the drone must have reached before it will pickle, so it doesn't drop
     * while still accelerating.
     */
    public static final double RELEASE_SPEED_TOLERANCE = 0.9;
    /** Downward acceleration of a released payload, matching {@code BombletEntity}. */
    public static final double PAYLOAD_GRAVITY = 0.05;
    /**
     * How far above its cruise altitude a drone climbs after pickling before it counts as clear of the weapon and
     * rejoins the mission.
     */
    public static final double BREAK_OFF_CLIMB = 12.0;
    /**
     * Yaw spin of a shot-down drone, radians/tick.
     */
    public static final float DOWNED_SPIN = 0.45f;
    /** How fast a wreck tips over as it falls, radians/tick, and how far. */
    public static final double DOWNED_TUMBLE = 0.05;
    public static final double DOWNED_TUMBLE_LIMIT = Math.toRadians(75.0);
    /** How fast a drone that has merely run out of power returns to level, per tick. */
    public static final double UNPOWERED_LEVELLING = 0.05;
    /**
     * Downward acceleration while spinning out, and how much horizontal momentum a falling wreck keeps.
     */
    public static final double DOWNED_GRAVITY = 0.06;
    public static final double DOWNED_DRAG = 0.96;
    public static final double DOWNED_TERMINAL = -1.6;
    /**
     * How hard a one-sided airframe slides toward its dead rotors while it falls, blocks/tick per unit of
     * asymmetry.
     */
    public static final double DOWNED_SIDESLIP = 0.055;

    private Tuning() {
    }
}
