package com.wf.wflib.rail.build;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Comparator;

/**
 * Keeps the ground a machine is working on loaded, without ever being able to leave it that way.
 *
 * <p>A tunnelling machine changes blocks, which means the chunks it is in have to be resident whether or
 * not anyone is watching. The ticket carries its own lifespan, so a machine that crashes, is cancelled,
 * or loses its level releases the ground a few seconds later on its own. Forced chunks would be the
 * obvious alternative and are exactly wrong here: they are saved with the world, so the one failure that
 * matters leaves a permanently loaded strip across the map with nothing left alive to free it.</p>
 */
final class ChunkHold {

    /** Ticks a hold outlives its last renewal. Long enough to ride out a lag spike, short enough to notice. */
    private static final int LIFESPAN = 200;

    /**
     * Ticket radius. Two puts the chunk at entity-ticking level, which the consist riding the finished
     * track needs; anything it touches at the edges is still loaded.
     */
    private static final int RADIUS = 2;

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("wflib_rail_work", Comparator.comparingLong(ChunkPos::toLong), LIFESPAN);

    private ChunkHold() {
    }

    /**
     * Ask for every chunk this box touches, and say whether they are all here yet.
     *
     * <p>Asking is not getting: a chunk requested this tick arrives some ticks later, generated if it
     * has never existed. A machine calls this every tick and simply does not advance until it answers
     * true, which is what makes "the ground is not there yet" a pause rather than a hole.</p>
     *
     * @param lookahead blocks beyond the box to ask for but not wait on, so the ground a machine is
     *                  about to reach is already on its way by the time it gets there
     */
    static boolean hold(ServerLevel level, BoundingBox box, int lookahead) {
        for (int cx = (box.minX() - lookahead) >> 4; cx <= (box.maxX() + lookahead) >> 4; cx++) {
            for (int cz = (box.minZ() - lookahead) >> 4; cz <= (box.maxZ() + lookahead) >> 4; cz++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(TICKET, pos, RADIUS, pos);
            }
        }
        // Only the ground actually being changed has to be here. Waiting on the lookahead as well would
        // stall the machine on chunks it will not touch for another few seconds.
        boolean all = true;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                all &= level.getChunkSource().getChunkNow(cx, cz) != null;
            }
        }
        return all;
    }
}
