package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignEditorTest {

    private static AlignEditor threeLeg() {
        AlignEditor editor = new AlignEditor();
        editor.addPoint(0, 0);
        editor.addPoint(500, 0);
        editor.addPoint(500, 500);
        return editor;
    }

    @Test
    @DisplayName("a new point gets the class default radius, not zero")
    void newPointsAreCurvable() {
        AlignEditor editor = new AlignEditor();
        editor.setDesignClass(DesignClass.MAIN);
        editor.addPoint(10, 20);
        assertEquals(DesignClass.MAIN.defaultRadius(), editor.point(0).radius(), 1.0e-9);
        assertTrue(editor.point(0).radius() > DesignClass.MAIN.minRadius(),
                "a fresh corner should not start at the class floor");
    }

    @Test
    @DisplayName("the compile is cached until something changes, because a drag recompiles every frame")
    void resultIsCachedAndInvalidated() {
        AlignEditor editor = threeLeg();
        AlignResult first = editor.result();
        assertSame(first, editor.result(), "nothing changed, so no recompile");

        editor.movePoint(1, 400, 0);
        assertNotSame(first, editor.result(), "a move must invalidate");

        AlignResult second = editor.result();
        editor.setDesignClass(DesignClass.YARD);
        assertNotSame(second, editor.result(), "the class changes what is legal, so it invalidates too");
    }

    @Test
    @DisplayName("picking a point prefers the one drawn on top, so stacked points can be separated")
    void pickPrefersTheLatest() {
        AlignEditor editor = new AlignEditor();
        editor.addPoint(100, 100);
        editor.addPoint(100, 100);
        assertEquals(1, editor.pickPoint(100, 100, 6.0));
        assertEquals(-1, editor.pickPoint(200, 100, 6.0), "out of tolerance is a miss, not the nearest");
    }

    @Test
    @DisplayName("picking a leg gives the index an inserted point would take")
    void legPickGivesAnInsertionIndex() {
        AlignEditor editor = threeLeg();
        assertEquals(1, editor.pickLeg(250, 0, 6.0), "the first leg inserts at 1");
        assertEquals(2, editor.pickLeg(500, 250, 6.0), "the second leg inserts at 2");
        assertEquals(-1, editor.pickLeg(0, 400, 6.0), "nowhere near a leg");

        editor.insertPoint(editor.pickLeg(250, 0, 6.0), 250, 40);
        assertEquals(4, editor.size());
        assertEquals(250.0, editor.point(1).x(), 1.0e-9);
        assertEquals(40.0, editor.point(1).z(), 1.0e-9);
    }

    @Test
    @DisplayName("deleting keeps the selection on a neighbour rather than dropping it")
    void deleteKeepsSelectionNearby() {
        AlignEditor editor = threeLeg();
        editor.select(1);
        editor.removePoint(1);
        assertEquals(1, editor.selected(), "the point that slid into the gap");

        editor.removePoint(1);
        assertEquals(0, editor.selected(), "clamped to the last point when the tail goes");

        editor.removePoint(0);
        assertEquals(-1, editor.selected(), "nothing left to select");
    }

    @Test
    @DisplayName("editing an alignment that cannot be built still yields a drawable line")
    void brokenAlignmentsStillDraw() {
        AlignEditor editor = new AlignEditor();
        editor.addPoint(0, 0);
        editor.addPoint(1000, 0);
        editor.addPoint(1010, 10);
        editor.addPoint(1010, 1000);
        editor.setRadius(1, 400);
        editor.setRadius(2, 400);

        AlignResult result = editor.result();
        assertTrue(!result.buildable(), "these corners cannot both fit on a 14 block leg");
        assertTrue(result.centreline().length() > 0.0, "but there is still something to draw");
    }
}
