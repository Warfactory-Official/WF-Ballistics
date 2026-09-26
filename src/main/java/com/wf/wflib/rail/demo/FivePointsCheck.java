package com.wf.wflib.rail.demo;

import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.mod.math.Vec3d;
import com.wf.wflib.rail.RailCompat;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.build.ir.IrRoll;
import com.wf.wflib.rail.build.ir.IrStock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The verdict on a deployed Five Points network.
 *
 * <p>Four questions, in the order they stop being worth asking if the one before failed: is the network
 * drawn the way it was designed, does every route carry a railway, did building each one leave the
 * others alone, and can a train actually get anywhere. The last is the only one that is about a network
 * rather than about a route, and it is the reason {@link IrRoll} exists.</p>
 */
public final class FivePointsCheck {

    /** How finely a route is asked whether it carries a railway. */
    private static final double STEP = 2.0;

    /** How far a roll may run before it has made its point. */
    private static final double ROLL_LIMIT = 3000.0;

    /** How near the end of a roll has to be to a stop to count as standing at it. */
    private static final double ARRIVED = 12.0;

    /** How close a route end has to be to a stop to be serving it. */
    private static final double SERVES = 2.0;

    /** How far back from a junction a train is stood before it is run at it, in blocks. */
    private static final double APPROACH = 12.0;

    /** How far past a junction counts as having gone through it rather than into it. */
    private static final double THROUGH = 24.0;

    /**
     * How far inside the route a roll starts, in blocks.
     *
     * <p>Not at the stop itself. A stop is a surveyed point and lands on a block boundary as often as
     * not, and the block on the far side of that boundary is off the end of the railway: starting there
     * reports a stop no train can leave, which is a believable and completely wrong answer. A few blocks
     * in is also where a train actually stands.</p>
     */
    private static final double INSIDE = 4.0;

    /** How far inside a route a train actually stands, once the junction's own stretch is allowed for. */
    private static double inside() {
        return INSIDE + com.wf.wflib.rail.RailConfig.TURNOUT_LEAD.get();
    }

    private FivePointsCheck() {
    }

    /**
     * Where the network was deployed, recovered from a route rather than assumed.
     *
     * <p>The origin is not stored anywhere, because a route is a list of world coordinates once it is
     * drawn. Taking it back off a drawn route means a network deployed somewhere else is still this
     * network and still checkable, which a hard coded origin would quietly not be.</p>
     */
    private record Site(double x, double z) {

        static Site of(List<Alignment> routes) {
            for (Alignment route : routes) {
                FivePoints.Route drawn = FivePoints.route(route.name());
                if (drawn != null && !route.points().isEmpty()) {
                    return new Site(route.points().get(0).x() - drawn.points().get(0)[0],
                            route.points().get(0).z() - drawn.points().get(0)[1]);
                }
            }
            return new Site(0.0, 0.0);
        }

        double x(FivePoints.Stop stop) {
            return this.x + stop.x();
        }

        double z(FivePoints.Stop stop) {
            return this.z + stop.z();
        }
    }

    public static List<String> verdict(ServerLevel level, int floorY) {
        List<String> out = new ArrayList<>();
        List<Alignment> routes = FivePointsFixture.drawn(level);
        if (routes.isEmpty()) {
            out.add("the Five Points network is not drawn here; /wfrail demo survey draws it");
            return out;
        }
        Site site = Site.of(routes);
        out.add(String.format(Locale.ROOT,
                "Five Points: %d of %d route(s) drawn at %.0f, %.0f, floor y=%d",
                routes.size(), FivePoints.routes().size(), site.x(), site.z(), floorY));

        junctions(routes, out);
        coverage(level, routes, floorY, out);
        joints(level, routes, floorY, out);
        reachability(level, routes, site, floorY, out);
        return out;
    }

    // ------------------------------------------------------------------ is it drawn as designed

    private static void junctions(List<Alignment> routes, List<String> out) {
        List<RouteMeeting> found = new ArrayList<>(RouteMeetings.find(routes));
        List<FivePoints.Meeting> expected = FivePoints.expected();
        List<FivePoints.Meeting> missing = new ArrayList<>();
        for (FivePoints.Meeting want : expected) {
            RouteMeeting hit = null;
            for (RouteMeeting candidate : found) {
                if (want.matches(candidate)) {
                    hit = candidate;
                    break;
                }
            }
            if (hit == null) {
                missing.add(want);
            } else {
                found.remove(hit);
            }
        }
        out.add(String.format(Locale.ROOT, "junctions: %d of %d as designed",
                expected.size() - missing.size(), expected.size()));
        for (FivePoints.Meeting want : missing) {
            out.add("  missing: " + want.describe());
        }
        for (RouteMeeting extra : found) {
            out.add("  nobody asked for: " + extra.describe());
        }
    }

