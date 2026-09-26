package com.wf.wflib.rail.plan;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.demo.FivePoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a train may actually go on the Five Points network.
 *
 * <p>Coverage says there is track under a surveyed line, and every check written before this one said
 * some version of that. None of them can say whether a train gets from one named place to another,
 * which is the only question a network raises that a route does not, and the answer turns on one rule
 * that is easy to get backwards: <b>two lines that cross are not joined.</b> A diamond looks more like
 * a connection than a joint does and is the one of the three that is not one.</p>
 */
class RailGraphTest {

    private static final UUID AUTHOR = UUID.nameUUIDFromBytes("surveyor".getBytes());

    private static List<Alignment> network() {
        return FivePoints.alignments(AUTHOR, null, 0, 0);
    }

    private static RailGraph graph() {
        return RailGraph.of(network());
    }

    private static RailGraph.Place at(RailGraph graph, String stop) {
        FivePoints.Stop place = FivePoints.stop(stop);
        return graph.nearest(place.x(), place.z(), 32.0);
    }

    private static RailGraph.Journey from(String from, String to) {
        RailGraph graph = graph();
        return graph.between(at(graph, from), at(graph, to));
    }

    @Test
    @DisplayName("every route end and every switch is a place, and a joint is one place not two")
    void places() {
        RailGraph graph = graph();
        // Nine routes have eighteen ends. Five of the loop's joints pair them off, as does Moor
        // Junction, so eighteen ends become twelve places.
        assertEquals(12, graph.places().size());
        for (String stop : List.of("Westport", "Northgate", "Eastfield", "Southbank", "Fordwell")) {
            RailGraph.Place place = at(graph, stop);
            assertNotNull(place, stop + " has no place on the network");
            assertEquals(2, place.ports().size(), stop + " is where two lines join end to end");
        }
    }

    @Test
    @DisplayName("a train can be routed right round the loop, and the way back is the way out reversed")
    void roundTheLoop() {
        List<String> loop = List.of("Westport", "Northgate", "Eastfield", "Southbank", "Fordwell");
        for (String from : loop) {
            for (String to : loop) {
                if (from.equals(to)) {
                    continue;
                }
                RailGraph.Journey out = from(from, to);
                RailGraph.Journey back = from(to, from);
                assertFalse(out.isEmpty(), "no way from " + from + " to " + to);
                assertEquals(out.length(), back.length(), 0.5, from + " and " + to + " disagree");
                assertEquals(out.legs().size(), back.legs().size());
                for (int i = 0; i < out.legs().size(); i++) {
                    Leg there = out.legs().get(i);
                    Leg home = back.legs().get(back.legs().size() - 1 - i);
                    assertEquals(there.route(), home.route());
                    assertEquals(there.from(), home.to(), 1.0E-6);
                    assertEquals(there.to(), home.from(), 1.0E-6);
                }
            }
        }
    }

    @Test
    @DisplayName("the next stop round the loop is one leg, and half of those run against the survey")
    void neighbours() {
        RailGraph.Journey out = from("Westport", "Fordwell");
        assertEquals(1, out.legs().size());
        assertTrue(out.legs().get(0).reversed(),
                "the Western Line was surveyed from Fordwell, so Westport to Fordwell runs backwards");
        RailGraph.Journey other = from("Fordwell", "Westport");
        assertEquals(1, other.legs().size());
        assertFalse(other.legs().get(0).reversed());
    }

    @Test
    @DisplayName("two stops apart is two legs and neither is a part of a route")
    void twoLegs() {
        RailGraph.Journey out = from("Westport", "Eastfield");
        assertEquals(2, out.legs().size());
        for (Leg leg : out.legs()) {
            assertEquals(Math.abs(leg.to() - leg.from()), leg.length(), 1.0E-9);
            assertTrue(leg.length() > 200.0, "each leg is a whole side of the loop");
        }
        assertEquals(out.legs().get(0).length() + out.legs().get(1).length(), out.length(), 0.5);
    }

    @Test
    @DisplayName("a diamond is not a way through: the Moor line crosses the loop and joins none of it")
    void crossingIsNotAConnection() {
        RailGraph graph = graph();
        RailGraph.Place moor = at(graph, "Moor Junction");
        assertNotNull(moor, "Moor Junction is where the two Moor lines join end to end");
        assertEquals(2, moor.ports().size());
        for (String stop : List.of("Westport", "Northgate", "Eastfield", "Southbank", "Fordwell")) {
            assertTrue(graph.between(moor, at(graph, stop)).isEmpty(),
                    "the Moor line only crosses the loop at " + stop + ", so no train can turn onto it");
        }
    }

    @Test
    @DisplayName("a switch is a place in the middle of the line it is cut into")
    void turnoutSplitsItsMainLine() {
        RailGraph graph = graph();
        RailGraph.Place found = null;
        for (RailGraph.Place place : graph.places()) {
            if (place.describe().contains("Quarry Branch") && place.ports().size() == 2) {
                found = place;
            }
        }
        assertNotNull(found, "the Quarry Branch leaves the Trunk Line at a switch");
        assertTrue(found.describe().contains("Trunk Line"));
        // The Trunk's own port there is neither of its ends: the switch is somewhere down the middle.
        for (RailGraph.Port port : found.ports()) {
            if (port.line().equals("Trunk Line")) {
                assertTrue(port.chainage() > 10.0, "the switch is not the Trunk Line's start");
            }
        }
    }

    @Test
    @DisplayName("running onto a branch takes part of the main line and then the branch backwards")
    void partialLegOntoABranch() {
        RailGraph graph = graph();
        RailGraph.Place trunk = null;
        RailGraph.Place quarry = null;
        for (RailGraph.Place place : graph.places()) {
            if (place.ports().size() != 1) {
                continue;
            }
            if (place.ports().get(0).line().equals("Trunk Line") && place.ports().get(0).chainage() <= 1.0) {
                trunk = place;
            }
            if (place.ports().get(0).line().equals("Quarry Branch")) {
                quarry = place;
            }
        }
        assertNotNull(trunk);
        assertNotNull(quarry);
        RailGraph.Journey out = graph.between(trunk, quarry);
        assertEquals(2, out.legs().size());
        Leg main = out.legs().get(0);
        Leg branch = out.legs().get(1);
        assertEquals("Trunk Line", main.name());
        assertFalse(main.reversed());
        assertTrue(main.length() < graph.centreline(main.route()).length() - 10.0,
                "only as far as the switch, not the whole Trunk Line");
        assertEquals("Quarry Branch", branch.name());
        assertTrue(branch.reversed(), "the branch ends on the Trunk, so it is run from its far end");
        assertEquals(0.0, branch.to(), 1.0E-9);
    }

    @Test
    @DisplayName("a place with nothing surveyed near it is not on the network")
    void nowhere() {
        assertNull(graph().nearest(100000.0, 100000.0, 32.0));
    }

    private static void assertNull(Object value) {
        assertTrue(value == null, "expected nothing, got " + value);
    }
}
