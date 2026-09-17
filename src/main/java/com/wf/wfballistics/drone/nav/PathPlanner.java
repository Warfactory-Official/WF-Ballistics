package com.wf.wfballistics.drone.nav;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** Finds a route over terrain: an A* across a {@link TerrainField}, run on a planner worker. */
public final class PathPlanner {

    /** How far above the terrain a route is flown. */
    public static final double CLEARANCE = 6.0;
    /** Half the airframe's width, near enough. */
    public static final double BODY_RADIUS = 2.0;
    /** Cost of a block of climb relative to a block of travel. */
    public static final double CLIMB_WEIGHT = 2.5;
    /**
     * Descending is nearly free: a drone gets most of the way down by easing off.
     */
    public static final double DESCEND_WEIGHT = 0.15;
    /** Cells expanded before the search gives up and returns the best it found. */
    public static final int MAX_EXPANSIONS = 8000;

    private static final int[] NEIGHBOUR_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NEIGHBOUR_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    private PathPlanner() {
    }

    /**
     * Plan a route.
     *
     * @param ceiling the highest a route may be flown. Terrain that would push the drone above this is
     *      impassable, so a wall too tall to climb is gone around rather than scaled
     * @return a route, never null. If nothing was found it is a direct line high enough to clear both ends,
     *      which the reactive {@link TerrainGuard} then flies safely: a drone with a bad plan should still move
     */
    public static DronePath plan(TerrainField field, Vec3 from, Vec3 to, double clearance, double ceiling,
                                 long now) {
        int width = field.width();
        int depth = field.depth();
        int startX = clamp(field.cellX(from.x), width);
        int startZ = clamp(field.cellZ(from.z), depth);
        int goalX = clamp(field.cellX(to.x), width);
        int goalZ = clamp(field.cellZ(to.z), depth);
        boolean partial = !field.inBounds(field.cellX(to.x), field.cellZ(to.z));

        int start = field.index(startX, startZ);
        int goal = field.index(goalX, goalZ);
        if (start == goal) {
            return direct(field, from, to, clearance, now, partial);
        }

        int cells = width * depth;
        double[] cost = new double[cells];
        int[] parent = new int[cells];
        boolean[] closed = new boolean[cells];
        Arrays.fill(cost, Double.MAX_VALUE);
        Arrays.fill(parent, -1);

        PriorityQueue<Node> open = new PriorityQueue<>();
        cost[start] = 0.0;
        open.add(new Node(start, heuristic(field, startX, startZ, goalX, goalZ)));

        int expansions = 0;
        int best = start;
        double bestHeuristic = heuristic(field, startX, startZ, goalX, goalZ);
        boolean reached = false;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node node = open.poll();
            if (closed[node.index()]) {
                continue;
            }
            closed[node.index()] = true;
            expansions++;

            if (node.index() == goal) {
                reached = true;
                best = goal;
                break;
            }

            int cx = node.index() % width;
            int cz = node.index() / width;
            double here = altitude(field, cx, cz, clearance);

            double h = heuristic(field, cx, cz, goalX, goalZ);
            if (h < bestHeuristic) {
                bestHeuristic = h;
                best = node.index();
            }

            for (int i = 0; i < NEIGHBOUR_X.length; i++) {
                int nx = cx + NEIGHBOUR_X[i];
                int nz = cz + NEIGHBOUR_Z[i];
                if (!field.inBounds(nx, nz)) {
                    continue;
                }
                int next = field.index(nx, nz);
                if (closed[next]) {
                    continue;
                }
                double there = altitude(field, nx, nz, clearance);
                if (there > ceiling) {
                    continue;
                }
                double travel = field.cell() * (NEIGHBOUR_X[i] != 0 && NEIGHBOUR_Z[i] != 0 ? 1.4142 : 1.0);
                double step = travel
                        + CLIMB_WEIGHT * Math.max(0.0, there - here)
                        + DESCEND_WEIGHT * Math.max(0.0, here - there);
                double through = cost[node.index()] + step;
                if (through < cost[next]) {
                    cost[next] = through;
                    parent[next] = node.index();
                    open.add(new Node(next, through + heuristic(field, nx, nz, goalX, goalZ)));
                }
            }
        }

