package com.wf.wflib.chunk;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps the chunks around a detonation force-loaded for a short grace window after the missile that caused it is
 * gone, so a multi-tick effect the blast spawned (fire boxes, gas cloud, ...) isn't frozen by a chunk unload the
 * instant the missile is discarded.
 */
public final class DetonationChunkGuard {

    public static final int DEFAULT_GRACE_TICKS = 60;

    public static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "chunk_guard"),
            WFChunkValidation::validateTickets
    );

    private static final List<Hold> HOLDS = new ArrayList<>();

    private DetonationChunkGuard() {
    }

    public static void hold(ServerLevel level, Vec3 center, int chunkRadius) {
        hold(level, center, chunkRadius, DEFAULT_GRACE_TICKS);
    }

    public static void hold(ServerLevel level, Vec3 center, int chunkRadius, int ticks) {
        int r = Math.max(0, chunkRadius);
        BlockPos owner = BlockPos.containing(center);
        int cx = SectionPos.blockToSectionCoord(center.x);
        int cz = SectionPos.blockToSectionCoord(center.z);
        int side = 2 * r + 1;
        long[] chunks = new long[side * side];
        int i = 0;
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                int x = cx + ox;
                int z = cz + oz;
                chunks[i++] = ChunkPos.asLong(x, z);
                CONTROLLER.forceChunk(level, owner, x, z, true, true);
            }
        }
        HOLDS.add(new Hold(level, owner, chunks, level.getGameTime() + ticks));
    }

    public static void tick(ServerLevel level, long now) {
        if (HOLDS.isEmpty()) {
            return;
        }
        Iterator<Hold> it = HOLDS.iterator();
        while (it.hasNext()) {
            Hold h = it.next();
            if (h.level != level || now < h.expiry) {
                continue;
            }
            for (long key : h.chunks) {
                CONTROLLER.forceChunk(level, h.owner,
                        ChunkPos.getX(key), ChunkPos.getZ(key), false, true);
            }
            it.remove();
        }
    }

    private record Hold(ServerLevel level, BlockPos owner, long[] chunks, long expiry) {
    }
}
