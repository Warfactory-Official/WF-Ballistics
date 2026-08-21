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

/**
 * The shape held against <em>every</em> member at once rather than against one reference.
 *
 * <p>A drone here does not ask "where is my slot"; it asks each of its squadmates "where should you be
 * relative to me, and where are you actually", and flies the average of the answers. In formation-control
 * terms the sensing graph is complete rather than a star, and the consequence is that the shape is
 * <em>rigid</em>: it is over-determined by the pairwise offsets, so an error anywhere is shared out across
 * everyone instead of landing entirely on whichever drone happens to be measuring against the one that
 * moved.
 *
 * <p><b>Where this beats a single reference.</b> Under {@link LeaderFollower} the leader is a single point of
 * failure for accuracy as well as for survival: it is the only drone whose position is treated as truth, so
 * its error is everybody's error at full strength and nobody else's error is corrected at all. Under
 * consensus no drone's position is privileged. A member knocked off station is pulled back by the whole
 * squad rather than only by itself, and a member that cannot recover pulls the rest of the shape only
 * slightly out of true instead of leaving a hole in it.
 *
 * <p><b>What it costs.</b> The relative offsets fix the shape but not where the shape <em>is</em>, so on its
 * own a consensus flight is free to translate and rotate as a body: mathematically it has to be, since
 * every constraint is a difference. That is what the leader is still for: it flies the mission as the one
 * informed member and the anchor carries its heading, so the group is pinned to a course without any drone's
 * position being taken as the truth. The other cost is n² work per tick, which for squads this size is
 * nothing.
 */
public final class Consensus implements CoordinationModel {

    public static final Consensus INSTANCE = new Consensus();

    /**
     * How much of the drone's target position is taken from the mission rather than from its neighbours.
     *
     * <p>Small, because the whole point is that the shape is decided by the squad. But not zero, and it
     * cannot be: the pairwise offsets fix the shape and say nothing whatever about where the shape is, so a
     * pure consensus is free to translate and rotate as a body and would happily hold a perfect wedge into
     * the sea. This is the informed member's vote, and it is the only thing tying the group to a course.
     */
    public static final double MISSION_WEIGHT = 0.15;

    private Consensus() {
    }

    @Override
    public String id() {
        return "consensus";
    }

    /**
     * The frame exists only to orient the shape and to say where the flight is going. Its position is not
     * used as a reference by anybody: that is the difference between this and {@link LeaderFollower}, which
     * shares the same anchor construction.
     */
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
