package com.wf.wflib.rail.demo;

import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.align.RouteMeeting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Five Points test network: a circle line through five stops, four lines across it, and thirteen
 * places they meet.
 *
 * <p>A railway drawn to be difficult in the exact ways this build has never been asked about, and drawn
 * as a railway rather than as a diagram. That distinction cost a whole deployment to learn. The first
 * version of this network was the five diagonals of a pentagon, which crosses beautifully and is not a
 * railway at all: two diagonals meet at a stop in a <b>V</b>, and a train running down one of them has
 * to <em>reverse</em> to take the other. Measured, it looked exactly like a bug - five perfect lines,
 * every junction as designed, and a train stopping dead at every stop.</p>
 *
 * <p>So the stops are on a <b>circle line</b> now: five arcs, each one ending where the next begins and
 * tangent to it, which is what a through station is. Eastfield is deliberately pushed out of the circle
 * so that one stop is a real junction with a real deflection rather than a straight, and the Moor line
 * is surveyed as two routes meeting in open country so that one joint has no stop on it. Four lines run
 * across the circle to give the crossings: the Trunk from side to side, the two halves of the Moor line,
 * and a Quarry Branch that ends on the Trunk, which is the one turnout.</p>
 *
 * <p>Data only: no world, no Immersive Railroading and no Minecraft in it, so the shape can be asserted
 * in a unit test and deployed by a command from the same table.</p>
 */
public final class FivePoints {

    /** Where the network is laid out when nobody says otherwise. Clear of spawn, easy to type. */
    public static final int ORIGIN_X = 2000;
    public static final int ORIGIN_Z = 2000;

    /** The rail level every route in the network is built at. */
    public static final int FLOOR_Y = 0;

    /** Everything is drawn at one class, so every tunnel that crosses another is the same size as it. */
    public static final DesignClass CLASS = DesignClass.BRANCH;

    /**
     * The radius the circle line's corners are turned at, in blocks.
     *
     * <p>A little inside the circle the stops sit on, so each arc has a short straight at either end of
     * it. A curve that started exactly at a stop would leave the junction there with no tangent to be
     * tangent to, which is a numerically awkward way to build the one thing the network is for.</p>
     */
    public static final double LOOP_RADIUS = 180.0;

    private FivePoints() {
    }

    /** A named place on the network, in the network's own coordinates. */
    public record Stop(String name, double x, double z) {

        public double worldX(double originX) {
            return originX + this.x;
        }

        public double worldZ(double originZ) {
            return originZ + this.z;
        }
    }

    /**
     * One surveyed route.
     *
     * <p>The order of {@link #points} is not cosmetic. It decides which end of a route is its start,
     * and a meeting knows which end of each route it falls on: the junction between two routes is built
     * from the last stretch of one and the first of the other. Both halves of the Moor line are
     * deliberately surveyed <em>towards</em> Moor Junction, so that one joint in the network is two
     * finishes meeting rather than a finish and a start.</p>
     */
    public record Route(String name, LineColour colour, List<double[]> points) {

        public Route {
            points = List.copyOf(points);
        }

        /** Stable, so surveying twice replaces the route rather than drawing a second one over it. */
        public UUID id() {
            return UUID.nameUUIDFromBytes(("wflib:five-points:" + this.name).getBytes());
        }

        public double length() {
            double total = 0.0;
            for (int i = 0; i + 1 < this.points.size(); i++) {
                total += Math.hypot(this.points.get(i + 1)[0] - this.points.get(i)[0],
                        this.points.get(i + 1)[1] - this.points.get(i)[1]);
            }
            return total;
        }
    }

    // ------------------------------------------------------------------ the network

    public static final Stop WESTPORT = new Stop("Westport", -190.2, -61.8);
    public static final Stop NORTHGATE = new Stop("Northgate", 0.0, -200.0);
    /** Pushed out of the circle, so one stop on the loop is a junction with a real deflection in it. */
    public static final Stop EASTFIELD = new Stop("Eastfield", 223.5, -72.6);
    public static final Stop SOUTHBANK = new Stop("Southbank", 117.6, 161.8);
    public static final Stop FORDWELL = new Stop("Fordwell", -117.6, 161.8);
    public static final Stop MOOR_JUNCTION = new Stop("Moor Junction", 0.0, 0.0);

