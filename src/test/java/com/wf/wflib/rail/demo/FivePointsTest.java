package com.wf.wflib.rail.demo;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.build.JunctionCurve;
import com.wf.wflib.rail.build.TrackPieces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the Five Points network, asserted without a game.
 *
 * <p>The network is only worth deploying if it still contains the cases it was drawn for, and every one
 * of those is a property of six numbers somebody could nudge by fifty blocks while tidying. Running the
 * real {@link RouteMeetings} over the real alignments here means the table in {@link FivePoints} and the
 * railway it describes cannot drift apart quietly.</p>
 */
class FivePointsTest {

    private static final UUID AUTHOR = UUID.nameUUIDFromBytes("surveyor".getBytes());

    private static List<Alignment> network() {
        return FivePoints.alignments(AUTHOR, null, FivePoints.ORIGIN_X, FivePoints.ORIGIN_Z);
    }

    private static Centreline centreline(String name) {
        for (Alignment route : network()) {
            if (route.name().equals(name)) {
                return route.compile().centreline();
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    @DisplayName("the network is nine routes over five stops and a junction")
    void shape() {
        assertEquals(6, FivePoints.stops().size(), "five stops and the junction between two routes");
        assertEquals(9, FivePoints.routes().size());
        for (Alignment route : network()) {
            assertFalse(route.compile().centreline().isEmpty(), route.name() + " compiles to nothing");
        }
        assertEquals(3034.0, FivePoints.length(), 10.0, "about 3000 blocks of railway to dig");
    }

    @Test
    @DisplayName("every meeting the network is drawn for is there, and nothing else is")
    void meetings() {
        List<RouteMeeting> found = RouteMeetings.find(network());
        List<FivePoints.Meeting> expected = FivePoints.expected();

        List<RouteMeeting> unmatched = new ArrayList<>(found);
        for (FivePoints.Meeting want : expected) {
            RouteMeeting hit = null;
            for (RouteMeeting candidate : unmatched) {
                if (want.matches(candidate)) {
                    hit = candidate;
                    break;
                }
            }
            assertNotNull(hit, "missing: " + want.describe() + "\nfound: " + describe(found));
            unmatched.remove(hit);
        }
        assertTrue(unmatched.isEmpty(), "meetings nobody asked for: " + describe(unmatched));
        assertEquals(expected.size(), found.size());
    }

    @Test
    @DisplayName("the crossings span a spread of angles, with one shallow enough to be hard")
    void crossingAngles() {
        List<Double> angles = new ArrayList<>();
        for (RouteMeeting meeting : RouteMeetings.find(network())) {
            if (meeting.kind() == RouteMeeting.Kind.CROSSING) {
                angles.add(meeting.angle());
            }
        }
        angles.sort(Double::compare);
        assertEquals(5, angles.size());
        assertTrue(angles.get(0) < 30.0, "no shallow crossing to be hard about: " + angles);
        assertTrue(angles.get(angles.size() - 1) > 80.0, "no square crossing as a control: " + angles);
        // The two near seventy-nine degrees are the deliberate pair ten blocks apart on the Eastern
        // Line, which tests the piece joins rather than the angle, so they are allowed to be alike.
        assertEquals(2, angles.stream().filter(a -> a > 78.0 && a < 79.0).count());
    }

    @Test
    @DisplayName("two crossings sit ten blocks apart on the Eastern Line")
    void tightestPair() {
        UUID eastern = FivePoints.route("Eastern Line").id();
        List<Double> breaks = RouteMeetings.breaksOn(RouteMeetings.on(network(), eastern), eastern);
        double tightest = Double.MAX_VALUE;
        for (int i = 1; i < breaks.size(); i++) {
            tightest = Math.min(tightest, breaks.get(i) - breaks.get(i - 1));
        }
        assertEquals(9.7, tightest, 1.0, "the tight pair has moved: " + breaks);
        // Anything closer than the merge distance is one meeting found twice rather than two meetings,
        // so this really is about as tight as a pair of crossings gets.
        assertTrue(tightest > RouteMeetings.TOUCH, "closer than this and they fold into one meeting");
        assertEquals(4, breaks.size(),
                "the Eastern Line carries a stop at each end and two crossings: " + breaks);
    }

    @Test
    @DisplayName("track is split at every meeting, and no piece is squeezed out of existence")
    void piecesSplitAtMeetings() {
        for (Alignment route : network()) {
            Centreline line = route.compile().centreline();
            List<Double> breaks = RouteMeetings.breaksOn(
                    RouteMeetings.on(network(), route.id()), route.id());
            TrackPieces pieces = TrackPieces.along(line, 24.0, 0.0, 0.0, breaks);
            assertFalse(pieces.isEmpty(), route.name() + " has no track on it");
            for (TrackPieces.Piece piece : pieces.pieces()) {
                assertTrue(piece.length() >= 1.0,
                        route.name() + " has a piece " + piece.length() + " blocks long");
                for (double meeting : breaks) {
                    // A meeting within MIN_BREAK_SPAN of an end is deliberately not split at: the piece
                    // it would cut off is shorter than its own anchor's reach, and an anchor inside its
                    // neighbour's sleepers deletes one of the two. Everything else has to be split.
                    if (meeting < 8.0 || meeting > line.length() - 8.0) {
                        continue;
                    }
                    boolean across = piece.from() < meeting - 0.5 && piece.to() > meeting + 0.5;
                    assertFalse(across, route.name() + " lays a piece across the meeting at " + meeting
                            + ": its anchor would sit in ground another railway shares");
                }
            }
        }
    }

    @Test
    @DisplayName("every stop is a through station, not a place a train has to reverse at")
    void everyJointIsAJunction() {
        // The property that makes this a railway rather than a drawing, and the one whose absence cost
        // a whole deployment to find. Two routes ending at the same point meet at some angle, and if
        // that angle is a reversal no curve can join them: a train runs down one line, arrives, and the
        // other line goes back the way it came. It reads exactly like a broken junction and is not one.
        List<Alignment> network = network();
        int joints = 0;
        for (RouteMeeting meeting : RouteMeetings.find(network)) {
            if (meeting.kind() != RouteMeeting.Kind.JOINT) {
                continue;
            }
            joints++;
            JunctionCurve curve = JunctionCurve.between(centreline(meeting.a().name()),
                    centreline(meeting.b().name()), meeting, 14.0);
            assertNotNull(curve, meeting.describe() + " is too short to be joined");
            assertTrue(Math.abs(curve.deflection()) < 45.0,
                    String.format("%s is a %.0f degree corner, which is not a junction a train can run"
                            + " through", meeting.describe(), Math.abs(curve.deflection())));
        }
        assertEquals(6, joints, "five stops on the loop and Moor Junction");
    }

    @Test
    @DisplayName("at every stop one route ends and the next begins")
    void stopsAreHandedOver() {
        Map<String, List<String>> starting = new HashMap<>();
        Map<String, List<String>> finishing = new HashMap<>();
        for (FivePoints.Route route : FivePoints.routes()) {
            List<double[]> points = route.points();
            FivePoints.Stop head = stopAt(points.get(0)[0], points.get(0)[1]);
            FivePoints.Stop tail = stopAt(points.get(points.size() - 1)[0],
                    points.get(points.size() - 1)[1]);
            if (head != null) {
                starting.computeIfAbsent(head.name(), key -> new ArrayList<>()).add(route.name());
            }
            if (tail != null) {
                finishing.computeIfAbsent(tail.name(), key -> new ArrayList<>()).add(route.name());
            }
        }
        for (FivePoints.Stop stop : FivePoints.stops()) {
            if (stop == FivePoints.MOOR_JUNCTION) {
                // The one exception, and the reason it is in the network: both routes were surveyed
                // towards it, so only sleepers meet there and nothing starts in that block.
                assertEquals(null, starting.get(stop.name()));
                assertEquals(2, finishing.get(stop.name()).size());
                continue;
            }
            assertEquals(1, starting.getOrDefault(stop.name(), List.of()).size(),
                    stop.name() + " should have exactly one route leaving it");
            assertEquals(1, finishing.getOrDefault(stop.name(), List.of()).size(),
                    stop.name() + " should have exactly one route arriving at it");
        }
    }

    @Test
    @DisplayName("every stop is served by two routes, so a network is what this is")
    void everyStopIsServed() {
        Map<String, Integer> served = new HashMap<>();
        for (FivePoints.Route route : FivePoints.routes()) {
            for (double[] point : List.of(route.points().get(0),
                    route.points().get(route.points().size() - 1))) {
                FivePoints.Stop at = stopAt(point[0], point[1]);
                if (at != null) {
                    served.merge(at.name(), 1, Integer::sum);
                }
            }
        }
        for (FivePoints.Stop stop : FivePoints.stops()) {
            assertEquals(2, served.getOrDefault(stop.name(), 0),
                    stop.name() + " is not served by two routes");
        }
    }

    private static FivePoints.Stop stopAt(double x, double z) {
        for (FivePoints.Stop stop : FivePoints.stops()) {
            if (Math.hypot(stop.x() - x, stop.z() - z) < 1.0) {
                return stop;
            }
        }
        return null;
    }

    private static String describe(List<RouteMeeting> meetings) {
        StringBuilder out = new StringBuilder();
        for (RouteMeeting meeting : meetings) {
            out.append("\n  ").append(meeting.describe());
        }
        return out.toString();
    }
}
