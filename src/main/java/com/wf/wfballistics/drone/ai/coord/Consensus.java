package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The shape held against <em>every</em> member at once rather than against one reference. */
public final class Consensus implements CoordinationModel {

    public static final Consensus INSTANCE = new Consensus();

    /** How much of the drone's target position is taken from the mission rather than from its neighbours. */
    public static final double MISSION_WEIGHT = 0.15;

    private Consensus() {
    }

    @Override
    public String id() {
        return "consensus";
    }

    /** The frame exists only to orient the shape and to say where the flight is going. */
    @Override
    public SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous) {
        return LeaderFollower.INSTANCE.advance(squad, previous);
    }

    @Override
    public SquadAnchor refresh(SquadView squad, SquadAnchor anchor, DronePlan leaderPlan) {
        return LeaderFollower.INSTANCE.refresh(squad, anchor, leaderPlan);
    }

    @Override
    public SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        Formation shape = Formations.get(squad.formationId());
        Vec3 forward = Formation.forward(anchor.yaw());
        double spacing = squad.spacing();
        Vec3 mine = shape.slot(squad.indexOf(self), Vec3.ZERO, forward, spacing);

        Vec3 error = Vec3.ZERO;
        int neighbours = 0;
        for (DroneSnapshot other : squad.slots()) {
            if (other.id().equals(self.id())) {
                continue;
            }
            Vec3 offset = shape.slot(squad.indexOf(other), Vec3.ZERO, forward, spacing).subtract(mine);
            error = error.add(other.pos().subtract(offset).subtract(self.pos()));
            neighbours++;
        }
        if (neighbours > 0) {
            error = error.scale(1.0 / neighbours);
        }

        Vec3 fromNeighbours = self.pos().add(error);
        Vec3 fromMission = anchor.pos().add(mine);
        Vec3 target = fromNeighbours.scale(1.0 - MISSION_WEIGHT).add(fromMission.scale(MISSION_WEIGHT));

        Slots.Track track = Slots.track(self, squad, anchor);
        return new SquadCommand(
                Steering.station(self.pos(), target, track.velocity(), self.cruiseSpeed(),
                        Tuning.FORMATION_MAX_OVERSPEED, self.climbRate(), self.airframe()),
                track.acceleration());
    }
}
