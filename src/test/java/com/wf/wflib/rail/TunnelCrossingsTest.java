package com.wf.wflib.rail;

import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.demo.FivePoints;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A branch driven off a main line must not dig the main line's rails out from under it.
 *
 * <p>The failure this exists to stop is silent and expensive. The branch finishes, reports a hundred
 * per cent, and the main line it left from has lost the two pieces either side of the junction: forty
 * odd blocks of railway, gone, with the only sign a coverage check on a route nobody was building. It
 * cost a live run to find and it is pure geometry, so it belongs here.</p>
 */
class TunnelCrossingsTest {

    private static final TunnelProfile STANDARD = TunnelProfile.parse("standard", List.of(
            "@torch 8",
            "########",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "#L....L#",
            "########"));

    /** A main line running east along z = 0.5, as a surveyed route would be. */
    private static Centreline main() {
        AlignElement.Tangent tangent = new AlignElement.Tangent(0.5, 0.5, 300.5, 0.5);
        return new Centreline(List.of(tangent), tangent.length());
    }

    /** A branch leaving it at chainage 180, at about 27 degrees. */
    private static Centreline branch() {
        AlignElement.Tangent tangent = new AlignElement.Tangent(180.5, 0.5, 300.5, 60.5);
        return new Centreline(List.of(tangent), tangent.length());
    }

    private static CarveVolume branchBore() {
        Centreline line = branch();
        return new ProfileVolume(
                TunnelBuilder.corridor(line, 0.0, line.length(), STANDARD, 0), STANDARD,
                TunnelProfile.Kind.BORE, 0, false);
    }

    /** Everything of the main line the branch is told to leave alone. */
    private static CarveVolume shared() {
        Centreline line = main();
        return TunnelCrossings.volume(
                List.of(new TunnelCrossings.Stretch(line, 0.0, line.length())), STANDARD, 0);
    }

    @Test
    @DisplayName("the branch's own bore really does reach the main line's rails, so the rule is needed")
    void thereIsSomethingToProtect() {
        CarveVolume bore = branchBore();
        boolean reaches = false;
        for (int x = 156; x <= 204; x++) {
            if (bore.contains(x, 0, 0) || bore.contains(x, 0, 1)) {
                reaches = true;
            }
        }
        assertTrue(reaches, "if the branch never reached the main line's floor there would be no bug");
    }

    @Test
    @DisplayName("boring the branch leaves every cell of the main line's rail course alone")
    void boringSparesTheRailCourse() {
        CarveVolume shared = shared();
        CarveVolume cutting = TunnelCrossings.boring(branchBore(), shared, 0);
        CarveVolume mainBore = shared;
        int spared = 0;
        for (int x = 140; x <= 220; x++) {
            for (int z = -6; z <= 6; z++) {
                if (!mainBore.contains(x, 0, z)) {
                    continue;
                }
                spared++;
                assertFalse(cutting.contains(x, 0, z),
                        "the branch would cut the main line's rail course at " + x + ", 0, " + z);
            }
        }
        assertTrue(spared > 200, "the two should share real ground: " + spared);
    }

