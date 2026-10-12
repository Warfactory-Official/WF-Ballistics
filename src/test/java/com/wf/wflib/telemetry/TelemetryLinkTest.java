package com.wf.wflib.telemetry;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WarForge absent here: sides match by identity only. */
class TelemetryLinkTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID BLUE = UUID.randomUUID();
    private static final UUID RED = UUID.randomUUID();

    @Test
    void theOperatorHearsTheirOwnRoundWhateverTheSide() {
        assertTrue(TelemetryLink.receives(ME, null, new Telemetry(TelemetryKind.MISSILE, "", RED, ME)));
    }

    @Test
    void theSameSideHearsItAndTheOtherDoesNot() {
        Telemetry blue = new Telemetry(TelemetryKind.DRONE, "", BLUE, OTHER);
        assertTrue(TelemetryLink.receives(ME, BLUE, blue));
        assertFalse(TelemetryLink.receives(ME, RED, blue));
    }

    @Test
    void noSideHearsOnlyItsOwn() {
        assertFalse(TelemetryLink.receives(ME, null, new Telemetry(TelemetryKind.DRONE, "", null, OTHER)));
        assertFalse(TelemetryLink.receives(ME, BLUE, new Telemetry(TelemetryKind.DRONE, "", null, null)));
    }
}
