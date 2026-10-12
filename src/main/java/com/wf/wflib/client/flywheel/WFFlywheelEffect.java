package com.wf.wflib.client.flywheel;

import dev.engine_room.flywheel.api.visual.Effect;

public interface WFFlywheelEffect extends Effect {

    /** One client tick. */
    void tickEffect();

    /** @return true once finished; caller removes it. */
    boolean isExpired();

    /** Called once, after the last tick; releases resources owned outside Flywheel. */
    default void disposeEffect() {
    }
}
