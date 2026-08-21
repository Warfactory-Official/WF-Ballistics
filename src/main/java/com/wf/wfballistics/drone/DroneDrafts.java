package com.wf.wfballistics.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The program each player is part-way through writing at the command line.
 *
 * <p>A scratchpad, deliberately. {@code /wfballistics drone program} adds one step per invocation, and the
 * steps have to accumulate somewhere between commands; this is that somewhere. It is server-side, per player,
 * and not saved: a half-written program is not worth carrying across a restart, and the drone pad is where a
 * program that <em>should</em> outlive the session belongs, because a pad keeps its own.
 *
 * <p>World-thread only, like everything else the command layer touches.
 */
public final class DroneDrafts {

    private static final Map<UUID, DroneProgram> BY_PLAYER = new HashMap<>();

    private DroneDrafts() {
    }

    public static DroneProgram get(UUID player) {
        return BY_PLAYER.getOrDefault(player, DroneProgram.EMPTY);
    }

    /**
     * Rewrite a player's draft. {@link DroneProgram} is immutable, so editing is "read it, transform it, put
     * it back" rather than mutating in place.
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