    @Test
    @DisplayName("lining the branch does not wall the main line off at the junction")
    void liningStopsAtTheOtherTunnel() {
        Centreline line = branch();
        CarveVolume shell = TunnelCrossings.lining(new ProfileVolume(
                TunnelBuilder.corridor(line, 0.0, line.length(), STANDARD, 0), STANDARD,
                TunnelProfile.Kind.LINING, 0, false), shared());
        CarveVolume mainBore = shared();
        for (int x = 140; x <= 220; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = -6; z <= 6; z++) {
                    if (mainBore.contains(x, y, z)) {
                        assertFalse(shell.contains(x, y, z),
                                "the branch would wall the main line off at " + x + ", " + y + ", " + z);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("away from the junction the branch is lined and bored exactly as it would be alone")
    void nothingElseChanges() {
        Centreline line = branch();
        CarveVolume plain = new ProfileVolume(
                TunnelBuilder.corridor(line, 0.0, line.length(), STANDARD, 0), STANDARD,
                TunnelProfile.Kind.BORE, 0, false);
        CarveVolume cutting = TunnelCrossings.boring(plain, shared(), 0);
        int checked = 0;
        for (int x = 240; x <= 300; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = 20; z <= 60; z++) {
                    if (plain.contains(x, y, z)) {
                        checked++;
                        assertTrue(cutting.contains(x, y, z),
                                "the rest of the branch still gets dug: " + x + ", " + y + ", " + z);
                    }
                }
            }
        }
        assertTrue(checked > 200, "the far half of the branch is a real tunnel: " + checked);
    }

    // ------------------------------------------------------------------ who is in the way, and when

    private static final UUID SURVEYOR = UUID.nameUUIDFromBytes("surveyor".getBytes());

    /** Nobody is digging anything. */
    private static final Function<UUID, BuildProgress.Span> IDLE = route -> null;

    private static List<Alignment> network() {
        return FivePoints.alignments(SURVEYOR, null, FivePoints.ORIGIN_X, FivePoints.ORIGIN_Z);
    }

    private static Function<UUID, Alignment> lookup(List<Alignment> routes) {
        return id -> {
            for (Alignment route : routes) {
                if (route.id().equals(id)) {
                    return route;
                }
            }
            return null;
        };
    }

    /** The same route, reporting that stretch of itself dug and tracked. */
    private static Alignment reporting(Alignment route, double from, double to) {
        return new Alignment(route.id(), route.name(), route.author(), route.ownerFaction(),
                route.designClass(), route.coreColour(), route.points(), route.status(),
                BuildProgress.NONE.with(from, to), route.revision());
    }

    /**
     * A crossing far enough along both routes that a machine can be short of it by a hundred blocks.
     *
     * <p>Taken from the real network rather than invented, so the case being asserted is one that is
     * actually dug when the demo is deployed.</p>
     */
    private static RouteMeeting someCrossing(List<Alignment> routes) {
        for (RouteMeeting meeting : RouteMeetings.find(routes)) {
            if (meeting.kind() == RouteMeeting.Kind.CROSSING && meeting.a().chainage() > 120.0
                    && meeting.b().chainage() > 120.0) {
                return meeting;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a crossing nobody has dug yet is solid ground, and is not exempted")
    void nothingBuiltIsNothingSpared() {
        List<Alignment> routes = network();
        RouteMeeting crossing = someCrossing(routes);
        assertNotNull(crossing, "the network is meant to contain a crossing well along both routes");
        UUID mine = crossing.a().route();
        assertTrue(TunnelCrossings.stretches(mine, RouteMeetings.on(routes, mine), lookup(routes),
                crossing.a().chainage(), crossing.a().chainage(), IDLE).isEmpty(),
                "leaving a hole in a tunnel wall for a railway that is not there is how an aquifer"
                        + " gets in");
    }

    @Test
    @DisplayName("a crossing the other line reports built is exempted")
    void whatIsReportedIsSpared() {
        List<Alignment> routes = new java.util.ArrayList<>(network());
        RouteMeeting crossing = someCrossing(routes);
        assertNotNull(crossing);
        UUID mine = crossing.a().route();
        UUID theirs = crossing.b().route();
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id().equals(theirs)) {
                routes.set(i, reporting(routes.get(i), 0.0, crossing.b().chainage() + 10.0));
            }
        }
        assertEquals(1, TunnelCrossings.stretches(mine, RouteMeetings.on(routes, mine), lookup(routes),
                crossing.a().chainage(), crossing.a().chainage(), IDLE).size());
    }

    @Test
    @DisplayName("a crossing being dug this second is exempted, though nothing is reported yet")
    void theLiveFaceIsSpared() {
        List<Alignment> routes = network();
        RouteMeeting crossing = someCrossing(routes);
        assertNotNull(crossing);
        UUID mine = crossing.a().route();
        UUID theirs = crossing.b().route();
        // The other machine is ten blocks past the crossing and has reported none of it: the build
        // record is written every eight blocks, and eight blocks of walled-off tunnel is a dead line.
        List<TunnelCrossings.Stretch> spared = TunnelCrossings.stretches(mine,
                RouteMeetings.on(routes, mine), lookup(routes), crossing.a().chainage(),
                crossing.a().chainage(),
                route -> route.equals(theirs)
                        ? new BuildProgress.Span(0.0, crossing.b().chainage() + 10.0) : null);
        assertEquals(1, spared.size(), "a machine that is there now is as real as one that has been");
    }

    @Test
    @DisplayName("a machine still a hundred blocks short of the crossing spares nothing there")
    void theLiveFaceIsNotAPromise() {
        List<Alignment> routes = network();
        RouteMeeting crossing = someCrossing(routes);
        assertNotNull(crossing);
        UUID mine = crossing.a().route();
        UUID theirs = crossing.b().route();
        assertTrue(TunnelCrossings.stretches(mine, RouteMeetings.on(routes, mine), lookup(routes),
                crossing.a().chainage(), crossing.a().chainage(),
                route -> route.equals(theirs)
                        ? new BuildProgress.Span(0.0, crossing.b().chainage() - 100.0) : null).isEmpty(),
                "ground the other machine has not reached is still rock");
    }

    @Test
    @DisplayName("a crossing the slice cannot reach is not even looked up")
    void theWindowKeepsItCheap() {
        List<Alignment> routes = new java.util.ArrayList<>(network());
        RouteMeeting crossing = someCrossing(routes);
        assertNotNull(crossing);
        UUID mine = crossing.a().route();
        UUID theirs = crossing.b().route();
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id().equals(theirs)) {
                routes.set(i, reporting(routes.get(i), 0.0, 10000.0));
            }
        }
        List<RouteMeeting> meetings = RouteMeetings.on(routes, mine);
        double far = crossing.a().chainage() + 100.0;
        assertTrue(TunnelCrossings.stretches(mine, meetings, lookup(routes), far, far, IDLE).isEmpty(),
                "a slice a hundred blocks away cannot cut into that crossing");
        assertFalse(TunnelCrossings.stretches(mine, meetings, lookup(routes),
                crossing.a().chainage(), crossing.a().chainage(), IDLE).isEmpty(),
                "and the same crossing is still seen when the face is on it");
    }
}
