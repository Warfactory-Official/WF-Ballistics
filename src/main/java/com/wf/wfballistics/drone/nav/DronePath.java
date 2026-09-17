package com.wf.wfballistics.drone.nav;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A route over the terrain: corner points whose heights already clear the ground between them, so following the
 * line from one to the next is itself the terrain following.
 *
 * @param goal what this route was planned to reach, so a retasked drone notices its path is for the old
 *      destination
 * @param createdAt the tick it was planned on
 * @param partial true if the planner ran out of room or budget and this only heads the right way. The drone
 *      flies it and asks again on arrival, which is how a route longer than one planning horizon
 *      gets covered in pieces
 */
public record DronePath(List<Vec3> waypoints, Vec3 goal, long createdAt, boolean partial) {

    /** How far ahead along the route the drone aims. */
    public static final double LOOKAHEAD = 24.0;
    /** How far ahead the <em>altitude</em> is taken from, which is much less than the heading is. */
    public static final double ALTITUDE_LEAD = 8.0;
    /** Ticks before a route is replanned regardless. */
    public static final long MAX_AGE = 400L;
    /**
     * How far the destination may move before the route is thrown away.
     */
    public static final double GOAL_TOLERANCE = 8.0;
    /** How far off the line the drone may drift before the route no longer describes where it is. */
    public static final double OFF_ROUTE = 48.0;
    /**
     * How close to the end of a partial route counts as having used it up.
     */
    public static final double END_OF_LEG = 24.0;

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    public Vec3 end() {
        return waypoints.get(waypoints.size() - 1);
    }

    /**
     * @return the point to fly at: {@link #LOOKAHEAD} blocks further along the route than the drone's own
     *      closest point on it. Its height is interpolated along the leg, so the drone climbs into a ridge over
     *      the length of the approach instead of at the last moment.
     */
    public Vec3 carrot(Vec3 pos) {
        return carrot(pos, LOOKAHEAD);
    }

    /**
     * @param lookahead how far along the route to aim
     * @see #carrot(Vec3)
     */
    public Vec3 carrot(Vec3 pos, double lookahead) {
        if (waypoints.isEmpty()) {
            return pos;
        }
        if (waypoints.size() == 1) {
            return waypoints.get(0);
        }

        int bestSegment = 0;
        double bestT = 0.0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 a = waypoints.get(i);
            Vec3 b = waypoints.get(i + 1);
            double t = projection(pos, a, b);
            double distance = horizontalDistance(pos, lerp(a, b, t));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
                bestT = t;
            }
        }

        double remaining = lookahead;
        for (int i = bestSegment; i < waypoints.size() - 1; i++) {
            Vec3 a = waypoints.get(i);
            Vec3 b = waypoints.get(i + 1);
            double length = horizontalDistance(a, b);
            double start = i == bestSegment ? bestT : 0.0;
            double available = length * (1.0 - start);
            if (available >= remaining) {
                double t = length < 1.0E-6 ? 1.0 : start + remaining / length;
                return lerp(a, b, Math.min(1.0, t));
            }
            remaining -= available;
        }
        return end();
    }

    /**
     * @return how far off the route the drone is, horizontally.
     */
    public double deviation(Vec3 pos) {
        if (waypoints.isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (waypoints.size() == 1) {
            return horizontalDistance(pos, waypoints.get(0));
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 a = waypoints.get(i);
            Vec3 b = waypoints.get(i + 1);
            best = Math.min(best, horizontalDistance(pos, lerp(a, b, projection(pos, a, b))));
        }
        return best;
    }

    /**
     * @return true if this route no longer describes where the drone is or where it is going, and should be
     *      replanned. Deliberately generous: replanning is the expensive half, and a slightly old route over
     *      terrain that has not moved is still a good route.
     */
    public boolean stale(Vec3 pos, @Nullable Vec3 currentGoal, long now) {
        if (waypoints.isEmpty() || currentGoal == null) {
            return true;
        }
        if (horizontalDistance(goal, currentGoal) > GOAL_TOLERANCE) {
            return true;
        }
        if (now - createdAt > MAX_AGE) {
            return true;
        }
        if (deviation(pos) > OFF_ROUTE) {
            return true;
        }
        return partial && horizontalDistance(pos, end()) <= END_OF_LEG;
    }

    private static double projection(Vec3 pos, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr < 1.0E-9) {
            return 0.0;
        }
        double t = ((pos.x - a.x) * dx + (pos.z - a.z) * dz) / lengthSqr;
        return Math.max(0.0, Math.min(1.0, t));
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
