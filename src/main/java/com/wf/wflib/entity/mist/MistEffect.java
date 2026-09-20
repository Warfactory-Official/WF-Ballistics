package com.wf.wflib.entity.mist;

import com.wf.wflib.entity.MistEntity;
import net.minecraft.world.entity.Entity;

/** What a fluid does when it hangs in the air as mist. */
public interface MistEffect {

    /**
     * Applied to one entity inside the cloud. Runs server-side.
     */
    void affect(MistEntity mist, Entity target, double intensity);

    /**
     * Whole-cloud tick, before per-entity processing. Runs server-side. Default: nothing.
     */
    default void areaTick(MistEntity mist, double intensity) {
    }

    /**
     * ARGB particle tint. Default {@code -1} means "use the fluid's own render colour".
     */
    default int color(MistEntity mist) {
        return -1;
    }
}
