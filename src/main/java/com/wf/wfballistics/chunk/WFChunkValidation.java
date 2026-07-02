package com.wf.wfballistics.chunk;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongSet;
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
    }
}
