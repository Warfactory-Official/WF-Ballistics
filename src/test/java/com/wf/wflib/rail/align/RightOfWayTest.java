package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folding per-sample readings into runs: the part with an off-by-one in it. */
class RightOfWayTest {

    private static RightOfWay fold(double[] chainage, String[] owners, boolean[] blocked, double total) {
        int[] colours = new int[owners.length];
        return RightOfWay.fold(chainage, owners, colours, blocked, total, owners.length, 0);
    }

    @Test
    @DisplayName("one owner the whole way is one run, reaching the end of the line")
    void singleRun() {
        RightOfWay row = fold(new double[]{0, 10, 20, 30},
                new String[]{"red", "red", "red", "red"},
                new boolean[]{false, false, false, false}, 40.0);

        assertEquals(1, row.spans().size());
        assertEquals(0.0, row.spans().get(0).from(), 1.0e-9);
        assertEquals(40.0, row.spans().get(0).to(), 1.0e-9, "runs to the end, not to the last sample");
    }

    @Test
    @DisplayName("the runs tile the line with no gaps and no overlaps")
    void runsTile() {
        RightOfWay row = fold(new double[]{0, 10, 20, 30, 40},
                new String[]{"red", "red", "blue", "blue", ""},
                new boolean[]{false, false, true, true, false}, 50.0);

        assertEquals(3, row.spans().size());
        double cursor = 0.0;
        for (RightOfWay.Span span : row.spans()) {
            assertEquals(cursor, span.from(), 1.0e-9, "no gap before this run");
            cursor = span.to();
        }
        assertEquals(50.0, cursor, 1.0e-9, "the last run reaches the end");
    }

    @Test
    @DisplayName("a change of verdict splits a run even when the owner does not change")
    void verdictSplitsARun() {
        // The same faction, but a siege or a zone makes half of it unbuildable.
        RightOfWay row = fold(new double[]{0, 10, 20, 30},
                new String[]{"red", "red", "red", "red"},
                new boolean[]{false, false, true, true}, 40.0);

        assertEquals(2, row.spans().size());
        assertFalse(row.spans().get(0).blocked());
        assertTrue(row.spans().get(1).blocked());
    }

    @Test
    @DisplayName("blocked length and owner count are what a surveyor actually wants to read")
    void summaries() {
        RightOfWay row = fold(new double[]{0, 100, 200, 300},
                new String[]{"", "red", "red", "blue"},
                new boolean[]{false, true, true, false}, 400.0);

        assertEquals(2, row.ownersCrossed(), "unclaimed ground is not an owner");
        assertEquals(200.0, row.blockedLength(), 1.0e-9, "100 to 300 is refused");
    }

    @Test
    @DisplayName("an empty route folds to nothing rather than throwing")
    void emptyRoute() {
        assertTrue(fold(new double[0], new String[0], new boolean[0], 0.0).isEmpty());
        assertTrue(RightOfWay.EMPTY.isEmpty());
        assertEquals(0, RightOfWay.EMPTY.ownersCrossed());
    }

    @Test
    @DisplayName("alternating ownership is capped rather than filling the wire")
    void capped() {
        int n = RightOfWay.MAX_SPANS * 4;
        double[] chainage = new double[n];
        String[] owners = new String[n];
        boolean[] blocked = new boolean[n];
        for (int i = 0; i < n; i++) {
            chainage[i] = i * 10.0;
            owners[i] = i % 2 == 0 ? "red" : "blue";
        }
        assertEquals(RightOfWay.MAX_SPANS, fold(chainage, owners, blocked, n * 10.0).spans().size());
    }
}
