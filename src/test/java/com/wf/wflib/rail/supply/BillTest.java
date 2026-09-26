package com.wf.wflib.rail.supply;

import com.wf.wflib.rail.excavate.TunnelProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a railway costs, which is the number a station needs before it dispatches anything.
 *
 * <p>A work train that sets off under-loaded is not wrong, it just comes back sooner. One dispatched
 * against an estimate that is wildly low looks exactly like a broken machine: it drives four blocks,
 * turns round, and does that until somebody watches it long enough to see the pattern. So the estimate
 * is asserted against a section whose walls can be counted by hand.</p>
 */
class BillTest {

    /** Eight by eight, walls one course thick, torches on the side walls every eight blocks. */
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

    @Test
    @DisplayName("a block of that tunnel is walled with twenty eight blocks, counted by hand")
    void theWallIsTheSectionsOwn() {
        // Eight along the roof, eight along the invert, two per course for the six courses between,
        // and the two torch cells are torches rather than wall.
        assertEquals(28, Bill.liningPerBlock(STANDARD));
    }

    @Test
    @DisplayName("a hundred blocks of it costs the wall a hundred times over, and a rail per block")
    void aRouteCostsItsLength() {
        Bill.Estimate cost = Bill.forLength(STANDARD, 100.0);
        assertEquals(2800, cost.lining());
        assertEquals(100, cost.track());
        assertEquals(25, cost.torch(), "two torches per section, one section every eight blocks");
        assertEquals(2925, cost.total());
    }

    @Test
    @DisplayName("a route with no length costs nothing, and a fraction of a block costs a whole one")
    void theEndsOfTheScale() {
        assertEquals(0, Bill.forLength(STANDARD, 0.0).total());
        assertEquals(0, Bill.forLength(STANDARD, -50.0).total(), "a negative length is not a refund");
        Bill.Estimate scrap = Bill.forLength(STANDARD, 0.25);
        assertEquals(1, scrap.track(), "you cannot buy a quarter of a rail");
        assertTrue(scrap.lining() >= 7, "nor a quarter of a course of bricks");
    }

    @Test
    @DisplayName("the estimate grows with the section, so a bigger bore is a bigger bill")
    void aBiggerTunnelCostsMore() {
        TunnelProfile wide = TunnelProfile.box(12, 8, TunnelProfile.DEFAULT_LINING, 8);
        assertTrue(Bill.liningPerBlock(wide) > Bill.liningPerBlock(STANDARD),
                "twelve wide has more wall than eight wide");
    }
}
