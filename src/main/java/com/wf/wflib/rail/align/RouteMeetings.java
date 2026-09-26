package com.wf.wflib.rail.align;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds where a world's surveyed routes meet each other.
 *
 * <p>Pure geometry over compiled centrelines, with no world and no Immersive Railroading in it, so the
 * part that decides whether two railways cross can be tested without either. The result is the input to
 * everything that builds a meeting: a track layer needs the chainages to put its piece joins on, and a
 * turnout needs the headings on both sides of it.</p>
 *
 * <p>Two ways a meeting is found, and both are needed. A <b>crossing</b> is two sampled segments that
 * genuinely intersect, which is the only way to catch two lines passing through each other in open
 * country. A <b>touch</b> is one route's endpoint lying on the other, which is how a branch is actually
 * surveyed and which no intersection test would ever see, because the branch stops at the main line
 * rather than passing through it.</p>
 */
public final class RouteMeetings {

    /** How finely a centreline is walked. A curve departs from its chord by under a hundredth here. */
    private static final double STEP = 2.0;

    /** How close an endpoint has to be to the other line to be on it, in blocks. */
    public static final double TOUCH = 3.0;

    /**
     * How near an end a meeting has to fall to count as that route ending there.
     *
     * <p>Wider than {@link #TOUCH} because it is answering a different question: not "is this point on
     * that line" but "is this route stopping here, or running past". A branch surveyed a few blocks
     * short of the main line is still a branch.</p>
     */
    public static final double END_REACH = 8.0;

    /** Meetings closer together than this are the same meeting found twice. */
    private static final double MERGE = 8.0;

    /** Sanity bound on one pair, so a pathological survey cannot take a tick with it. */
    private static final int MAX_PER_PAIR = 32;

    private RouteMeetings() {
    }

    /** A centreline walked into a polyline, keeping what a meeting needs to know at every vertex. */
    private record Walk(UUID route, String name, UUID owner, double[] xs, double[] zs, double[] chainage,
                        double[] heading, double length) {

        int points() {
            return this.xs.length;
        }
    }

    /** Every meeting between the routes of one world. */
    public static List<RouteMeeting> find(ServerLevel level) {
        return find(AlignmentStore.of(level).all());
    }

    /**
     * Every meeting between these routes, in a stable order.
     *
     * <p>Every pair is considered once. A route is never compared with itself: a line that crosses its
     * own path is a loop, and a loop needs no junction to work.</p>
     */
    public static List<RouteMeeting> find(Collection<Alignment> routes) {
        List<Walk> walks = new ArrayList<>();
        for (Alignment route : routes) {
            Walk walk = walk(route);
            if (walk != null) {
                walks.add(walk);
            }
        }
        List<RouteMeeting> out = new ArrayList<>();
        for (int i = 0; i < walks.size(); i++) {
            for (int j = i + 1; j < walks.size(); j++) {
                between(walks.get(i), walks.get(j), out);
            }
        }
        return out;
    }

    /** Every meeting one route has with the others, which is what a track layer for it wants. */
    public static List<RouteMeeting> on(Collection<Alignment> routes, UUID route) {
        List<RouteMeeting> out = new ArrayList<>();
        for (RouteMeeting meeting : find(routes)) {
            if (meeting.sideOf(route) != null) {
                out.add(meeting);
            }
        }
        return out;
    }

    /** The chainages along one route at which its meetings fall, sorted, for splitting track at them. */
    public static List<Double> breaksOn(Collection<RouteMeeting> meetings, UUID route) {
        List<Double> out = new ArrayList<>();
        for (RouteMeeting meeting : meetings) {
            RouteMeeting.Side side = meeting.sideOf(route);
            if (side != null) {
                out.add(side.chainage());
            }
        }
        out.sort(Double::compare);
        return out;
    }