    /** The five stops on the circle, and the junction in open country the Moor line is cut at. */
    public static List<Stop> stops() {
        return List.of(WESTPORT, NORTHGATE, EASTFIELD, MOOR_JUNCTION, SOUTHBANK, FORDWELL);
    }

    public static Stop stop(String name) {
        for (Stop stop : stops()) {
            if (stop.name().equalsIgnoreCase(name)) {
                return stop;
            }
        }
        return null;
    }

    /**
     * The routes, in the order they are meant to be built.
     *
     * <p>The order decides which line arrives second at each crossing, which is the half of a crossing
     * that has to make room for the one already there. Reversing it swaps all six.</p>
     */
    public static List<Route> routes() {
        return List.of(
                // The circle line, five arcs. Each one runs from a stop, round a curve at the tangent
                // intersection of the two stops it joins, to the next stop, so it arrives along the
                // circle and the next route leaves along it: a through station rather than a corner.
                loop("Northern Line", LineColour.GREEN, WESTPORT, -145.3, -200.0, NORTHGATE),
                loop("Eastern Line", LineColour.AMBER, NORTHGATE, 145.3, -200.0, EASTFIELD),
                loop("Harbour Line", LineColour.BLUE, EASTFIELD, 235.1, 76.4, SOUTHBANK),
                loop("Southern Line", LineColour.RED, SOUTHBANK, 0.0, 247.2, FORDWELL),
                loop("Western Line", LineColour.VIOLET, FORDWELL, -235.1, 76.4, WESTPORT),
                // Across it. The Trunk runs right through from one side to the other, which gives two
                // crossings with the circle and one with the Moor line.
                new Route("Trunk Line", LineColour.WHITE, List.of(
                        new double[]{32.8, -357.0}, new double[]{96.5, 90.0, LOOP_RADIUS},
                        new double[]{-60.0, 300.0})),
                // The Moor line, surveyed as two routes that both finish at Moor Junction, so the one
                // joint that is not at a stop has only sleepers in it from either side.
                straight("Moor North", LineColour.MAGENTA, 120.0, -368.0, 0.0, 0.0),
                straight("Moor South", LineColour.CYAN, -150.0, 372.0, 0.0, 0.0),
                // Ends on the Trunk, which is the one turnout, and crosses the circle on its way.
                straight("Quarry Branch", LineColour.BLACK, -90.1, -441.2, 45.4, -267.9));
    }

    public static Route route(String name) {
        for (Route route : routes()) {
            if (route.name().equalsIgnoreCase(name)) {
                return route;
            }
        }
        return null;
    }

    /** One arc of the circle: stop, the tangent intersection the curve is turned at, next stop. */
    private static Route loop(String name, LineColour colour, Stop from, double piX, double piZ,
                              Stop to) {
        return new Route(name, colour, List.of(new double[]{from.x(), from.z()},
                new double[]{piX, piZ, LOOP_RADIUS}, new double[]{to.x(), to.z()}));
    }

    private static Route straight(String name, LineColour colour, double x1, double z1,
                                  double x2, double z2) {
        return new Route(name, colour,
                List.of(new double[]{x1, z1}, new double[]{x2, z2}));
    }

    // ------------------------------------------------------------------ compiling it

    /** Every route as a surveyed alignment, at an origin. */
    public static List<Alignment> alignments(UUID author, UUID faction, double originX, double originZ) {
        List<Alignment> out = new ArrayList<>();
        for (Route route : routes()) {
            out.add(alignment(route, author, faction, originX, originZ));
        }
        return out;
    }

    public static Alignment alignment(Route route, UUID author, UUID faction,
                                      double originX, double originZ) {
        return new Alignment(route.id(), route.name(), author, faction, CLASS, route.colour().rgb(),
                points(route, originX, originZ));
    }

