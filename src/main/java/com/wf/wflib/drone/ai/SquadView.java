package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.ai.coord.CoordinationModel;
import com.wf.wflib.drone.ai.coord.CoordinationModels;
import com.wf.wflib.drone.ai.coord.SquadAnchor;
import com.wf.wflib.drone.squad.Formation;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The whole squad as one worker job sees it.
 *
 * @param slots members in a stable order; index 0 is the leader's slot when it is present
 * @param spacing how far apart this squad flies its slots, from the leader's mission. Held on the
 *      squad rather than on each member because a formation with two opinions about its own
 *      size is not a formation: every slot has to be measured off the same number
 * @param coordinationId which architecture the squad holds its shape with. The shape and the architecture are
 *      separate choices: a wedge is a wedge whether it is measured off the leader or off a
 *      computed point, and only the second decides how well it is held
 * @param anchor the reference frame this squad was flying at the end of last tick, or null if it has
 *      none yet. The one piece of squad state that persists between ticks, and it is passed
 *      in and handed back rather than stored anywhere a worker could reach: see
 *      {@link CoordinationModel}
 */
public record SquadView(long squadId, @Nullable ResourceLocation formationId, double spacing,
                        @Nullable ResourceLocation coordinationId, @Nullable SquadAnchor anchor,
                        DroneSnapshot leader, List<DroneSnapshot> slots) {

    public static SquadView solo(DroneSnapshot self) {
        return new SquadView(0L, null, Formation.DEFAULT_SPACING, null, null, self, List.of(self));
    }

    /**
     * @return the architecture this squad coordinates with, falling back to the default for an unknown or
     *      missing id exactly as {@code Formations} does for a shape.
     */
    public CoordinationModel coordination() {
        return CoordinationModels.get(coordinationId);
    }

    /**
     * @return this view with a different remembered frame, for handing last tick's anchor to a fresh
     *      snapshot of the same squad.
     */
    public SquadView withAnchor(@Nullable SquadAnchor anchor) {
        return new SquadView(squadId, formationId, spacing, coordinationId, anchor, leader, slots);
    }

    public int size() {
        return slots.size();
    }

    /**
     * @return this drone's 0-based position in the formation, or 0 if it somehow isn't listed.
     */
    public int indexOf(DroneSnapshot member) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).id().equals(member.id())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * @return true if {@code member} should fly its own route rather than hold a formation slot.
     */
    public boolean isLeader(DroneSnapshot member) {
        return leader.id().equals(member.id());
    }
}
