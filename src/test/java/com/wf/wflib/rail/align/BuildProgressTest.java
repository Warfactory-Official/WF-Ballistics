package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which parts of a route have track on them.
 *
 * <p>Worth testing here because it is what {@link RouteStatus#BUILT} rests on: a route claims to be
 * finished when these runs cover it, and a merge that leaves a one-block seam would make that never
 * happen while looking exactly right on the map.</p>
 */
class BuildProgressTest {

    @Test
    @DisplayName("touching runs become one, so building forward from a railhead stays a single run")
    void mergesForwardWork() {
        BuildProgress built = BuildProgress.NONE;
        for (int i = 0; i < 50; i++) {
            built = built.with(i * 8.0, i * 8.0 + 8.0);
        }
        assertEquals(1, built.spans().size(), "a machine working along the route makes one run");
        assertEquals(400.0, built.builtLength(), 1e-9);
    }

    @Test
    @DisplayName("runs a fraction of a block apart are one run")
    void mergesAcrossTheTolerance() {
        BuildProgress built = BuildProgress.NONE.with(0.0, 100.0).with(100.2, 200.0);
        assertEquals(1, built.spans().size());
        assertEquals(200.0, built.spans().get(0).to(), 1e-9);
    }

    @Test
    @DisplayName("a real gap is kept")
    void keepsRealGaps() {
        BuildProgress built = BuildProgress.NONE.with(0.0, 100.0).with(300.0, 400.0);
        assertEquals(2, built.spans().size());
        assertEquals(200.0, built.builtLength(), 1e-9);
        assertEquals(List.of(new BuildProgress.Span(100.0, 300.0), new BuildProgress.Span(400.0, 500.0)),
                built.gaps(500.0));
    }

    @Test
    @DisplayName("track taken out of the middle of a run splits it")
    void removalSplits() {
        BuildProgress built = BuildProgress.NONE.with(0.0, 1000.0).without(400.0, 500.0);
        assertEquals(2, built.spans().size());
        assertEquals(900.0, built.builtLength(), 1e-9);
        assertFalse(built.isBuiltAt(450.0), "the bridge that went down is not built any more");
        assertTrue(built.isBuiltAt(399.0));
        assertTrue(built.isBuiltAt(501.0));
    }

    @Test
    @DisplayName("a route counts as covered only when one run reaches both ends")
    void coversTheWholeRoute() {
        assertTrue(BuildProgress.NONE.with(0.0, 1000.0).covers(1000.0));
        assertFalse(BuildProgress.NONE.with(0.0, 900.0).covers(1000.0));
        assertFalse(BuildProgress.NONE.with(0.0, 400.0).with(600.0, 1000.0).covers(1000.0),
                "a gap in the middle is not a finished railway");
        assertTrue(BuildProgress.NONE.with(0.2, 999.8).covers(1000.0),
                "within the merge tolerance at each end, because that is below anything drawable");
    }

    @Test
    @DisplayName("more runs than the cap close the narrowest gaps, keeping the shape")
    void capsTheRunCount() {
        BuildProgress built = BuildProgress.NONE;
        // Alternating built and blown-up sections, far more of them than the wire allows.
        for (int i = 0; i < BuildProgress.MAX_SPANS * 3; i++) {
            built = built.with(i * 20.0, i * 20.0 + 10.0);
        }
        assertEquals(BuildProgress.MAX_SPANS, built.spans().size());
        assertTrue(built.builtLength() > BuildProgress.MAX_SPANS * 3 * 10.0,
                "closing gaps over-reports rather than dropping runs, so the route keeps its extent");
    }

    @Test
    @DisplayName("a route re-aligned shorter cannot keep track past its own end")
    void clampsToTheRoute() {
        BuildProgress built = BuildProgress.NONE.with(0.0, 1000.0).clampTo(600.0);
        assertEquals(1, built.spans().size());
        assertEquals(600.0, built.spans().get(0).to(), 1e-9);
        assertEquals(1.0, built.fractionOf(600.0), 1e-9);
        assertTrue(BuildProgress.NONE.with(800.0, 900.0).clampTo(600.0).isEmpty(),
                "a run entirely past the new end goes away");
    }

    @Test
    @DisplayName("a zero-length or backwards report changes nothing")
    void ignoresNonsense() {
        assertTrue(BuildProgress.NONE.with(500.0, 500.0).isEmpty());
        assertEquals(100.0, BuildProgress.NONE.with(600.0, 500.0).builtLength(), 1e-9,
                "a backwards report is still a report; it is the order that is wrong, not the fact");
    }

    @Test
    @DisplayName("nothing built is nothing built, at any route length")
    void emptyIsSafe() {
        assertEquals(0.0, BuildProgress.NONE.fractionOf(1000.0), 1e-9);
        assertEquals(0.0, BuildProgress.NONE.fractionOf(0.0), 1e-9);
        assertFalse(BuildProgress.NONE.covers(0.0));
        assertEquals(List.of(new BuildProgress.Span(0.0, 500.0)), BuildProgress.NONE.gaps(500.0));
    }
}
