package com.wf.wfballistics.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;

import java.util.Map;
import java.util.UUID;


public final class WFChunkValidation {
    private WFChunkValidation() {
    }

    /**
     * {@link net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback} implementation.
     *
     * Entity-owned tickets (missile UUID → chunks) are for a missile that no longer exists after a restart:
     * drop all non-ticking ones — the live missile will re-add whatever it needs on the first tick.
     *
     * Block-owned tickets (listener wakeups + detonation-guard holds) are transient: they're re-derived from
     * live state each session, so any surviving into a fresh load are stale. Drop them all so a crash or
     * restart inside a grace window can't leak a permanently force-loaded chunk.
     */
    public static void validateTickets(ServerLevel level, TicketHelper helper) {
        for (Map.Entry<UUID, TicketSet> entry : helper.getEntityTickets().entrySet()) {
            UUID owner = entry.getKey();
            TicketSet set = entry.getValue();
            // Non-ticking entity chunks are always stale after restart (fan preload).
            for (long chunk : set.nonTicking()) {
                helper.removeTicket(owner, chunk, false);
            }
            // Ticking entity chunks belong to live missiles; the missile will re-register on first tick,
            // so it is safe to drop them here to avoid permanently orphaned tickets.
            for (long chunk : set.ticking()) {
                helper.removeTicket(owner, chunk, true);
            }
        }
        // Block-owned tickets (listener wakeups + detonation-guard holds) are transient: they're re-derived from
        // live state each session, so any surviving into a fresh load are stale. Drop them all so a crash or
        // restart inside a grace window can't leak a permanently force-loaded chunk.
        for (Map.Entry<BlockPos, TicketSet> entry : helper.getBlockTickets().entrySet()) {
            BlockPos owner = entry.getKey();
            TicketSet set = entry.getValue();
            for (long chunk : set.nonTicking()) {
                helper.removeTicket(owner, chunk, false);
            }
            for (long chunk : set.ticking()) {
                helper.removeTicket(owner, chunk, true);
            }
        }
    }
}
