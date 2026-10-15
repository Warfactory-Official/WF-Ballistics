package com.wf.wflib.backpack;

import com.wf.wflib.probe.ProbeActions;

/** Probe action {@link Backpacks#OPEN} on a player: another player's worn backpack, unless locked. */
final class BackpackProbe {
    private BackpackProbe() {
    }

    static void registerServer() {
        ProbeActions.registerEntity(Backpacks.OPEN, (player, entity, arg) -> Backpacks.open(player, entity));
    }
}
