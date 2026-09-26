package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding where a world's routes meet, and telling the three kinds apart.
 *
 * <p>The classification is the part worth testing, because getting it wrong is silent and expensive: a
 * crossing mistaken for a turnout builds a switch in the middle of a main line, and a turnout mistaken
 * for a crossing leaves a branch nobody can be sent down. Both look like track from every angle.</p>
 */
class RouteMeetingsTest {

    private static final UUID RED = UUID.nameUUIDFromBytes("red".getBytes());
    private static final UUID BLUE = UUID.nameUUIDFromBytes("blue".getBytes());

    private static Alignment route(String name, UUID owner, double... coordinates) {
        List<AlignPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < coordinates.length; i += 2) {
            points.add(new AlignPoint(coordinates[i], coordinates[i + 1], 80.0));
        }
        return new Alignment(UUID.nameUUIDFromBytes(name.getBytes()), name,
                UUID.randomUUID(), owner, DesignClass.BRANCH, 0x66E0FF, points);
    }

    @Test
    @DisplayName("two lines that pass through each other are a crossing")
    void crossingLines() {
        Alignment main = route("east main", RED, 0.0, 0.0, 400.0, 0.0);
        Alignment other = route("north branch", RED, 200.0, -200.0, 200.0, 200.0);
        List<RouteMeeting> meetings = RouteMeetings.find(List.of(main, other));

        assertEquals(1, meetings.size(), "one crossing, found once: " + meetings);
        RouteMeeting meeting = meetings.get(0);
        assertEquals(RouteMeeting.Kind.CROSSING, meeting.kind());
        assertEquals(200.0, meeting.x(), 2.0);
        assertEquals(0.0, meeting.z(), 2.0);
        assertEquals(90.0, meeting.angle(), 1.0, "they cross square");
        assertEquals(200.0, meeting.sideOf(main.id()).chainage(), 3.0);
        assertEquals(200.0, meeting.sideOf(other.id()).chainage(), 3.0);
    }

    @Test
    @DisplayName("a line that starts on another one is a turnout, and knows which of the two branches")
    void branchIsATurnout() {
        Alignment main = route("east main", RED, 0.0, 0.0, 400.0, 0.0);
        Alignment branch = route("quarry branch", RED, 200.0, 0.0, 300.0, 120.0);
        List<RouteMeeting> meetings = RouteMeetings.find(List.of(main, branch));

        assertEquals(1, meetings.size(), "one junction: " + meetings);
        RouteMeeting meeting = meetings.get(0);
        assertEquals(RouteMeeting.Kind.TURNOUT, meeting.kind());
        assertNotNull(meeting.branch());
        assertEquals(branch.id(), meeting.branch().route(), "the branch is the one that ends there");
        assertEquals(main.id(), meeting.through().route());
        assertEquals(200.0, meeting.through().chainage(), 4.0);
        assertTrue(meeting.angle() > 20.0 && meeting.angle() < 70.0,
                "it leaves at a real angle: " + meeting.angle());
    }

    @Test
    @DisplayName("two lines that finish on each other are a joint, not a junction")
    void endToEndIsAJoint() {
        List<RouteMeeting> meetings = RouteMeetings.find(List.of(
                route("west section", RED, 0.0, 0.0, 200.0, 0.0),
                route("east section", RED, 200.0, 0.0, 400.0, 0.0)));

        assertEquals(1, meetings.size(), "one joint: " + meetings);
        assertEquals(RouteMeeting.Kind.JOINT, meetings.get(0).kind());
    }

    @Test
    @DisplayName("lines that never come near each other do not meet")
    void separateLinesDoNotMeet() {
        assertTrue(RouteMeetings.find(List.of(
                route("east main", RED, 0.0, 0.0, 400.0, 0.0),
                route("far branch", RED, 0.0, 500.0, 400.0, 500.0))).isEmpty());
    }

    @Test
    @DisplayName("two lines running alongside each other are not a crossing however close they are")
    void parallelLinesDoNotCross() {
        assertTrue(RouteMeetings.find(List.of(
                route("up line", RED, 0.0, 0.0, 400.0, 0.0),
                route("down line", RED, 0.0, 6.0, 400.0, 6.0))).isEmpty(),
                "a double track railway is not one long junction");
    }

    @Test
    @DisplayName("a meeting between two factions' routes knows it is not one faction's to build")
    void ownershipIsCarried() {
        List<RouteMeeting> meetings = RouteMeetings.find(List.of(
                route("east main", RED, 0.0, 0.0, 400.0, 0.0),
                route("quarry branch", BLUE, 200.0, 0.0, 300.0, 120.0)));

        assertEquals(1, meetings.size());
        assertTrue(!meetings.get(0).sameOwner(),
                "a switch into another faction's main line is theirs to build, not ours");
    }

    @Test
    @DisplayName("the chainages a meeting reports are where track has to be joined")
    void breaksAreTheChainages() {
        Alignment main = route("east main", RED, 0.0, 0.0, 400.0, 0.0);
        Alignment branch = route("quarry branch", RED, 200.0, 0.0, 300.0, 120.0);
        List<RouteMeeting> meetings = RouteMeetings.find(List.of(main, branch));

        List<Double> breaks = RouteMeetings.breaksOn(meetings, main.id());
        assertEquals(1, breaks.size());
        assertEquals(200.0, breaks.get(0), 4.0);
        assertTrue(RouteMeetings.breaksOn(meetings, UUID.randomUUID()).isEmpty(),
                "a route with no meetings has no joins forced on it");
    }
}
