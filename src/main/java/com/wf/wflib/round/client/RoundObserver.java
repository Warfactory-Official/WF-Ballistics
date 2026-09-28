package com.wf.wflib.round.client;

import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundNetwork;
import net.minecraft.world.phys.Vec3;

/** Every client round, whatever its preset ({@link ClientRounds#addObserver}). Client thread. */
public interface RoundObserver {

    /** After the round's step: travelled {@code round.previous() -> round.position()}; unclipped by blocks. */
    void tick(ClientRounds.Round round);

    /**
     * Round met a block with {@code blockPen}: exited or stopped ({@code pierce.exited()}). Server-reported, or a
     * local prediction's own (then not again from the server). Before that round's next {@link #tick}.
     */
    default void pierced(RoundNetwork.Pierce pierce) {
    }

    /**
     * Client state corrected: server resync (pierce, landing) snapped {@code round} from {@code before}, or an adopted
     * prediction re-aimed onto its server round (eased from {@code before} over the next ticks).
     */
    default void resynced(ClientRounds.Round round, Vec3 before) {
    }

    /** Round removed; {@code at} = server end point (client position for {@code EXPIRED}/{@code CLEARED} locally). */
    default void ended(ClientRounds.Round round, Vec3 at, RoundEnd reason) {
    }
}
