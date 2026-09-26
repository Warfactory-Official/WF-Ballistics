package com.wf.wflib.rail.build;

import com.wf.wflib.rail.align.AlignCompiler;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.RouteMeeting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve that joins two routes end to end.
 *
 * <p>Asserted on the geometry alone, because the geometry is the half that is wrong in ways nobody sees
 * from inside a tunnel. The rule it encodes is the real one: <b>the lead is chosen and the radius falls
 * out of it</b>, which is how a turnout is specified on a railway, and a joint whose two routes are
 * already in line is the degenerate case and has to come out as a straight rather than as a curve of
 * enormous radius that a cubic cannot represent.</p>
 */
class JunctionCurveTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());

    /** A straight route from one point to another, compiled the way the survey compiles one. */
    private static Centreline line(double x1, double z1, double x2, double z2) {
        return AlignCompiler.compile(List.of(new AlignPoint(x1, z1, 0.0), new AlignPoint(x2, z2, 0.0)),
                DesignClass.BRANCH).centreline();
    }

    /** A joint where the first route finishes and the second starts, which is what a stop is. */
    private static RouteMeeting joint(Centreline a, Centreline b, double x, double z) {
        return new RouteMeeting(
                new RouteMeeting.Side(A, "first", null, a.length(),
                        a.at(a.length()).heading(), RouteMeeting.End.FINISH),
                new RouteMeeting.Side(B, "second", null, 0.0,
                        b.at(0.0).heading(), RouteMeeting.End.START),
                RouteMeeting.Kind.JOINT, x, z, 0.0);
    }

    @Test
    @DisplayName("the lead is chosen and the radius falls out of it")
    void radiusComesFromTheLead() {
        // Two routes meeting at 60 degrees: in along +X, out at 60 degrees off it.
        Centreline in = line(0.0, 0.0, 100.0, 0.0);
        double out = Math.toRadians(60.0);
        Centreline away = line(100.0, 0.0, 100.0 + Math.cos(out) * 100.0, Math.sin(out) * 100.0);
        JunctionCurve curve = JunctionCurve.between(in, away, joint(in, away, 100.0, 0.0), 14.0);

        assertNotNull(curve);
        assertEquals(60.0, Math.abs(curve.deflection()), 0.01);
        assertEquals(14.0 / Math.tan(Math.toRadians(30.0)), curve.radius(), 0.01,
                "the radius of a curve with a 14 block tangent through 60 degrees");
        assertTrue(curve.radius() > 24.0 && curve.radius() < 25.0);
    }

    @Test
    @DisplayName("the curve starts a lead back along each route and points the way they do")
    void endsAreOnTheRoutes() {
        Centreline in = line(0.0, 0.0, 100.0, 0.0);
        Centreline away = line(100.0, 0.0, 200.0, 0.0);
        JunctionCurve curve = JunctionCurve.between(in, away, joint(in, away, 100.0, 0.0), 14.0);

        assertNotNull(curve);
        assertEquals(86.0, curve.x1(), 0.01);
        assertEquals(0.0, curve.z1(), 0.01);
        assertEquals(114.0, curve.x2(), 0.01);
        assertEquals(0.0, curve.z2(), 0.01);
        // Both tangents along +X: the direction a train travels through the junction, on both routes.
        assertEquals(0.0, Math.cos(curve.heading1()) - 1.0, 1.0E-9);
        assertEquals(0.0, Math.cos(curve.heading2()) - 1.0, 1.0E-9);
    }

    @Test
    @DisplayName("two routes in line are joined by a straight, not by a curve")
    void inLineIsAStraight() {
        Centreline in = line(0.0, 0.0, 100.0, 0.0);
        Centreline away = line(100.0, 0.0, 200.0, 0.0);
        JunctionCurve curve = JunctionCurve.between(in, away, joint(in, away, 100.0, 0.0), 14.0);

        assertNotNull(curve);
        assertTrue(curve.straight(), "two routes in line should give an infinite radius");
        assertEquals(0.0, curve.deflection(), 1.0E-6);
        // A straight's handles are simply collinear, a third of the way along, which is what a cubic
        // with evenly spaced handles looks like.
        assertEquals(curve.length() / 3.0, curve.handle(), 0.01);
        assertEquals(curve.x1() + curve.length() / 3.0, curve.c1x(), 0.01);
        assertEquals(curve.x2() - curve.length() / 3.0, curve.c2x(), 0.01);
    }

    @Test
    @DisplayName("two routes that both finish at the junction give the same curve as one of each")
    void bothFinishingReadsTheSameWay() {
        // Moor Junction: both routes were surveyed towards the stop, arriving head on.
        Centreline west = line(0.0, 0.0, 100.0, 0.0);
        Centreline east = line(200.0, 0.0, 100.0, 0.0);
        RouteMeeting meeting = new RouteMeeting(
                new RouteMeeting.Side(A, "west", null, west.length(),
                        west.at(west.length()).heading(), RouteMeeting.End.FINISH),
                new RouteMeeting.Side(B, "east", null, east.length(),
                        east.at(east.length()).heading(), RouteMeeting.End.FINISH),
                RouteMeeting.Kind.JOINT, 100.0, 0.0, 0.0);
        JunctionCurve curve = JunctionCurve.between(west, east, meeting, 14.0);

        assertNotNull(curve);
        assertTrue(curve.straight(), "two routes meeting head on are in line");
        assertEquals(86.0, curve.x1(), 0.01);
        assertEquals(114.0, curve.x2(), 0.01, "the far end is a lead along the second route,"
                + " which was surveyed the other way round");
    }

    @Test
    @DisplayName("a route too short to give the junction its lead has no curve")
    void tooShortHasNoCurve() {
        Centreline in = line(0.0, 0.0, 100.0, 0.0);
        Centreline stub = line(100.0, 0.0, 120.0, 0.0);
        assertNull(JunctionCurve.between(in, stub, joint(in, stub, 100.0, 0.0), 14.0));
    }
}
