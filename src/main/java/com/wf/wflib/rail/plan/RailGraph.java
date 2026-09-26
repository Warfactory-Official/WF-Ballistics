package com.wf.wflib.rail.plan;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * The surveyed network as something a train can be routed over.
 *
 * <p>A route is a line and a network is routes that meet, which {@link RouteMeetings} already works out.
 * What it does not say is where a <em>train</em> may go, and the difference is the whole of this class:
 * a train changes railway at a junction and nowhere else. Two lines that join end to end are one road; a
 * branch leaves its main line at the switch; and two lines that cross are not connected at all, however
 * much they touch. A diamond is the case worth naming, because it looks more like a connection than
 * either of the others and is the only one of the three that is not one.</p>
 *
 * <p>So: <b>places</b> where a train may change route, and <b>legs</b> of route between them. A journey
 * is a path over that, and it comes back as legs rather than as a line on the ground, because a leg is
 * what a machine builds and what a train runs over.</p>
 *
 * <p>No world in it. A network is a property of the survey, and being able to ask whether Westport can
 * reach Fordwell without loading a chunk is what makes it answerable in a test.</p>
 */
public final class RailGraph {

    /** How near a node's chainage a meeting has to fall to be that node, in blocks. */
    private static final double SAME_NODE = 2.0;

    /**
     * One place a train may change from one route to another.
     *
     * @param ports the route ends and switch points that meet here
     */
    public record Place(int id, double x, double z, List<Port> ports) {

        /** What lines call at this place, which is the only name a surveyed junction has. */
        public String describe() {
            Set<String> names = new LinkedHashSet<>();
            for (Port port : this.ports) {
                names.add(port.line());
            }
            return String.join(" / ", names);
        }
    }

    /** One route's end of a place: which line, and how far along it. */
    public record Port(UUID route, String line, double chainage) {
    }

    /** A way from one place to another, as the legs a train runs over in order. */
    public record Journey(List<Leg> legs, double length) {

        public static final Journey NOWHERE = new Journey(List.of(), 0.0);

        public boolean isEmpty() {
            return this.legs.isEmpty();
        }

        /** The lines it runs over, in order, for a player reading a dispatch. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            for (Leg leg : this.legs) {
                out.append(out.isEmpty() ? "" : ", ").append(leg.name().isEmpty() ? "unnamed route"
                        : leg.name()).append(String.format(Locale.ROOT, " (%.0f)", leg.length()));
            }
            return out.toString();
        }
    }

    private final List<Place> places = new ArrayList<>();
    /** Every leg out of a place, which is what a walk of the network follows. */
    private final Map<Integer, List<Step>> out = new HashMap<>();
    private final Map<UUID, Centreline> lines = new LinkedHashMap<>();

    private record Step(int to, Leg leg) {
    }

    private RailGraph() {
    }

    /** @return the centreline of one route, compiled once while the graph was built. */
    public Centreline centreline(UUID route) {
        return this.lines.get(route);
    }

    public List<Place> places() {
        return List.copyOf(this.places);
    }

    public static RailGraph of(Collection<Alignment> routes) {
        return of(routes, RouteMeetings.find(routes));
    }

    /**
     * Build the graph.
     *
     * <p>Nodes first, then what joins them, then what runs between them, in that order because each
     * needs the one before it. A route's nodes are its two ends and every switch cut into it; the
     * joining is done by the meetings that a train can actually take; and a leg is then simply the
     * stretch of route between one node and the next.</p>
     */
    public static RailGraph of(Collection<Alignment> routes, List<RouteMeeting> meetings) {
        RailGraph graph = new RailGraph();
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, List<Double>> nodes = new LinkedHashMap<>();
        for (Alignment route : routes) {
            Centreline line = route.compile().centreline();
            if (line.length() <= 0.0) {
                continue;
            }
            graph.lines.put(route.id(), line);
            names.put(route.id(), route.name());
            List<Double> at = new ArrayList<>();
            at.add(0.0);
            at.add(line.length());
            nodes.put(route.id(), at);
        }
        // A switch is a node in the middle of the line it is cut into. Nothing else is: a diamond is
        // two railways passing, and a train at one has no more choice than it had a block earlier.
        for (RouteMeeting meeting : meetings) {
            if (meeting.kind() != RouteMeeting.Kind.TURNOUT) {
                continue;
            }
            RouteMeeting.Side through = meeting.through();
            List<Double> at = nodes.get(through.route());
            if (at != null) {
                at.add(through.chainage());
            }
        }
        Map<UUID, List<Double>> tidy = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Double>> entry : nodes.entrySet()) {
            tidy.put(entry.getKey(), merge(entry.getValue()));
        }

