package com.wf.wfballistics.drone;

import com.wf.wfballistics.drone.sim.SimDroneManager;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bringing a drone back that has fallen out of the loaded world. */
public final class DroneRecall {

    /** Loaded is not enough: {@code 33 - 2 = 31} is entity-ticking, and ticking is the whole point. */
    private static final int TICKET_DISTANCE = 2;
    /** Lifespan comfortably longer than {@link #RESTAMP}, so a watched drone never blinks out between stamps. */
    private static final TicketType<Unit> RECALL = TicketType.create("wf_drone_recall", (a, b) -> 0, 200);
    private static final int RESTAMP = 40;

    private static final Map<UUID, GlobalPos> LAST_SEEN = new HashMap<>();
    private static final Map<UUID, Long> STAMPED = new HashMap<>();

    private DroneRecall() {
    }

    /** Called as a drone leaves the level, whether that is an unload or a death. */
    public static void leaving(DroneEntity drone) {
        if (!(drone.level() instanceof ServerLevel level)) {
            return;
        }
        if (drone.getRemovalReason() != null && drone.getRemovalReason().shouldDestroy()) {
            LAST_SEEN.remove(drone.getUUID());
            STAMPED.remove(drone.getUUID());
            return;
        }
        LAST_SEEN.put(drone.getUUID(), GlobalPos.of(level.dimension(), drone.blockPosition()));
    }

    /**
     * Get this drone back, by whichever of the two routes applies.
     *
     * @return true if there is somewhere to look for it. False means it was never seen in this level this
     *      session, and the caller has nothing better to offer than an empty channel.
     */
    public static boolean recall(ServerLevel level, UUID id) {
        if (SimDroneManager.onload(level, id)) {
            return true;
        }
        GlobalPos at = LAST_SEEN.get(id);
        if (at == null || !at.dimension().equals(level.dimension())) {
            return false;
        }
        long now = level.getGameTime();
        Long last = STAMPED.get(id);
        if (last != null && now - last < RESTAMP) {
            return true;
        }
        STAMPED.put(id, now);
        level.getChunkSource().addRegionTicket(RECALL, new ChunkPos(at.pos()), TICKET_DISTANCE, Unit.INSTANCE);
        return true;
    }

    public static void shutdown() {
        LAST_SEEN.clear();
        STAMPED.clear();
    }
}