    // ------------------------------------------------------------------ does it carry a railway

    private static void coverage(ServerLevel level, List<Alignment> routes, int floorY,
                                 List<String> out) {
        if (!RailCompat.trackGraphAvailable()) {
            out.add("Immersive Railroading is not installed, so there is no track graph to ask");
            return;
        }
        out.add("track, over the stretch each line lays itself:");
        List<RouteMeeting> meetings = RouteMeetings.find(routes);
        int complete = 0;
        for (Alignment route : routes) {
            Centreline line = route.compile().centreline();
            double head = junctionLead(meetings, route, RouteMeeting.End.START);
            double tail = junctionLead(meetings, route, RouteMeeting.End.FINISH);
            IrStock.Coverage coverage = IrStock.check(level, line, floorY, STEP,
                    head, line.length() - tail);
            if (coverage.complete()) {
                complete++;
            }
            out.add(String.format(Locale.ROOT, "  %-14s %s%s", route.name(), coverage.describe(),
                    head + tail > 0.0
                            ? String.format(Locale.ROOT, "  (%.0f blocks of it are junction)",
                                    head + tail)
                            : ""));
        }
        out.add(String.format(Locale.ROOT, "  %d of %d route(s) carry track end to end",
                complete, routes.size()));
    }

    /**
     * How much of one end of a route belongs to a junction rather than to the line.
     *
     * <p>A line that meets another one lays no track over its last stretch, because that stretch is the
     * junction and the junction is one piece of track carrying the whole connection. Counting it against
     * the line reports a hole in a railway that is exactly as it was meant to be built.</p>
     */
    private static double junctionLead(List<RouteMeeting> meetings, Alignment route,
                                       RouteMeeting.End end) {
        for (RouteMeeting meeting : meetings) {
            RouteMeeting.Side mine = meeting.sideOf(route.id());
            if (mine == null || mine.end() != end) {
                continue;
            }
            if (meeting.kind() == RouteMeeting.Kind.JOINT
                    || (meeting.kind() == RouteMeeting.Kind.TURNOUT && meeting.branch() == mine)) {
                return com.wf.wflib.rail.RailConfig.TURNOUT_LEAD.get();
            }
        }
        return 0.0;
    }

    // ------------------------------------------------------------------ does a joint carry a train

    /**
     * Run a train at each junction from both sides and say whether it came out the other one.
     *
     * <p>The question a network is made of, and the one nothing else here asks. Coverage is a question
     * about one route: both halves of a junction can be at a hundred per cent and the junction still be
     * two railways that happen to touch. This stands a train a dozen blocks short of the meeting,
     * points it at it, and reports which line it is on when it stops - so a joint that does not join
     * reads as a refusal with a place in it, rather than as two perfect routes.</p>
     */
    private static void joints(ServerLevel level, List<Alignment> routes, int floorY, List<String> out) {
        if (!RailCompat.trackGraphAvailable()) {
            return;
        }
        out.add("junctions, a train run at each from both sides:");
        int through = 0;
        int tried = 0;
        for (RouteMeeting meeting : RouteMeetings.find(routes)) {
            if (meeting.kind() == RouteMeeting.Kind.CROSSING) {
                continue;
            }
            for (boolean first : new boolean[]{true, false}) {
                RouteMeeting.Side mine = first ? meeting.a() : meeting.b();
                RouteMeeting.Side theirs = first ? meeting.b() : meeting.a();
                Alignment route = byId(routes, mine.route());
                Alignment other = byId(routes, theirs.route());
                if (route == null || other == null || !mine.end().terminal()) {
                    continue;
                }
                Centreline line = route.compile().centreline();
                // A route leaves its last lead blocks to the junction and lays no track on them, so a
                // train stood a dozen blocks short of one is stood on nothing. Back off past it.
                double back = APPROACH + com.wf.wflib.rail.RailConfig.TURNOUT_LEAD.get();
                if (line.length() <= back) {
                    continue;
                }
                boolean atFinish = mine.end() == RouteMeeting.End.FINISH;
                AlignElement.Sample from = line.at(atFinish ? line.length() - back : back);
                double heading = atFinish ? from.heading() : from.heading() + Math.PI;
                IrRoll.Ran ran = IrRoll.roll(level, new Vec3(from.x(), floorY, from.z()),
                        VecUtil.toWrongYaw(new Vec3d(Math.cos(heading), 0.0, Math.sin(heading))),
                        back + THROUGH);
                boolean carried = ran.routes().contains(other.name());
                tried++;
                if (carried) {
                    through++;
                }
                out.add(String.format(Locale.ROOT, "  %-14s to %-14s %s", route.name(), other.name(),
                        carried ? "runs through"
                                : String.format(Locale.ROOT,
                                        "stops after %.0f of %.0f blocks at %.0f, %.0f: %s",
                                        ran.blocks(), back, ran.end().x, ran.end().z,
                                        ran.stopped())));
            }
        }
        out.add(String.format(Locale.ROOT, "  %d of %d run(s) carried on to the next line",
                through, tried));
    }