        // One place per node to begin with, then the meetings a train can take are told to join them.
        Map<String, Integer> index = new LinkedHashMap<>();
        Union union = new Union();
        for (Map.Entry<UUID, List<Double>> entry : tidy.entrySet()) {
            for (double chainage : entry.getValue()) {
                index.put(key(entry.getKey(), chainage), union.make());
            }
        }
        for (RouteMeeting meeting : meetings) {
            if (meeting.kind() == RouteMeeting.Kind.CROSSING) {
                continue;
            }
            Integer a = graph.nodeAt(index, tidy, meeting.a());
            Integer b = graph.nodeAt(index, tidy, meeting.b());
            if (a != null && b != null) {
                union.join(a, b);
            }
        }

        // Places, then the legs between them.
        Map<Integer, Integer> seats = new LinkedHashMap<>();
        Map<Integer, List<Port>> ports = new LinkedHashMap<>();
        Map<Integer, double[]> where = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Double>> entry : tidy.entrySet()) {
            Centreline line = graph.lines.get(entry.getKey());
            for (double chainage : entry.getValue()) {
                int root = union.find(index.get(key(entry.getKey(), chainage)));
                Integer held = seats.get(root);
                int seat = held != null ? held : seats.size();
                seats.put(root, seat);
                ports.computeIfAbsent(seat, ignored -> new ArrayList<>())
                        .add(new Port(entry.getKey(), names.get(entry.getKey()), chainage));
                var sample = line.at(chainage);
                where.putIfAbsent(seat, new double[]{sample.x(), sample.z()});
            }
        }
        for (int seat = 0; seat < seats.size(); seat++) {
            double[] at = where.getOrDefault(seat, new double[]{0.0, 0.0});
            graph.places.add(new Place(seat, at[0], at[1], List.copyOf(ports.get(seat))));
        }
        for (Map.Entry<UUID, List<Double>> entry : tidy.entrySet()) {
            List<Double> at = entry.getValue();
            String name = names.get(entry.getKey());
            for (int i = 0; i < at.size() - 1; i++) {
                int from = seats.get(union.find(index.get(key(entry.getKey(), at.get(i)))));
                int to = seats.get(union.find(index.get(key(entry.getKey(), at.get(i + 1)))));
                graph.link(from, to, new Leg(entry.getKey(), name, at.get(i), at.get(i + 1)));
                graph.link(to, from, new Leg(entry.getKey(), name, at.get(i + 1), at.get(i)));
            }
        }
        return graph;
    }

    private void link(int from, int to, Leg leg) {
        this.out.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Step(to, leg));
    }

    /** Which node of a route a meeting sits at, or null when it sits between two of them. */
    private Integer nodeAt(Map<String, Integer> index, Map<UUID, List<Double>> nodes,
                           RouteMeeting.Side side) {
        List<Double> at = nodes.get(side.route());
        if (at == null) {
            return null;
        }
        Double best = null;
        for (double chainage : at) {
            double away = Math.abs(chainage - side.chainage());
            if (away <= RouteMeetings.END_REACH
                    && (best == null || away < Math.abs(best - side.chainage()))) {
                best = chainage;
            }
        }
        return best == null ? null : index.get(key(side.route(), best));
    }

    /** @return the place nearest a point, or null when nothing is within reach of it. */
    public Place nearest(double x, double z, double reach) {
        Place best = null;
        double nearest = reach * reach;
        for (Place place : this.places) {
            double dx = place.x() - x;
            double dz = place.z() - z;
            double away = dx * dx + dz * dz;
            if (away <= nearest) {
                nearest = away;
                best = place;
            }
        }
        return best;
    }

    /**
     * The shortest way from one place to another.
     *
     * <p>Shortest by ground covered, which for a railway being built is also cheapest: every block of
     * the answer is a block of tunnel somebody pays for. Legs that carry on over the same route in the
     * same direction are run together, so a line crossed by three others comes back as one leg and not
     * as four.</p>
     *
     * @return the legs in order, or {@link Journey#NOWHERE} when there is no way through
     */
    public Journey between(Place from, Place to) {
        if (from == null || to == null) {
            return Journey.NOWHERE;
        }
        if (from.id() == to.id()) {
            return Journey.NOWHERE;
        }
        Map<Integer, Double> cost = new HashMap<>();
        Map<Integer, Step> came = new HashMap<>();
        // The cost travels with the entry rather than being read back out of the map: a heap whose
        // comparator reads a value that changes underneath it is a heap with no ordering at all.
        PriorityQueue<double[]> queue = new PriorityQueue<>(Comparator.comparingDouble(seat -> seat[1]));
        cost.put(from.id(), 0.0);
        queue.add(new double[]{from.id(), 0.0});
        Set<Integer> done = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            int at = (int) queue.poll()[0];
            if (!done.add(at)) {
                continue;
            }
            if (at == to.id()) {
                break;
            }
            for (Step step : this.out.getOrDefault(at, List.of())) {
                double next = cost.getOrDefault(at, Double.MAX_VALUE) + step.leg().length();
                if (next < cost.getOrDefault(step.to(), Double.MAX_VALUE)) {
                    cost.put(step.to(), next);
                    came.put(step.to(), new Step(at, step.leg()));
                    queue.add(new double[]{step.to(), next});
                }
            }
        }
        if (!came.containsKey(to.id())) {
            return Journey.NOWHERE;
        }
        List<Leg> legs = new ArrayList<>();
        int at = to.id();
        while (at != from.id()) {
            Step step = came.get(at);
            legs.add(0, step.leg());
            at = step.to();
        }
        return new Journey(run(legs), cost.getOrDefault(to.id(), 0.0));
    }

    /** Consecutive legs of one route going one way are one leg. */
    private static List<Leg> run(List<Leg> legs) {
        List<Leg> out = new ArrayList<>();
        for (Leg leg : legs) {
            if (!out.isEmpty()) {
                Leg last = out.get(out.size() - 1);
                if (last.route().equals(leg.route()) && Math.abs(last.to() - leg.from()) < SAME_NODE
                        && last.reversed() == leg.reversed()) {
                    out.set(out.size() - 1, new Leg(last.route(), last.name(), last.from(), leg.to()));
                    continue;
                }
            }
            out.add(leg);
        }
        return List.copyOf(out);
    }

    /** Chainages closer together than a node's tolerance are one node. */
    private static List<Double> merge(List<Double> chainages) {
        List<Double> sorted = new ArrayList<>(chainages);
        sorted.sort(Comparator.naturalOrder());
        List<Double> out = new ArrayList<>();
        for (double at : sorted) {
            if (out.isEmpty() || at - out.get(out.size() - 1) > SAME_NODE) {
                out.add(at);
            }
        }
        return out;
    }

    private static String key(UUID route, double chainage) {
        return route + "@" + Math.round(chainage * 100.0);
    }

    /** Disjoint sets, so that three routes ending in one place are one place and not three. */
    private static final class Union {

        private final List<Integer> parent = new ArrayList<>();

        int make() {
            this.parent.add(this.parent.size());
            return this.parent.size() - 1;
        }

        int find(int at) {
            int root = at;
            while (this.parent.get(root) != root) {
                root = this.parent.get(root);
            }
            return root;
        }

        void join(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                this.parent.set(rb, ra);
            }
        }
    }
}
