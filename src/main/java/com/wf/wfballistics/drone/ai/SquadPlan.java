package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.ai.coord.SquadAnchor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A worker's answer for a whole squad: one {@link DronePlan} per member, and where the formation's reference frame
 * ended up.
 *
 * @param squadId which squad this is the answer for, so the anchor can be filed without the scheduler having
 *      to re-derive it from a leader that may since have been shot down
 * @param anchor null when the squad's model keeps no state worth carrying: a solo drone, or one of the
 *      models that rebuilds its frame from scratch each tick
 */
public record SquadPlan(long squadId, List<DronePlan> plans, @Nullable SquadAnchor anchor) {
}