    private static Walk walk(Alignment route) {
        Centreline line = route.compile().centreline();
        double length = line.length();
        if (length <= STEP) {
            return null;
        }
        int steps = (int) Math.ceil(length / STEP);
        double[] xs = new double[steps + 1];
        double[] zs = new double[steps + 1];
        double[] chainage = new double[steps + 1];
        double[] heading = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double s = Math.min(length, length * i / steps);
            AlignElement.Sample at = line.at(s);
            xs[i] = at.x();
            zs[i] = at.z();
            chainage[i] = s;
            heading[i] = at.heading();
        }
        return new Walk(route.id(), route.name(), route.ownerFaction(), xs, zs, chainage, heading,
                length);
    }

    private static void between(Walk a, Walk b, List<RouteMeeting> out) {
        List<RouteMeeting> found = new ArrayList<>();
        // A coarse index over one route's segments, so a long pair costs a lookup per segment rather
        // than the product of the two. A surveyed main line is thousands of blocks.
        Map<Long, List<Integer>> index = index(a);
        for (int j = 0; j + 1 < b.points() && found.size() < MAX_PER_PAIR; j++) {
            for (int i : candidates(index, b.xs()[j], b.zs()[j], b.xs()[j + 1], b.zs()[j + 1])) {
                double[] hit = cross(a.xs()[i], a.zs()[i], a.xs()[i + 1], a.zs()[i + 1],
                        b.xs()[j], b.zs()[j], b.xs()[j + 1], b.zs()[j + 1]);
                if (hit == null) {
                    continue;
                }
                double sa = a.chainage()[i] + (a.chainage()[i + 1] - a.chainage()[i]) * hit[0];
                double sb = b.chainage()[j] + (b.chainage()[j + 1] - b.chainage()[j]) * hit[1];
                add(found, meeting(a, sa, b, sb, hit[2], hit[3]));
            }
        }
        // Then the touches: an endpoint of one lying on the other, which is how a branch is surveyed and
        // which the intersection test above can never see, because a branch stops rather than crosses.
        touch(a, 0.0, b, found);
        touch(a, a.length(), b, found);
        touch(b, 0.0, a, found);
        touch(b, b.length(), a, found);
        out.addAll(found);
    }

    /** One route's endpoint against the whole of another line. */
    private static void touch(Walk end, double chainage, Walk line, List<RouteMeeting> found) {
        int at = chainage <= 0.0 ? 0 : end.points() - 1;
        double px = end.xs()[at];
        double pz = end.zs()[at];
        double bestDistance = TOUCH;
        double bestChainage = -1.0;
        double bestX = 0.0;
        double bestZ = 0.0;
        for (int i = 0; i + 1 < line.points(); i++) {
            double ax = line.xs()[i];
            double az = line.zs()[i];
            double dx = line.xs()[i + 1] - ax;
            double dz = line.zs()[i + 1] - az;
            double lengthSq = dx * dx + dz * dz;
            if (lengthSq <= 1.0E-9) {
                continue;
            }
            double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / lengthSq));
            double nx = ax + dx * t;
            double nz = az + dz * t;
            double distance = Math.hypot(px - nx, pz - nz);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestChainage = line.chainage()[i] + (line.chainage()[i + 1] - line.chainage()[i]) * t;
                bestX = nx;
                bestZ = nz;
            }
        }
        if (bestChainage < 0.0) {
            return;
        }
        // The meeting is put where the through line actually runs rather than at the branch's own last
        // point: that is the block a switch has to be built in, and the branch may stop short of it.
        add(found, meeting(end, chainage, line, bestChainage, bestX, bestZ));
    }

    private static RouteMeeting meeting(Walk a, double sa, Walk b, double sb, double x, double z) {
        double ha = headingAt(a, sa);
        double hb = headingAt(b, sb);
        RouteMeeting.End endA = endOf(a, sa);
        RouteMeeting.End endB = endOf(b, sb);
        RouteMeeting.Kind kind;
        if (endA.terminal() && endB.terminal()) {
            kind = RouteMeeting.Kind.JOINT;
        } else if (endA.terminal() || endB.terminal()) {
            kind = RouteMeeting.Kind.TURNOUT;
        } else {
            kind = RouteMeeting.Kind.CROSSING;
        }
        return new RouteMeeting(
                new RouteMeeting.Side(a.route(), a.name(), a.owner(), sa, ha, endA),
                new RouteMeeting.Side(b.route(), b.name(), b.owner(), sb, hb, endB),
                kind, x, z, acute(ha, hb));
    }

    /** The angle between two headings, folded into the nought to ninety degrees a crossing can have. */
    static double acute(double ha, double hb) {
        double degrees = Math.abs(Math.toDegrees(ha - hb)) % 180.0;
        return degrees > 90.0 ? 180.0 - degrees : degrees;
    }

    /**
     * The route's heading at a chainage.
     *
     * <p>Indexed off the walk's own spacing and not off {@link #STEP}. The two are not the same: a line
     * is divided into a whole number of equal steps so that the last one lands exactly on its end, which
     * makes every step a little shorter than asked for. Using the nominal step here reads a heading from
     * further and further along the line the further along it you ask, which on a curve is a heading
     * that is simply wrong.</p>
     */
    private static double headingAt(Walk walk, double chainage) {
        int last = walk.points() - 1;
        if (last <= 0 || walk.length() <= 0.0) {
            return walk.heading()[0];
        }
        int i = (int) Math.round(chainage / walk.length() * last);
        return walk.heading()[Math.max(0, Math.min(i, last))];
    }

    private static RouteMeeting.End endOf(Walk walk, double chainage) {
        if (chainage <= END_REACH) {
            return RouteMeeting.End.START;
        }
        return chainage >= walk.length() - END_REACH ? RouteMeeting.End.FINISH : RouteMeeting.End.NONE;
    }

    /** Keep the meeting unless one this close has already been found: the same place, found twice. */
    private static void add(List<RouteMeeting> found, RouteMeeting meeting) {
        for (RouteMeeting seen : found) {
            if (Math.hypot(seen.x() - meeting.x(), seen.z() - meeting.z()) < MERGE) {
                return;
            }
        }
        if (found.size() < MAX_PER_PAIR) {
            found.add(meeting);
        }
    }

    // ------------------------------------------------------------------ the geometry

    /**
     * Where two segments cross.
     *
     * @return {t along the first, t along the second, x, z}, or null when they do not
     */
    static double[] cross(double ax, double az, double bx, double bz,
                          double cx, double cz, double dx, double dz) {
        double rx = bx - ax;
        double rz = bz - az;
        double sx = dx - cx;
        double sz = dz - cz;
        double denominator = rx * sz - rz * sx;
        if (Math.abs(denominator) < 1.0E-12) {
            // Parallel, which includes two routes running alongside each other. Not a crossing.
            return null;
        }
        double t = ((cx - ax) * sz - (cz - az) * sx) / denominator;
        double u = ((cx - ax) * rz - (cz - az) * rx) / denominator;
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) {
            return null;
        }
        return new double[]{t, u, ax + rx * t, az + rz * t};
    }

    private static Map<Long, List<Integer>> index(Walk walk) {
        Map<Long, List<Integer>> out = new HashMap<>();
        for (int i = 0; i + 1 < walk.points(); i++) {
            for (long cell : cells(walk.xs()[i], walk.zs()[i], walk.xs()[i + 1], walk.zs()[i + 1])) {
                out.computeIfAbsent(cell, key -> new ArrayList<>()).add(i);
            }
        }
        return out;
    }

    private static List<Integer> candidates(Map<Long, List<Integer>> index, double x1, double z1,
                                            double x2, double z2) {
        List<Integer> out = new ArrayList<>();
        for (long cell : cells(x1, z1, x2, z2)) {
            List<Integer> here = index.get(cell);
            if (here != null) {
                for (int i : here) {
                    if (!out.contains(i)) {
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    /** The coarse cells a segment's box touches, grown by the touch reach so a near miss still meets. */
    private static List<Long> cells(double x1, double z1, double x2, double z2) {
        int minX = (int) Math.floor(Math.min(x1, x2) - TOUCH) >> 4;
        int maxX = (int) Math.floor(Math.max(x1, x2) + TOUCH) >> 4;
        int minZ = (int) Math.floor(Math.min(z1, z2) - TOUCH) >> 4;
        int maxZ = (int) Math.floor(Math.max(z1, z2) + TOUCH) >> 4;
        List<Long> out = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                out.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
            }
        }
        return out;
    }
}
