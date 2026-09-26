package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiler decides what a line is, so these are the tests that matter most. The continuity check is
 * the one to keep: IR positions stock along its graph, so a heading discontinuity is not a cosmetic seam,
 * it is a place a consist's geometry gets re-derived into a kink.
 */
class AlignCompilerTest {

    private static final double EPS = 1.0e-6;

    private static AlignPoint pi(double x, double z, double r) {
        return new AlignPoint(x, z, r);
    }

    /** Every element must start exactly where the previous one ended, pointing the same way. */
    private static void assertContinuous(Centreline line) {
        List<AlignElement> elements = line.elements();
        for (int i = 0; i < elements.size() - 1; i++) {
            AlignElement here = elements.get(i);
            AlignElement next = elements.get(i + 1);
            AlignElement.Sample end = here.at(here.length());
            AlignElement.Sample start = next.at(0.0);
            assertEquals(end.x(), start.x(), 1.0e-6, "x at element " + i + " boundary");
            assertEquals(end.z(), start.z(), 1.0e-6, "z at element " + i + " boundary");
            double turn = Math.abs(Math.atan2(Math.sin(start.heading() - end.heading()),
                    Math.cos(start.heading() - end.heading())));
            assertTrue(turn < 1.0e-6, "heading jumps " + Math.toDegrees(turn) + " deg at element " + i);
        }
    }

    @Test
    @DisplayName("two points are one straight, and nothing else")
    void twoPointsAreAStraight() {
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(300, 400, 0)), DesignClass.BRANCH);

        assertEquals(1, result.centreline().elements().size());
        assertEquals(500.0, result.centreline().length(), EPS, "3-4-5");
        assertTrue(result.buildable());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    @DisplayName("collinear points need no curve, and get none")
    void collinearPointsAreStillStraight() {
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(100, 0, 120), pi(300, 0, 120)), DesignClass.BRANCH);

        assertEquals(300.0, result.centreline().length(), EPS);
        assertTrue(result.centreline().elements().stream().allMatch(e -> e instanceof AlignElement.Tangent));
        assertTrue(result.buildable(), "a straight line through a PI is not a problem");
    }

    @Test
    @DisplayName("a right-angle corner sets its curve back by the tangent length, and stays tangent to both legs")
    void rightAngleCorner() {
        // T = R tan(45) = R, so the curve starts 50 short of the corner and ends 50 past it.
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(100, 0, 50), pi(100, 100, 0)), DesignClass.YARD);

        Centreline line = result.centreline();
        assertEquals(3, line.elements().size(), "straight, arc, straight");
        assertContinuous(line);

        AlignElement.Curve curve = (AlignElement.Curve) line.elements().get(1);
        assertEquals(50.0, curve.radius(), EPS);
        assertEquals(Math.PI / 2.0, curve.deflection(), EPS, "a right angle of deflection");

        // 50 of straight, a quarter circle, 50 of straight.
        assertEquals(50.0 + 50.0 * Math.PI / 2.0 + 50.0, line.length(), EPS);

        AlignElement.Sample start = line.at(0.0);
        assertEquals(0.0, start.x(), EPS);
        assertEquals(0.0, start.z(), EPS);
        AlignElement.Sample end = line.at(line.length());
        assertEquals(100.0, end.x(), EPS);
        assertEquals(100.0, end.z(), EPS);
        assertEquals(Math.PI / 2.0, end.heading(), EPS, "leaves heading north");
    }

    @Test
    @DisplayName("a corner turning the other way is the mirror of one turning this way")
    void cornersTurnBothWays() {
        AlignResult left = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(100, 0, 50), pi(100, 100, 0)), DesignClass.YARD);
        AlignResult right = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(100, 0, 50), pi(100, -100, 0)), DesignClass.YARD);

        assertEquals(left.centreline().length(), right.centreline().length(), EPS);
        assertContinuous(right.centreline());
        AlignElement.Curve leftCurve = (AlignElement.Curve) left.centreline().elements().get(1);
        AlignElement.Curve rightCurve = (AlignElement.Curve) right.centreline().elements().get(1);
        assertTrue(leftCurve.sweep() > 0 && rightCurve.sweep() < 0, "opposite sweeps");
        assertEquals(leftCurve.cz() - 0.0, -(rightCurve.cz() - 0.0), EPS, "centres mirror about the leg");
    }

    @Test
    @DisplayName("two curves that will not fit on one leg are both cut down, reported, and still drawable")
    void overrunningCurvesAreClamped() {
        // A dogleg: 85 blocks between the two corners, but each 45 degree turn at radius 200 wants
        // 83 of tangent, so together they want twice the leg they have to share.
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(200, 0, 200), pi(260, 60, 200), pi(260, 300, 0)),
                DesignClass.BRANCH);

        assertFalse(result.buildable(), "this cannot be built as drawn");
        assertFalse(result.errors().isEmpty());
        assertNotNull(result.worstAt(1));
        assertNotNull(result.worstAt(2));
        // The point of clamping: what is drawn is continuous, so an edit in progress still renders.
        assertContinuous(result.centreline());
    }

    @Test
    @DisplayName("doubling back is refused rather than clamped, because tan(d/2) runs away")
    void doublingBackIsAnError() {
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(100, 0, 50), pi(0, 1, 0)), DesignClass.YARD);

        assertFalse(result.buildable());
        AlignProblem problem = result.worstAt(1);
        assertNotNull(problem);
        assertTrue(problem.message().contains("doubles back"), problem.message());
    }

    @Test
    @DisplayName("a radius under the class minimum warns but still builds")
    void tightRadiusWarns() {
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(1000, 0, 40), pi(1000, 1000, 0)), DesignClass.MAIN);

        assertTrue(result.buildable(), "off-class is a bad line, not an impossible one");
        AlignProblem problem = result.worstAt(1);
        assertNotNull(problem);
        assertEquals(AlignProblem.Severity.WARNING, problem.severity());
    }

    @Test
    @DisplayName("sampling lands on element boundaries, so a drawn curve has no kink at its ends")
    void samplingRespectsElementBoundaries() {
        AlignResult result = AlignCompiler.compile(
                List.of(pi(0, 0, 0), pi(137, 0, 61), pi(137, 149, 0)), DesignClass.YARD);
        Centreline line = result.centreline();

        List<AlignElement.Sample> samples = line.sample(7.0);
        assertEquals(0.0, samples.get(0).x(), EPS);
        AlignElement.Sample last = samples.get(samples.size() - 1);
        assertEquals(137.0, last.x(), 1.0e-6);
        assertEquals(149.0, last.z(), 1.0e-6);

        // No two consecutive samples further apart than the step asked for.
        for (int i = 1; i < samples.size(); i++) {
            double gap = Math.hypot(samples.get(i).x() - samples.get(i - 1).x(),
                    samples.get(i).z() - samples.get(i - 1).z());
            assertTrue(gap <= 7.0 + 1.0e-6, "gap " + gap + " at sample " + i);
        }
    }

    @Test
    @DisplayName("an empty or single-point alignment compiles to nothing rather than throwing")
    void degenerateInputs() {
        assertTrue(AlignCompiler.compile(List.of(), DesignClass.BRANCH).centreline().isEmpty());
        assertTrue(AlignCompiler.compile(List.of(pi(5, 5, 0)), DesignClass.BRANCH).centreline().isEmpty());
        assertTrue(AlignCompiler.compile(null, DesignClass.BRANCH).centreline().isEmpty());
    }
}
