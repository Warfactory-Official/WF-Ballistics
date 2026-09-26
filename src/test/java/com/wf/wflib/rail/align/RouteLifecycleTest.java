package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What building does to a route's status, and what a revision is for.
 *
 * <p>The promotion rule is the part that keeps {@link RouteStatus#BUILT} honest rather than decorative,
 * and it is a rule about disclosure as well as display: promoting a draft makes it visible to allies.</p>
 */
class RouteLifecycleTest {

    private static Alignment route(RouteStatus status) {
        return new Alignment(UUID.randomUUID(), "north main", UUID.randomUUID(), UUID.randomUUID(),
                DesignClass.MAIN, 0x66E0FF, List.of(
                        new AlignPoint(0.0, 0.0, 200.0),
                        new AlignPoint(1000.0, 0.0, 200.0)),
                status, BuildProgress.NONE, Alignment.Revision.NEW);
    }

    @Test
    @DisplayName("laying track on a draft promotes it, because the rails are there to be seen anyway")
    void buildingPromotesADraft() {
        Alignment after = AlignmentEdits.applyBuild(route(RouteStatus.DRAFT), 0.0, 100.0, true);
        assertEquals(RouteStatus.BUILDING, after.status());
    }

    @Test
    @DisplayName("track the whole way marks a route built")
    void coveringMarksItBuilt() {
        Alignment after = AlignmentEdits.applyBuild(route(RouteStatus.PLANNED), 0.0, 10000.0, true);
        assertEquals(RouteStatus.BUILT, after.status());
        assertTrue(after.built().covers(after.compile().centreline().length()));
    }

    @Test
    @DisplayName("track taken out demotes a built route to planned, never back to a draft")
    void removingDemotesOnlySoFar() {
        Alignment built = AlignmentEdits.applyBuild(route(RouteStatus.PLANNED), 0.0, 10000.0, true);
        Alignment stripped = AlignmentEdits.applyBuild(built, 0.0, 10000.0, false);
        assertEquals(RouteStatus.PLANNED, stripped.status(),
                "a route that was once built was agreed, so it does not become a proposal again");
    }

    @Test
    @DisplayName("abandoning is a statement a person made, and building does not undo it")
    void abandonedStaysAbandoned() {
        Alignment after = AlignmentEdits.applyBuild(route(RouteStatus.ABANDONED), 0.0, 500.0, true);
        assertEquals(RouteStatus.ABANDONED, after.status());
    }

    @Test
    @DisplayName("a re-alignment cannot leave track hanging past the new end")
    void shorteningClampsTheTrack() {
        Alignment built = AlignmentEdits.applyBuild(route(RouteStatus.PLANNED), 0.0, 10000.0, true);
        double shorter = 400.0;
        BuildProgress clamped = built.built().clampTo(shorter);
        assertEquals(shorter, clamped.builtLength(), 1e-6);
        assertEquals(RouteStatus.BUILT, AlignmentEdits.afterBuildChange(built.status(), clamped, shorter));
    }

    @Test
    @DisplayName("every accepted write moves the revision on, which is what makes a stale save detectable")
    void everyEditBumpsTheRevision() {
        Alignment first = route(RouteStatus.DRAFT).editedBy(UUID.randomUUID());
        assertEquals(1, first.revision().number());
        UUID second = UUID.randomUUID();
        Alignment next = first.withStatus(RouteStatus.PLANNED).editedBy(second);
        assertEquals(2, next.revision().number());
        assertEquals(second, next.revision().editor());
        assertNotEquals(first.revision().number(), next.revision().number(),
                "a client saving against revision 1 must be refused once someone else has made 2");
    }

    @Test
    @DisplayName("extending a finished railway stops it claiming to be finished")
    void extendingDemotesABuiltRoute() {
        Alignment built = AlignmentEdits.applyBuild(route(RouteStatus.PLANNED), 0.0, 10000.0, true);
        assertEquals(RouteStatus.BUILT, built.status());

        // Twice as long, with the same track on the first half.
        double longer = built.compile().centreline().length() * 2.0;
        BuildProgress same = built.built().clampTo(longer);
        assertEquals(RouteStatus.BUILDING, AlignmentEdits.afterBuildChange(built.status(), same, longer),
                "the new half has no track on it, so the route is under construction again");
    }
}
