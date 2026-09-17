package com.wf.wfballistics.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** The program each player is part-way through writing at the command line. */
public final class DroneDrafts {

    private static final Map<UUID, DroneProgram> BY_PLAYER = new HashMap<>();

    private DroneDrafts() {
    }

    public static DroneProgram get(UUID player) {
        return BY_PLAYER.getOrDefault(player, DroneProgram.EMPTY);
    }

    /**
     * Rewrite a player's draft.
     *
     * @return the new draft
     */
    public static DroneProgram edit(UUID player, UnaryOperator<DroneProgram> change) {
        DroneProgram next = change.apply(get(player));
        if (next.isEmpty()) {
            BY_PLAYER.remove(player);
        } else {
            BY_PLAYER.put(player, next);
        }
        return next;
    }

    public static void clear(UUID player) {
        BY_PLAYER.remove(player);
    }
}
