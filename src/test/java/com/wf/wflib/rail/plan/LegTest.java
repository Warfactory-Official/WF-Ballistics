package com.wf.wflib.rail.plan;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.demo.FivePoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One stretch of one route, told to a machine in the direction the train runs over it.
 *
 * <p>Every one of these is a renumbering, and a renumbering is the sort of thing that is wrong by a
 * whole route's length while every counter still reads plausibly. A leg that reports its progress in
 * its own chainage rather than the survey's would have a line built from the far end reported as built
 * from the near one, and the map would show the wrong half of the railway finished.</p>
 */
class LegTest {

    private static final UUID AUTHOR = UUID.nameUUIDFromBytes("surveyor".getBytes());

    private static List<Alignment> network() {
        return FivePoints.alignments(AUTHOR, null, 0, 0);
    }

    private static Alignment route(String name) {
        for (Alignment route : network()) {
            if (route.name().equals(name)) {
                return route;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    @DisplayName("a leg's chainage and its route's are the same number read from different ends")
    void frames() {
        Alignment route = route("Northern Line");
        double length = route.compile().centreline().length();
        Leg forward = Leg.of(route);
        Leg back = new Leg(route.id(), route.name(), length, 0.0);

        assertEquals(length, forward.length(), 1.0E-6);
        assertEquals(length, back.length(), 1.0E-6);
        assertTrue(back.reversed());
        for (double along = 0.0; along <= length; along += 20.0) {
            assertEquals(along, forward.onRoute(along), 1.0E-6);
            assertEquals(length - along, back.onRoute(along), 1.0E-6);
            assertEquals(along, forward.onLeg(forward.onRoute(along)), 1.0E-6);
            assertEquals(along, back.onLeg(back.onRoute(along)), 1.0E-6);
        }
    }

    @Test
    @DisplayName("a reversed leg runs from the route's far end back towards its start")
    void reversedGround() {
        Alignment route = route("Northern Line");
        Centreline survey = route.compile().centreline();
        Leg back = new Leg(route.id(), route.name(), survey.length(), 0.0);
        Centreline line = back.centreline(survey);
        assertEquals(survey.length(), line.length(), 0.5);
        assertEquals(survey.at(survey.length()).x(), line.at(0.0).x(), 0.05);
        assertEquals(survey.at(survey.length()).z(), line.at(0.0).z(), 0.05);
        assertEquals(survey.at(0.0).x(), line.at(line.length()).x(), 0.05);
        assertEquals(survey.at(0.0).z(), line.at(line.length()).z(), 0.05);
    }

    @Test
    @DisplayName("a part of a route is only that part of it")
    void partial() {
        Alignment route = route("Trunk Line");
        Centreline survey = route.compile().centreline();
        Leg leg = new Leg(route.id(), route.name(), 40.0, 160.0);
        assertEquals(120.0, leg.length(), 0.5);
        Centreline line = leg.centreline(survey);
        assertEquals(survey.at(40.0).x(), line.at(0.0).x(), 0.05);
        assertEquals(survey.at(160.0).x(), line.at(line.length()).x(), 0.05);
    }

    @Test
    @DisplayName("track already there is counted from the end the train arrives at")
    void resumesFromTheNearEnd() {
        Alignment route = route("Northern Line");
        double length = route.compile().centreline().length();
        BuildProgress fromStart = BuildProgress.NONE.with(0.0, 58.0);
        BuildProgress fromEnd = BuildProgress.NONE.with(length - 58.0, length);

        Leg forward = Leg.of(route);
        Leg back = new Leg(route.id(), route.name(), length, 0.0);

        assertEquals(58.0, forward.builtAlong(fromStart), 0.5, "forward, built from the start");
        assertEquals(0.0, forward.builtAlong(fromEnd), 0.5,
                "forward, built from the far end: the train cannot reach any of it");
        assertEquals(58.0, back.builtAlong(fromEnd), 0.5, "backwards, built from the far end");
        assertEquals(0.0, back.builtAlong(fromStart), 0.5, "backwards, built from the start");
    }

    @Test
    @DisplayName("a run of track that stops short of the near end counts for nothing")
    void detachedTrackDoesNotCount() {
        Alignment route = route("Northern Line");
        Leg forward = Leg.of(route);
        // The middle third, with nothing joining it to either end. Track a train cannot get to.
        assertEquals(0.0, forward.builtAlong(BuildProgress.NONE.with(80.0, 160.0)), 1.0E-6);
    }

    @Test
    @DisplayName("a part of a route counts only the track inside it")
    void partialResume() {
        Alignment route = route("Trunk Line");
        Leg leg = new Leg(route.id(), route.name(), 119.0, 323.0);
        assertEquals(31.0, leg.builtAlong(BuildProgress.NONE.with(0.0, 150.0)), 0.5);
        assertEquals(leg.length(), leg.builtAlong(BuildProgress.NONE.with(0.0, 9999.0)), 0.5);
        assertTrue(leg.done(BuildProgress.NONE.with(0.0, 9999.0)));
    }

    @Test
    @DisplayName("what a machine has dug is a span on the survey, not a distance from its start")
    void dug() {
        Alignment route = route("Northern Line");
        double length = route.compile().centreline().length();
        Leg back = new Leg(route.id(), route.name(), length, 0.0);
        BuildProgress.Span span = back.dug(58.0);
        assertEquals(length - 58.0, span.from(), 0.5);
        assertEquals(length, span.to(), 0.5);

        BuildProgress.Span forward = Leg.of(route).dug(58.0);
        assertEquals(0.0, forward.from(), 0.5);
        assertEquals(58.0, forward.to(), 0.5);
    }

    @Test
    @DisplayName("a reversed leg's meetings are renumbered and its ends swap over")
    void meetingsReversed() {
        Alignment route = route("Northern Line");
        double length = route.compile().centreline().length();
        List<RouteMeeting> all = RouteMeetings.on(network(), route.id());
        assertTrue(all.size() >= 2, "the Northern Line meets something at each end");
        Leg back = new Leg(route.id(), route.name(), length, 0.0);
        List<RouteMeeting> moved = back.meetings(all);
        assertEquals(all.size(), moved.size());
        for (int i = 0; i < all.size(); i++) {
            RouteMeeting.Side was = all.get(i).sideOf(route.id());
            RouteMeeting.Side now = moved.get(i).sideOf(route.id());
            assertNotNull(now);
            assertEquals(length - was.chainage(), now.chainage(), 1.0E-6);
            assertEquals(flip(was.end()), now.end(),
                    "a route's start is the near end of a leg that runs the other way");
            // The other route is not this leg's business and is left exactly as it was.
            assertEquals(all.get(i).otherThan(route.id()).chainage(),
                    moved.get(i).otherThan(route.id()).chainage(), 1.0E-9);
        }
    }

    @Test
    @DisplayName("a leg that stops in the middle of a route has not reached the end of one")
    void meetingsOnAPart() {
        Alignment route = route("Northern Line");
        double length = route.compile().centreline().length();
        List<RouteMeeting> all = RouteMeetings.on(network(), route.id());
        // Everything but the last fifty blocks, so the meeting at the far end is now past the leg.
        Leg leg = new Leg(route.id(), route.name(), 0.0, length - 50.0);
        for (RouteMeeting meeting : leg.meetings(all)) {
            RouteMeeting.Side mine = meeting.sideOf(route.id());
            if (mine.chainage() > leg.length()) {
                assertEquals(RouteMeeting.End.NONE, mine.end(),
                        "a meeting beyond the leg is not one of its ends");
            }
        }
        assertTrue(leg.meetings(all).stream()
                        .anyMatch(meeting -> meeting.sideOf(route.id()).end() == RouteMeeting.End.START),
                "the leg still starts where the route did");
    }

    private static RouteMeeting.End flip(RouteMeeting.End end) {
        return switch (end) {
            case START -> RouteMeeting.End.FINISH;
            case FINISH -> RouteMeeting.End.START;
            case NONE -> RouteMeeting.End.NONE;
        };
    }
}
