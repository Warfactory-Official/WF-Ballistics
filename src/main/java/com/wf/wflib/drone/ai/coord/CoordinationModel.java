package com.wf.wflib.drone.ai.coord;

import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DronePlan;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.state.Tuning;
import com.wf.wflib.drone.squad.Formation;
import com.wf.wflib.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** How a squad decides where its members should be: the architecture of the formation, as opposed to its shape. */
public interface CoordinationModel {

    /**
     * Registry path, e.g. {@code "virtual_structure"}.
     */
    String id();

    /**
     * Place the reference frame for this tick, before any drone has been planned.
     *
     * @param previous last tick's anchor for this squad, or null on the first tick of a flight, after a
     *      squad re-forms around a new leader, or when the squad has been away over unloaded
     *      terrain. Treat it as "seed yourself"
     */
    SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous);

    /** Second chance at the anchor once the leader's plan for this tick is known. */
    default SquadAnchor refresh(SquadView squad, SquadAnchor anchor, DronePlan leaderPlan) {
        return anchor;
    }

    /**
     * @return true if the leader holds a station like everyone else rather than flying its own route
     *      directly.
     */
    default boolean stationsLeader() {
        return false;
    }

    /**
     * @return the velocity this drone should fly to hold its place in the squad, and what that velocity is
     *      accelerating at, for the flight model to lean into ahead of the error. Called for every member the
     *      model is responsible for, after {@link #advance} and {@link #refresh} have settled the frame.
     */
    SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor);

    /**
     * @return true when the squad has assembled into whatever shape this model actually holds, so a form-up
     *      can end. Asked once per tick, of the leader, by {@code MusterHandler}.
     * @param anchor the frame this squad is holding, or null if it has not settled one yet
     */
    default boolean formedUp(SquadView squad, @Nullable SquadAnchor anchor) {
        if (anchor == null) {
            return false;
        }
        Formation shape = Formations.get(squad.formationId());
        Vec3 forward = Formation.forward(anchor.yaw());
        double tolerance = Math.max(Tuning.MUSTER_IN_PLACE_FLOOR, squad.spacing() * Tuning.MUSTER_IN_PLACE);
        for (DroneSnapshot member : squad.slots()) {
            if (!member.state().powered()) {
                continue;
            }
            Vec3 slot = shape.slot(squad.indexOf(member), anchor.pos(), forward, squad.spacing());
            if (member.pos().distanceTo(slot) > tolerance) {
                return false;
            }
        }
        return true;
    }
}
