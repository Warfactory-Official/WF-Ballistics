package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One phase of a missile's flight (e.g. ascent, cruise, terminal attack). A stage is a stateless strategy: it
 * reads the missile plus the per-tick {@link FlightContext}, returns the desired velocity, and decides when the
 * flight should advance to another phase. Swapping a stage — a drone ascent curve, a different attack run —
 * changes how the missile flies without touching {@link MissileEntity}. Compose stages into a
 * {@link FlightProfile}; keep any per-missile state on the entity so it persists.
 */
public interface FlightStage {

    /**
     * @return the desired velocity (blocks/tick) for this tick. Turn-rate limiting is applied by the caller.
     */
    Vec3 guide(MissileEntity missile, FlightContext ctx);

    /**
     * @return the phase to transition into this tick, or {@code null} to remain in this stage. Evaluated
     * before {@link #guide}, so the returned phase's stage produces this tick's velocity.
     */
    @Nullable
    default MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return null;
    }

    /**
     * @return true if this terminal stage flies a curved level-run -> pitch-over -> dive trajectory (like
     * {@link AttackStage}) and so needs {@link CruiseStage} to hand off early enough to fit that pitch-over. A
     * stage that pure-pursues straight onto the dive line and dives immediately (no pitch-over) returns false so
     * the handoff isn't pulled out ahead of the real terminal descent.
     */
    default boolean needsPitchoverLead() {
        return false;
    }

    /**
     * @return a short stable id (for debugging / logging).
     */
    String id();
}
