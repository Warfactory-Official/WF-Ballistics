package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One phase of a missile's flight (e.g. */
public interface FlightStage {

    /**
     * @return the desired velocity (blocks/tick) for this tick. Turn-rate limiting is applied by the caller.
     */
    Vec3 guide(MissileEntity missile, FlightContext ctx);

    /**
     * @return the phase to transition into this tick, or {@code null} to remain in this stage. Evaluated
     *      before {@link #guide}, so the returned phase's stage produces this tick's velocity.
     */
    @Nullable
    default MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return null;
    }

    /**
     * @return true if this terminal stage flies a curved level-run -> pitch-over -> dive trajectory (like
     *      {@link AttackStage}) and so needs {@link CruiseStage} to hand off early enough to fit that pitch-over. A
     *      stage that pure-pursues straight onto the dive line and dives immediately (no pitch-over) returns false so
     *      the handoff isn't pulled out ahead of the real terminal descent.
     */
    default boolean needsPitchoverLead() {
        return false;
    }

    /**
     * @return a short stable id (for debugging / logging).
     */
    String id();
}
