package com.wf.wflib.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;

import java.util.Map;
import java.util.UUID;

public final class WFChunkValidation {
    private WFChunkValidation() {
    }

    /** {@link net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback} implementation. */
    public static void validateTickets(ServerLevel level, TicketHelper helper) {
        for (Map.Entry<UUID, TicketSet> entry : helper.getEntityTickets().entrySet()) {
            UUID owner = entry.getKey();
            TicketSet set = entry.getValue();
            // Non-ticking entity chunks are always stale after restart (fan preload).
            for (long chunk : set.nonTicking()) {
                helper.removeTicket(owner, chunk, false);
            }
            for (long chunk : set.ticking()) {
                helper.removeTicket(owner, chunk, true);
            }
        }
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