        if (parent[best] < 0 && best != start) {
            return direct(field, from, to, clearance, now, partial);
        }

        List<Vec3> raw = reconstruct(field, parent, start, best, clearance);
        if (raw.size() < 2) {
            return direct(field, from, to, clearance, now, partial);
        }
        raw.set(0, new Vec3(from.x, raw.get(0).y, from.z));
        if (reached && !partial) {
            raw.set(raw.size() - 1, new Vec3(to.x, raw.get(raw.size() - 1).y, to.z));
        }

        List<Vec3> smoothed = smooth(field, raw, clearance);
        return new DronePath(List.copyOf(smoothed), to, now, partial || !reached);
    }

    /**
     * @return a straight line flown high enough to clear everything under it. The fallback when the search
     *      found nothing, and the whole route when start and goal share a cell.
     */
    private static DronePath direct(TerrainField field, Vec3 from, Vec3 to, double clearance, long now,
                                    boolean partial) {
        double top = field.topAlong(from.x, from.z, to.x, to.z, BODY_RADIUS) + clearance;
        List<Vec3> line = List.of(new Vec3(from.x, top, from.z), new Vec3(to.x, top, to.z));
        return new DronePath(line, to, now, partial);
    }

    private static List<Vec3> reconstruct(TerrainField field, int[] parent, int start, int end,
                                          double clearance) {
        List<Vec3> out = new ArrayList<>();
        int width = field.width();
        int cursor = end;
        int guard = 0;
        while (cursor >= 0 && guard++ <= parent.length) {
            int cx = cursor % width;
            int cz = cursor / width;
            out.add(new Vec3(field.worldX(cx), altitude(field, cx, cz, clearance), field.worldZ(cz)));
            if (cursor == start) {
                break;
            }
            cursor = parent[cursor];
        }
        Collections.reverse(out);
        return out;
    }

    /** String-pulling: drop every corner the drone can fly straight past. */
    private static List<Vec3> smooth(TerrainField field, List<Vec3> raw, double clearance) {
        List<Vec3> out = new ArrayList<>();
        out.add(raw.get(0));
        int anchor = 0;
        while (anchor < raw.size() - 1) {
            int furthest = anchor + 1;
            for (int candidate = raw.size() - 1; candidate > anchor + 1; candidate--) {
                if (clearBetween(field, raw.get(anchor), raw.get(candidate), clearance)) {
                    furthest = candidate;
                    break;
                }
            }
            out.add(raw.get(furthest));
            anchor = furthest;
        }
        return out;
    }

    private static boolean clearBetween(TerrainField field, Vec3 a, Vec3 b, double clearance) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / field.cell()));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = a.x + dx * t;
            double z = a.z + dz * t;
            double flownY = a.y + (b.y - a.y) * t;
            if (field.topAround(x, z, BODY_RADIUS) + clearance > flownY) {
                return false;
            }
        }
        return true;
    }

    private static double altitude(TerrainField field, int cellX, int cellZ, double clearance) {
        return field.top(cellX, cellZ) + clearance;
    }

    /**
     * Octile distance in blocks: the shortest possible travel, never counting the climb it would also cost, so it
     * never overestimates and A* stays optimal.
     */
    private static double heuristic(TerrainField field, int fromX, int fromZ, int toX, int toZ) {
        int dx = Math.abs(toX - fromX);
        int dz = Math.abs(toZ - fromZ);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return field.cell() * (straight + 1.4142 * diagonal);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private record Node(int index, double score) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Double.compare(this.score, other.score);
        }
    }
}
