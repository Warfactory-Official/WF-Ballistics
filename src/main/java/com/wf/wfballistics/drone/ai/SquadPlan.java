package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.ai.coord.SquadAnchor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A worker's answer for a whole squad: one {@link DronePlan} per member, and where the formation's reference
 * frame ended up.
 *
 * <p>The anchor comes back rather than being kept somewhere because a
 * {@link com.wf.wfballistics.drone.ai.coord.CoordinationModel} that computes its reference has to remember
 * it between ticks, and there is nowhere off-thread it could safely be remembered. Handing it out with the
 * plans and taking it back in with the next snapshot keeps every model a pure function of its inputs, which
 * is the property the whole threading design rests on.
 *
 * @param squadId which squad this is the answer for, so the anchor can be filed without the scheduler having
 *                to re-derive it from a leader that may since have been shot down
 * @param anchor  null when the squad's model keeps no state worth carrying: a solo drone, or one of the
 *                models that rebuilds its frame from scratch each tick
 */
public record SquadPlan(long squadId, List<DronePlan> plans, @Nullable SquadAnchor anchor) {
}
