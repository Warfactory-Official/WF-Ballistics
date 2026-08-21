package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;

/**
 * Where a formation slot is and how it is moving, differentiated straight off the {@link SquadAnchor}.
 *
 * <p>Shared by every model that flies slots at all, because getting this wrong is the single most expensive
 * mistake available here and there is no reason to make it more than once.
 *
 * <p><b>A slot's velocity is not the anchor's velocity.</b> Slots are pinned to a frame that rotates, so as
 * the flight comes round they sweep as well as travel: the one on the outside of a turn covers a longer arc
 * in the same time and the one on the inside a shorter one. Feeding a follower the anchor's velocity tells
 * the outside drone to fly too slowly and the inside drone too quickly, and neither can make it up
 * afterwards, because the speed cap they are measured against came from the anchor too. The wider the
 * formation the further out the slots sit, the faster they sweep, and the worse it gets, which is why
 * loosening a formation used to make it fly <em>worse</em>.
 */
public final class Slots {

    private Slots() {
    }

    /**
     * Differentiate a slot's motion across the anchor's own extrapolation.
     *
     * <p>Taken as differences of the slot's position rather than solved as a rotation, and the reason is
     * generality: both come from the same {@link Formation#slot} the drone is actually placed with, so the
     * answer is exact for any shape a formation cares to describe, including one whose slots move relative
     * to each other, with no formation needing to know it is being differentiated. The anchor extrapolates
     * as a quadratic, so the second difference recovers the acceleration exactly rather than approximating
     * it.
     */
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
     * Fly to a slot and stay on it: {@code Steering#station} for the velocity, and the slot's own
     * acceleration passed straight through for the airframe to lean into.
     */
    public static SquadCommand hold(DroneSnapshot self, Track track) {
        return new SquadCommand(
                Steering.station(self.pos(), track.pos(), track.velocity(), self.cruiseSpeed(),
                        Tuning.FORMATION_MAX_OVERSPEED, self.climbRate(), self.airframe()),
                track.acceleration());
    }

    /**
     * @return where the anchor would have to be for {@code member} to be exactly on its station. The inverse
     * of {@link #of}, and what lets a model compare a computed reference against the squad actually flying
     * it without having to know which formation is being flown.
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
     * @param pos          where to be now. Not where to be next tick: the velocity below is already fed
     *                     forward into the answer, and leading the target as well would count it twice and
     *                     park every follower a tick of travel ahead of its station
     * @param velocity     how fast the slot itself is moving, translation and sweep together
     * @param acceleration how fast that is changing: the centripetal term of a turn, mostly, which at any
     *                     real spacing is a large fraction of what the airframe has to give
     */
    public record Track(Vec3 pos, Vec3 velocity, Vec3 acceleration) {
    }
}
