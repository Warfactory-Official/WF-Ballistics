package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import org.jetbrains.annotations.Nullable;

/**
 * Slots measured off the leader itself, where the leader is a real drone flying its own route.
 *
 * <p>The oldest of the formation architectures and the one everybody writes first, because it is the one you
 * get by simply saying "fly next to him". It is cheap, it needs no state, and a squad that loses its leader
 * re-forms on a survivor with nothing to rebuild.
 *
 * <p><b>Its weakness is structural, not a matter of tuning.</b> The reference is a measurement of another
 * aircraft, so everything that happens to the leader is indistinguishable from an order. Its own tracking
 * error, a terrain-guard shove off a hillside, a squadmate pushing past, a tick of lag in its velocity loop:
 * all of it enters the frame and is then multiplied by the lever arm of the spacing before it reaches the
 * followers. A follower cannot tell "the leader meant to go there" from "the leader got bumped", and the
 * wider the formation flies the more of the second it sees. {@link VirtualStructure} exists because that
 * ceiling cannot be raised from inside this model.
 *
 * <p>What it does have is the two feed-forward terms, and those are worth most of the difference between a
 * formation and a gaggle: the frame is built on the leader's <em>heading</em> rather than its velocity, so
 * drift cannot rotate it, and the anchor is refreshed from the leader's fresh plan rather than last tick's
 * measurement, so followers are not permanently a tick of acceleration behind.
 */
public final class LeaderFollower implements CoordinationModel {

    public static final LeaderFollower INSTANCE = new LeaderFollower();

    private LeaderFollower() {
    }

    @Override
    public String id() {
        return "leader_follower";
    }

    /**
     * Sit the frame on the leader as it is right now. Almost always replaced by {@link #refresh} a moment
     * later: this is what the squad flies only if the leader's plan somehow never arrives.
     */
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

    /**
     * Rebuild the frame around the decision the leader has just made.
     *
     * <p>The leader is planned first precisely so this can use the velocity it chose rather than the one it
     * was flying a tick ago, and the yaw it will be holding rather than the one it has. That second one is
     * what turns the frame, so it is what makes the slots sweep, and the sweep, not the translation, is
     * most of what a follower on the outside of a turn has to fly.
     *
     * <p>The position stays measured. There is nothing else available: this model's whole premise is that
     * the leader is where the formation is.
     */
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
