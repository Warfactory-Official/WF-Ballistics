package com.wf.wflib.stream;

import com.wf.wflib.config.WFConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Who is operating remotely right now, and what that does to their parked body. Read off-thread by mixins. */
public final class DetachedBodies {

    private static volatile Set<UUID> piloting = Collections.emptySet();

    private DetachedBodies() {
    }

    static void publish(Set<UUID> next) {
        piloting = next;
    }

    static void clear(UUID playerId) {
        Set<UUID> current = piloting;
        if (!current.contains(playerId)) {
            return;
        }
        Set<UUID> next = new HashSet<>(current);
        next.remove(playerId);
        piloting = next;
    }

    public static boolean isPiloting(UUID playerId) {
        return piloting.contains(playerId);
    }

    public static boolean suppressesBodyEntities(UUID playerId) {
        return isPiloting(playerId) && WFConfig.DETACHED_SUPPRESS_BODY_ENTITIES.get();
    }

    /** @return parked body's view distance, or -1 = vanilla's */
    public static int bodyViewDistance(UUID playerId, int serverViewDistance) {
        if (!isPiloting(playerId)) {
            return -1;
        }
        int configured = WFConfig.DETACHED_BODY_VIEW_DISTANCE.get();
        return configured < 2 ? -1 : Math.min(configured, serverViewDistance);
    }

    public static boolean parksBodyTickets(UUID playerId) {
        return isPiloting(playerId) && WFConfig.DETACHED_BODY_TICKET_RADIUS.get() >= 0;
    }

    public static boolean mustStayPaired(Entity entity, Player player) {
        return entity == player || entity.getVehicle() == player;
    }

    /** Whatever the player rides (a remote host however far the parked body is) stays tracked to them. */
    public static boolean ridesTogether(Entity entity, Player player) {
        Entity ridden = player.getVehicle();
        return ridden != null && entity.getRootVehicle() == ridden.getRootVehicle();
    }

    static void shutdown() {
        piloting = Collections.emptySet();
    }
}
