package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a position in the world into a distance along a route.
 *
 * <p>The half of {@link AlignmentProgress} that can be tested without a server, and the half with the
 * arithmetic in it. A track layer knows only where it put a rail, so if this is wrong the build record
 * is written against the wrong part of the route and the map reports progress somewhere nobody is
 * working.</p>
 */
class AlignmentProgressTest {

    /** A dogleg, so the answer is not simply the x coordinate. */
    private static Alignment dogleg() {
        return new Alignment(UUID.randomUUID(), "north main", UUID.randomUUID(), null,
                DesignClass.MAIN, 0x66E0FF, List.of(
                        new AlignPoint(0.0, 0.0, 200.0),
                        new AlignPoint(1000.0, 0.0, 200.0),
                        new AlignPoint(2000.0, 1000.0, 200.0)));
    }

    @Test
    @DisplayName("a position on the route gives its distance from the start")
    void findsChainageAlongAStraight() {
        Alignment route = dogleg();
        assertEquals(0.0, AlignmentProgress.chainageAt(route, 0.0, 0.0, 8.0), 8.0);
        assertEquals(400.0, AlignmentProgress.chainageAt(route, 400.0, 0.0, 8.0), 8.0,
                "400 blocks along the first leg is chainage 400");
    }

    @Test
    @DisplayName("a position off the route is not on it")
    void rejectsPositionsOffTheRoute() {
        Alignment route = dogleg();
        assertTrue(Double.isNaN(AlignmentProgress.chainageAt(route, 400.0, 500.0, 8.0)),
                "500 blocks to the side is not a rail on this line");
    }

    @Test
    @DisplayName("chainage past the corner accounts for the curve, not the straight-line distance")
    void curveShortensTheRoute() {
        Alignment route = dogleg();
        double total = route.compile().centreline().length();
        double atEnd = AlignmentProgress.chainageAt(route, 2000.0, 1000.0, 16.0);
        assertEquals(total, atEnd, 16.0);
        // The PI-to-PI distance is 1000 + 1414; the curve cuts the corner, so the route is shorter.
        assertTrue(total < 2414.0, "a curve cuts the corner: " + total);
    }

    @Test
    @DisplayName("the answer walks forward as a machine does, and stays right when it doubles back")
    void localSearchDoesNotStrand() {
        Alignment route = dogleg();
        double previous = -1.0;
        for (double x = 0.0; x <= 900.0; x += 50.0) {
            double at = AlignmentProgress.chainageAt(route, x, 0.0, 8.0);
            assertTrue(at > previous, "chainage advances with the railhead at x=" + x);
            previous = at;
        }
        // Back to the start in one jump: the cached local window must not stop it being found.
        assertEquals(0.0, AlignmentProgress.chainageAt(route, 0.0, 0.0, 8.0), 8.0);
    }

    @Test
    @DisplayName("a re-aligned route is projected against its new shape, not the cached old one")
    void cacheFollowsTheRevision() {
        UUID id = UUID.randomUUID();
        Alignment first = new Alignment(id, "a", null, null, DesignClass.MAIN, 0, List.of(
                new AlignPoint(0.0, 0.0, 200.0), new AlignPoint(1000.0, 0.0, 200.0)),
                RouteStatus.PLANNED, BuildProgress.NONE, new Alignment.Revision(1, null, 0L));
        assertEquals(500.0, AlignmentProgress.chainageAt(first, 500.0, 0.0, 8.0), 8.0);

        // Same id, moved 500 blocks north, one revision later.
        Alignment moved = new Alignment(id, "a", null, null, DesignClass.MAIN, 0, List.of(
                new AlignPoint(0.0, 500.0, 200.0), new AlignPoint(1000.0, 500.0, 200.0)),
                RouteStatus.PLANNED, BuildProgress.NONE, new Alignment.Revision(2, null, 0L));
        assertTrue(Double.isNaN(AlignmentProgress.chainageAt(moved, 500.0, 0.0, 8.0)),
                "the old centreline must not answer for the new one");
        assertEquals(500.0, AlignmentProgress.chainageAt(moved, 500.0, 500.0, 8.0), 8.0);
    }
}
