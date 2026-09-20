package com.wf.wflib.drone.cam;

import net.minecraft.server.level.ServerPlayer;

/** Re-runs one entity's visibility decision for one player, on demand. */
public interface FeedTracked {

    void wfCamUpdatePlayer(ServerPlayer player);
}
