package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim carries the one bit of state shared between the server thread and a worker. */
class ChunkClaimTest {

    @Test
    @DisplayName("a fresh claim is valid and the first revocation is the one kept")
    void firstRevocationWins() {
        ChunkClaim claim = new ChunkClaim(new ChunkPos(1, 2), 0L);
        assertFalse(claim.revoked());
        assertNull(claim.revocation());

        claim.revoke(ChunkClaim.Revocation.PLAYER_NEAR);
        claim.revoke(ChunkClaim.Revocation.CANCELLED);
        assertTrue(claim.revoked());
        assertEquals(ChunkClaim.Revocation.PLAYER_NEAR, claim.revocation(),
                "a later reason must not overwrite the one the worker may already have acted on");
    }

    @Test
    @DisplayName("only an interruption re-queues; a cancel must not carve anyway")
    void onlyInterruptionsRetry() {
        assertTrue(ChunkClaim.Revocation.CHUNK_LOADED.retry());
        assertTrue(ChunkClaim.Revocation.PLAYER_NEAR.retry());
        assertFalse(ChunkClaim.Revocation.CANCELLED.retry());
        assertFalse(ChunkClaim.Revocation.SHUTDOWN.retry());
    }
}
