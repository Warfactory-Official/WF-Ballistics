package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;

/** Where a formation slot is and how it is moving, differentiated straight off the {@link SquadAnchor}. */
public final class Slots {

    private Slots() {
    }

    /** Differentiate a slot's motion across the anchor's own extrapolation. */
    public static Track of(Formation shape, int index, SquadAnchor anchor, double spacing) {
        Vec3 s0 = shape.slot(index, anchor.pos(), Formation.forward(anchor.yaw()), spacing);
        Vec3 s1 = shape.slot(index, anchor.posAfter(1.0), Formation.forward(anchor.yawAfter(1.0)), spacing);
        Vec3 s2 = shape.slot(index, anchor.posAfter(2.0), Formation.forward(anchor.yawAfter(2.0)), spacing);
        return new Track(s0, s1.subtract(s0), s2.subtract(s1.scale(2.0)).add(s0));
    }

    /**
     * @return the track of {@code self}'s own slot in this squad's shape.
     */
    public static Track track(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        return of(Formations.get(squad.formationId()), squad.indexOf(self), anchor, squad.spacing());
    }

    /**
     * Fly to a slot and stay on it: {@code Steering#station} for the velocity, and the slot's own acceleration
     * passed straight through for the airframe to lean into.
     */
    public static SquadCommand hold(DroneSnapshot self, Track track) {
        return new SquadCommand(
                Steering.station(self.pos(), track.pos(), track.velocity(), self.cruiseSpeed(),
                        Tuning.FORMATION_MAX_OVERSPEED, self.climbRate(), self.airframe()),
                track.acceleration());
    }

    /**
     * @return where the anchor would have to be for {@code member} to be exactly on its station. The inverse
     *      of {@link #of}, and what lets a model compare a computed reference against the squad actually flying
     *      it without having to know which formation is being flown.
     */
    public static Vec3 impliedAnchor(DroneSnapshot member, SquadView squad, SquadAnchor anchor) {
        Vec3 offset = Formations.get(squad.formationId())
                .slot(squad.indexOf(member), Vec3.ZERO, Formation.forward(anchor.yaw()), squad.spacing());
        return member.pos().subtract(offset);
    }

    /**
     * @return how far the worst-tracking member of the squad is from its station, in blocks.
     */
    public static double worstError(SquadView squad, SquadAnchor anchor) {
        Formation shape = Formations.get(squad.formationId());
        double worst = 0.0;
        for (DroneSnapshot member : squad.slots()) {
            Vec3 slot = shape.slot(squad.indexOf(member), anchor.pos(), Formation.forward(anchor.yaw()),
                    squad.spacing());
            worst = Math.max(worst, member.pos().distanceTo(slot));
        }
        return worst;
    }

    /**
     * One slot's motion.
     *
     * @param pos where to be now. Not where to be next tick: the velocity below is already fed
     *      forward into the answer, and leading the target as well would count it twice and
     *      park every follower a tick of travel ahead of its station
     * @param velocity how fast the slot itself is moving, translation and sweep together
     * @param acceleration how fast that is changing: the centripetal term of a turn, mostly, which at any
     *      real spacing is a large fraction of what the airframe has to give
     */
    public record Track(Vec3 pos, Vec3 velocity, Vec3 acceleration) {
    }
}
