package com.wf.wfballistics.chunk;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.Map;
import java.util.UUID;


public final class WFChunkValidation {
    private WFChunkValidation() {
    }

    public static void validate(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        for (Map.Entry<UUID, Pair<LongSet, LongSet>> entry : helper.getEntityTickets().entrySet()) {
            UUID owner = entry.getKey();
            LongSet nonTicking = entry.getValue().getFirst();
            for (long chunk : nonTicking) {
                helper.removeTicket(owner, chunk, false);
            }
        }
        // Block-owned tickets (listener wakeups + detonation-guard holds) are transient: they're re-derived from
        // live state each session, so any surviving into a fresh load are stale. Drop them all so a crash or
        // restart inside a grace window can't leak a permanently force-loaded chunk.
        for (Map.Entry<BlockPos, Pair<LongSet, LongSet>> entry : helper.getBlockTickets().entrySet()) {
            BlockPos owner = entry.getKey();
            for (long chunk : entry.getValue().getFirst()) {
                helper.removeTicket(owner, chunk, false);
            }
            for (long chunk : entry.getValue().getSecond()) {
                helper.removeTicket(owner, chunk, true);
            }
        }
    }
}