    public static List<AlignPoint> points(Route route, double originX, double originZ) {
        List<AlignPoint> out = new ArrayList<>();
        for (double[] point : route.points()) {
            // A third number on a point is the radius the survey turns that corner at. The circle line
            // needs one: its arcs are what make two routes meet a stop tangent to each other, and the
            // class default would turn far too tightly to reach the next stop along the circle.
            out.add(new AlignPoint(originX + point[0], originZ + point[1],
                    point.length > 2 ? point[2] : CLASS.defaultRadius()));
        }
        return out;
    }

    // ------------------------------------------------------------------ what it is supposed to be

    /**
     * A meeting the network is drawn to contain.
     *
     * @param angle the acute angle between the two lines, in degrees
     */
    public record Meeting(RouteMeeting.Kind kind, String a, String b, double angle, String purpose) {

        public boolean matches(RouteMeeting found) {
            String first = found.a().name();
            String second = found.b().name();
            return found.kind() == this.kind
                    && ((first.equals(this.a) && second.equals(this.b))
                        || (first.equals(this.b) && second.equals(this.a)))
                    && Math.abs(found.angle() - this.angle) <= 1.5;
        }

        public String describe() {
            return String.format(Locale.ROOT, "%-8s %-13s x %-13s %4.0f deg  %s",
                    this.kind.lowerName(), this.a, this.b, this.angle, this.purpose);
        }
    }

    /**
     * The thirteen meetings, each one there for a reason.
     *
     * <p>Kept beside the geometry rather than derived from it, because the point of the network is that
     * it contains these cases: a table computed from the coordinates would agree with whatever the
     * coordinates happened to say after somebody nudged one.</p>
     */
    public static List<Meeting> expected() {
        return List.of(
                crossing("Southern Line", "Moor South", 87.2, "the control, a diamond at right angles"),
                crossing("Eastern Line", "Moor North", 78.7,
                        "ten blocks from the one below it on the Eastern Line: three tunnels inside"
                                + " twenty blocks, and the tightest pair of piece joins there can be,"
                                + " because a join closer than eight blocks is dropped rather than made"),
                crossing("Eastern Line", "Trunk Line", 78.3, "the other half of that pair"),
                crossing("Southern Line", "Trunk Line", 49.5, "middling, and inside a curve on both"),
                crossing("Trunk Line", "Moor North", 26.2,
                        "the shallow one: two six block corridors share fourteen blocks of ground"),
                joint("Northern Line", "Eastern Line", 0.0, "Northgate, a through station on the loop"),
                joint("Northern Line", "Western Line", 0.0, "Westport, a through station on the loop"),
                joint("Eastern Line", "Harbour Line", 27.1,
                        "Eastfield, pushed off the circle so that one stop is a junction with a real"
                                + " deflection in it rather than a straight"),
                joint("Harbour Line", "Southern Line", 0.0, "Southbank, a through station on the loop"),
                joint("Southern Line", "Western Line", 0.0, "Fordwell, a through station on the loop"),
                joint("Moor North", "Moor South", 3.9,
                        "Moor Junction, the one joint with no stop on it, and the only place where both"
                                + " routes were surveyed towards the meeting rather than away from it"),
                new Meeting(RouteMeeting.Kind.TURNOUT, "Quarry Branch", "Trunk Line", 29.9,
                        "the one turnout, out in open country where the branch leaves the Trunk"));
    }

    private static Meeting crossing(String a, String b, double angle, String purpose) {
        return new Meeting(RouteMeeting.Kind.CROSSING, a, b, angle, purpose);
    }

    private static Meeting joint(String a, String b, double angle, String purpose) {
        return new Meeting(RouteMeeting.Kind.JOINT, a, b, angle, purpose);
    }

    /**
     * Total surveyed length, which is roughly the digging this network costs.
     *
     * <p>Compiled rather than added up along the points: the circle line's routes are arcs, and the
     * straight line between their ends is a good deal shorter than the railway.</p>
     */
    public static double length() {
        double total = 0.0;
        for (Route route : routes()) {
            total += alignment(route, null, null, 0.0, 0.0).compile().centreline().length();
        }
        return total;
    }
}