    private static Alignment byId(List<Alignment> routes, java.util.UUID id) {
        for (Alignment route : routes) {
            if (route.id().equals(id)) {
                return route;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ can a train get anywhere

    /**
     * Roll a train out of every stop, along every line that serves it.
     *
     * <p>Twelve rolls, and between them they are the network. A stop a train can leave in two directions
     * on two different lines is an interchange; a joint a train runs straight through has made two routes
     * into one railway; and a roll that stops in open country is a break in the graph, reported with the
     * position to go and look at.</p>
     */
    private static void reachability(ServerLevel level, List<Alignment> routes, Site site, int floorY,
                                     List<String> out) {
        if (!RailCompat.trackGraphAvailable()) {
            return;
        }
        out.add(String.format(Locale.ROOT, "reachability, a train rolled out of each stop along each"
                + " line that serves it, from %.0f blocks inside:", inside()));
        for (FivePoints.Stop stop : FivePoints.stops()) {
            for (Alignment route : routes) {
                Departure departure = leaving(route, site, stop);
                if (departure == null) {
                    continue;
                }
                IrRoll.Ran ran = IrRoll.roll(level,
                        new Vec3(departure.x(), floorY, departure.z()), departure.yaw(), ROLL_LIMIT);
                out.add(String.format(Locale.ROOT, "  %-14s on %-14s %5.0f blocks over %-28s %s%s",
                        stop.name(), route.name(), ran.blocks(),
                        ran.routes().isEmpty() ? "nothing named" : String.join(" then ", ran.routes()),
                        arrival(ran, site), ran.kink()));
            }
        }
    }

    /** Where the roll ended up, named when it is somewhere a person would call a place. */
    private static String arrival(IrRoll.Ran ran, Site site) {
        if (ran.blocks() < 1.0) {
            // Naming the stop it has not left would read as an arrival. What matters is the reason.
            return "and goes nowhere: " + ran.stopped();
        }
        FivePoints.Stop nearest = null;
        double best = ARRIVED;
        for (FivePoints.Stop stop : FivePoints.stops()) {
            double distance = Math.hypot(ran.end().x - site.x(stop), ran.end().z - site.z(stop));
            if (distance < best) {
                best = distance;
                nearest = stop;
            }
        }
        return nearest != null ? "and stands at " + nearest.name()
                : String.format(Locale.ROOT, "and stops at %.0f, %.0f: %s",
                        ran.end().x, ran.end().z, ran.stopped());
    }

    /**
     * Minecraft's yaw for leaving this stop along this route, or null when the route does not end here.
     *
     * <p>A route's heading at its finish points into the stop rather than out of it, so that end is
     * turned round. Getting this backwards rolls every train into the buffers and reports a network with
     * nothing connected to anything, which is a believable enough answer to be worth being careful
     * about.</p>
     */
    private static Departure leaving(Alignment route, Site site, FivePoints.Stop stop) {
        Centreline line = route.compile().centreline();
        double inside = inside();
        if (line.isEmpty() || line.length() <= inside) {
            return null;
        }
        double sx = site.x(stop);
        double sz = site.z(stop);
        AlignElement.Sample start = line.at(0.0);
        AlignElement.Sample finish = line.at(line.length());
        AlignElement.Sample from;
        double heading;
        if (Math.hypot(start.x() - sx, start.z() - sz) < SERVES) {
            from = line.at(inside);
            heading = from.heading();
        } else if (Math.hypot(finish.x() - sx, finish.z() - sz) < SERVES) {
            from = line.at(line.length() - inside);
            heading = from.heading() + Math.PI;
        } else {
            return null;
        }
        return new Departure(from.x(), from.z(),
                VecUtil.toWrongYaw(new Vec3d(Math.cos(heading), 0.0, Math.sin(heading))));
    }

    /** Where a train stands at a stop on one route, and which way it points to leave. */
    private record Departure(double x, double z, float yaw) {
    }
}
