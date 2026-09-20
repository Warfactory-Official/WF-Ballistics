package com.wf.wflib.drone.nav;

import com.wf.wflib.drone.DroneState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The seam between the drone and the terrain: which point a drone is currently routing to, when its route needs
 * replanning, how much world to condense to plan it, and how high it has to be right now to clear what is in front
 * of it.
 */
public final class DroneNavigation {

    /** How far ahead a single route is planned. */
    public static final double PLAN_HORIZON = 224.0;
    /** Margin around the route corridor. */
    public static final double PLAN_PAD = 72.0;
    /** Cap on either side of a field, in cells. */
    public static final int PLAN_CELLS = 96;
    /** How far above the route's own altitude a drone is willing to climb. */
    public static final double CEILING_MARGIN = 96.0;
    /** Ticks before a drone that asked for a route and has not been given one asks again. */
    public static final long PLAN_RETRY_TICKS = 40L;

    private static final double MIN_LOOKAHEAD = 24.0;
    private static final double MAX_LOOKAHEAD = 112.0;
    /** Ticks of flight the look-ahead covers, so a faster drone looks proportionally further. */
    private static final double LOOKAHEAD_TICKS = 96.0;

    private DroneNavigation() {
    }

    /**
     * @return the point this drone is currently routing to, or null if it is not going anywhere. Both the
     *      carrier and the brain ask this so that the route being planned is the route being flown.
     */
    @Nullable
    public static Vec3 goal(DroneState state, @Nullable Vec3 leg, @Nullable Vec3 destination, Vec3 exfil) {
        Vec3 endpoint = switch (state) {
            case TRANSIT, DELIVER, COLLECT, PAYLOAD_RUN, MINELAY -> destination;
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
     * Condense the terrain needed to plan the next leg.
     *
     * @param fallbackTop height to assume where the world is not loaded: the drone's own ground height is
     *      the honest guess, since it makes unknown terrain look like more of what it is
     *      standing over
     * @return the field, or null if this dimension has spent its build allowance this tick
     */
    @Nullable
    public static TerrainField field(ServerLevel level, Vec3 from, Vec3 goal, double fallbackTop) {
        return TerrainCache.get(level).build(level, from, horizon(from, goal), PLAN_PAD, PLAN_CELLS, fallbackTop);
    }

    /**
     * @return the goal, or the point {@link #PLAN_HORIZON} blocks toward it if it is further away than one
     *      leg.
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

    /** How high the drone needs to be <em>now</em> to clear what is in front of it. */
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
