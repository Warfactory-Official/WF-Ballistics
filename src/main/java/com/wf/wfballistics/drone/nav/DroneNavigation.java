package com.wf.wfballistics.drone.nav;

import com.wf.wfballistics.drone.DroneState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The seam between the drone and the terrain: which point a drone is currently routing to, when its route
 * needs replanning, how much world to condense to plan it, and how high it has to be right now to clear what
 * is in front of it.
 *
 * <p>Split out from both the carrier and the brain because both need the same answers on different threads
 * (the carrier decides on the world thread whether to pay for a {@link TerrainField}, and the brain decides
 * off-thread what to do with one) and they must agree, or drones would plan routes they never fly.
 */
public final class DroneNavigation {

    /**
     * How far ahead a single route is planned. Long missions are covered in legs: a drone plans this far,
     * flies it, and asks again. Planning the whole way at once would mean condensing terrain the drone will
     * not reach for minutes, most of which is not loaded and would come back unknown anyway.
     */
    public static final double PLAN_HORIZON = 224.0;
    /**
     * Margin around the route corridor. This is the room the planner has to go <em>around</em> something, so
     * it needs to be a decent fraction of the horizon: with no padding the search could only ever pick a
     * height to cross an obstacle at, never a way past it.
     */
    public static final double PLAN_PAD = 72.0;
    /**
     * Cap on either side of a field, in cells. At four blocks a cell this is a 384-block square, or about
     * nine thousand cells for the search to chew through.
     */
    public static final int PLAN_CELLS = 96;
    /**
     * How far above the route's own altitude a drone is willing to climb. Terrain that would need more is
     * treated as a wall to go around.
     */
    public static final double CEILING_MARGIN = 96.0;
    /**
     * Ticks before a drone that asked for a route and has not been given one asks again. A search runs as its
     * own job and may take several ticks, and condensing the terrain for it is the expensive half, so this
     * stops a drone paying for a fresh field every tick while the search it already ordered is still running,
     * while still recovering if that search is lost.
     */
    public static final long PLAN_RETRY_TICKS = 40L;

    private static final double MIN_LOOKAHEAD = 24.0;
    private static final double MAX_LOOKAHEAD = 112.0;
    /**
     * Ticks of flight the look-ahead covers, so a faster drone looks proportionally further. It has to see
     * far enough that its climb rate can still clear whatever is out there.
     */
    private static final double LOOKAHEAD_TICKS = 96.0;

    private DroneNavigation() {
    }

    /**
     * @return the point this drone is currently routing to, or null if it is not going anywhere. Both the
     * carrier and the brain ask this so that the route being planned is the route being flown.
     */
    @Nullable
    public static Vec3 goal(DroneState state, @Nullable Vec3 leg, @Nullable Vec3 destination, Vec3 exfil) {
        Vec3 endpoint = switch (state) {
            case TRANSIT, DELIVER, COLLECT, PAYLOAD_RUN -> destination;
            case EXFIL -> exfil;
            case TAKEOFF, MUSTER, LANDING, SURVEIL, WORK, SUPPLY, IDLE, DEPLETED, DOWNED -> null;
        };
        if (leg != null && state.followsTerrain()) {
            return leg;
        }
        return endpoint;
    }

    /**
     * @return true if this drone should have a route and does not have a usable one.
     */
    public static boolean needsPlan(@Nullable DronePath path, Vec3 pos, @Nullable Vec3 goal, long now) {
        if (goal == null) {
            return false;
        }
        return path == null || path.stale(pos, goal, now);
    }

    /**
     * Condense the terrain needed to plan the next leg. Called on the world thread while building a snapshot.
     *
     * @param fallbackTop height to assume where the world is not loaded: the drone's own ground height is
     *                    the honest guess, since it makes unknown terrain look like more of what it is
     *                    standing over
     * @return the field, or null if this dimension has spent its build allowance this tick
     */
    @Nullable
    public static TerrainField field(ServerLevel level, Vec3 from, Vec3 goal, double fallbackTop) {
        return TerrainCache.get(level).build(level, from, horizon(from, goal), PLAN_PAD, PLAN_CELLS, fallbackTop);
    }

    /**
     * @return the goal, or the point {@link #PLAN_HORIZON} blocks toward it if it is further away than one
     * leg.
     */
    public static Vec3 horizon(Vec3 from, Vec3 goal) {
        double dx = goal.x - from.x;
        double dz = goal.z - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= PLAN_HORIZON || distance < 1.0E-6) {
            return goal;
        }
        double scale = PLAN_HORIZON / distance;
        return new Vec3(from.x + dx * scale, goal.y, from.z + dz * scale);
    }

    /**
     * How high the drone needs to be <em>now</em> to clear what is in front of it.
     *
     * <p>This is terrain following proper, rather than a proximity alarm. For each patch of ground ahead it
     * works out how long the drone will take to reach it and how much of the climb it can do in that time,
     * and requires only the altitude it cannot make up later:
     *
     * <pre>
     *   required = top + clearance − climbRate · (distance / speed)
     * </pre>
     *
     * <p>Taking the worst case over everything ahead means a drone starts climbing at exactly the last moment
     * it still can, and so crosses a ridge at ridge height rather than dragging itself over a whole valley at
     * the height of the next mountain.
     *
     * <p><b>{@code planningSpeed} must be the speed the drone intends to fly, not the speed it is flying.</b>
     * Feeding this its measured velocity makes it oscillate: the guard trades ground speed for climb, the
     * lower speed buys more time to climb, the requirement drops, the guard lets go, the drone speeds back
     * up and the requirement returns: a drone porpoising down the whole route at full throttle. Its ordered
     * speed is a constant, so the answer is stable, and taking the fastest leg it might fly keeps it safe.
     *
     * <p>World thread only: it reads the terrain cache.
     */
    public static double requiredAltitude(ServerLevel level, Vec3 pos, Vec3 velocity, double planningSpeed,
                                          double climbRate, double groundBelow) {
        TerrainCache cache = TerrainCache.get(level);
        int fallback = (int) Math.floor(groundBelow);
        double required = cache.topAt(level, pos.x, pos.z, fallback) + TerrainGuard.MIN_CLEARANCE;

        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (speed < 1.0E-3 || climbRate <= 1.0E-6 || planningSpeed <= 1.0E-6) {
            return required;
        }
        double distance = Mth.clamp(planningSpeed * LOOKAHEAD_TICKS, MIN_LOOKAHEAD, MAX_LOOKAHEAD);
        double dirX = velocity.x / speed;
        double dirZ = velocity.z / speed;
        for (double d = TerrainCache.CELL; d <= distance; d += TerrainCache.CELL) {
            int top = cache.topAt(level, pos.x + dirX * d, pos.z + dirZ * d, fallback);
            double climbAvailable = climbRate * (d / planningSpeed);
            required = Math.max(required, top + TerrainGuard.MIN_CLEARANCE - climbAvailable);
        }
        return required;
    }
}
