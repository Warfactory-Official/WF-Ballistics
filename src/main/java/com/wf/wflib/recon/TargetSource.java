package com.wf.wflib.recon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Somewhere targets live. */
public interface TargetSource {

    /**
     * Emit a snapshot for anything of mine inside this volume. World thread.
     */
    void collect(ServerLevel level, AABB volume, TargetSink sink);
}
