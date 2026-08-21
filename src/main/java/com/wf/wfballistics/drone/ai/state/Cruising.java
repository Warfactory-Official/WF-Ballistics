package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;

/**
 * Keeping a squad's drones off one another.
 *
 * <p>What used to live here as well, where each drone should <em>be</em>, is now
 * {@link com.wf.wfballistics.drone.ai.coord.CoordinationModel}, because there turned out to be more than one
 * defensible answer and the choice between them matters more than the shape being flown does. This is the
 * part that is the same whichever architecture is in use: nothing stops two drones occupying the same block,
 * so they have to be told not to.
 */
public final class Cruising {

    private Cruising() {
    }

    /**
     * Keep clear of the rest of the squad.
     *
     * <p>Hulls are solid to one another, see {@code Contacts}, but a collision is a failure, not a plan.
     * By the time two drones are touching they have already been thrown off their slots and had their
     * closing speed taken off them, and a squad launching from one pad or breaking formation for an attack
     * run will do that repeatedly if nothing keeps them apart in the first place. This is the usual flocking
     * separation rule and it is what stops it coming to that: push away from anything inside
     * {@link Tuning#SEPARATION_RADIUS}, harder the closer it is, capped so a crowded drone is nudged out
     * rather than flung. It is also the only thing keeping an off-world squad apart, since a simulated drone
     * has no hull to collide with.
     *
     * <p>Free to compute because the whole squad is planned by one job that already holds every member's
     * snapshot: the reason jobs are per squad rather than per drone.
     *
     * @return a velocity to add to whatever the drone was already doing
     */
    public static Vec3 separation(DroneSnapshot self, SquadView squad) {
        Vec3 push = Vec3.ZERO;
        for (DroneSnapshot other : squad.slots()) {
            if (other.id().equals(self.id())) {
                continue;
            }
            Vec3 delta = self.pos().subtract(other.pos());
            double distance = delta.length();
            if (distance > Tuning.SEPARATION_RADIUS) {
                continue;
            }
            if (distance < 1.0E-3) {
                double angle = squad.indexOf(self) * 2.399963;
                push = push.add(Math.cos(angle) * Tuning.SEPARATION_STRENGTH, 0.0,
                        Math.sin(angle) * Tuning.SEPARATION_STRENGTH);
                continue;
            }
            double urgency = 1.0 - distance / Tuning.SEPARATION_RADIUS;
            push = push.add(delta.scale(urgency * Tuning.SEPARATION_STRENGTH / distance));
        }
        double magnitude = push.length();
        return magnitude > Tuning.SEPARATION_MAX ? push.scale(Tuning.SEPARATION_MAX / magnitude) : push;
    }
}
