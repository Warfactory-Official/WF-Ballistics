package com.wf.wflib.drone.ai.coord;

import com.wf.wflib.drone.ai.DronePlan;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.Steering;
import org.jetbrains.annotations.Nullable;

/** Slots measured off the leader itself, where the leader is a real drone flying its own route. */
public final class LeaderFollower implements CoordinationModel {

    public static final LeaderFollower INSTANCE = new LeaderFollower();

    private LeaderFollower() {
    }

    @Override
    public String id() {
        return "leader_follower";
    }

    /** Sit the frame on the leader as it is right now. */
    @Override
    public SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous) {
        DroneSnapshot leader = squad.leader();
        if (previous == null) {
            return SquadAnchor.on(leader);
        }
        return new SquadAnchor(leader.pos(), leader.velocity(),
                leader.velocity().subtract(previous.velocity()),
                leader.yaw(), Steering.wrap(leader.yaw() - previous.yaw()));
    }

    /** Rebuild the frame around the decision the leader has just made. */
    @Override
    public SquadAnchor refresh(SquadView squad, SquadAnchor anchor, DronePlan leaderPlan) {
        DroneSnapshot leader = squad.leader();
        return new SquadAnchor(leader.pos(), leaderPlan.velocity(),
                leaderPlan.velocity().subtract(leader.velocity()),
                leader.yaw(), Steering.wrap(leaderPlan.yaw() - leader.yaw()));
    }

    @Override
    public SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        return Slots.hold(self, Slots.track(self, squad, anchor));
    }
}
