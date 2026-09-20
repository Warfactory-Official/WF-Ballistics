package com.wf.wflib.client.flywheel;

import dev.engine_room.flywheel.api.visual.Effect;

public interface WFFlywheelEffect extends Effect {

    /**
     * Advance the simulation one client tick.
     */
    void tickEffect();

    /**
     * @return true once the effect has finished and should be removed from Flywheel
     */
    boolean isExpired();

    /** Release anything the effect owns outside Flywheel, once it will never be ticked again. */
    default void disposeEffect() {
    }
}
