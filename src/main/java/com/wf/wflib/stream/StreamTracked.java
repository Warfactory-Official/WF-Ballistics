package com.wf.wflib.stream;

import net.minecraft.server.level.ServerPlayer;

/** Re-runs one entity's visibility decision for one player, on demand; who has it paired. */
public interface StreamTracked {

    void wfUpdatePlayer(ServerPlayer player);

    boolean wfSeenBy(ServerPlayer player);
}
