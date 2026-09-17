package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;

/** Keeping a squad's drones off one another. */
public final class Cruising {

    private Cruising() {
    }

    /**
     * @return how close two of this squad's drones may get before they push apart.
     */
    public static double separationRadius(SquadView squad) {
        return Math.min(Tuning.SEPARATION_RADIUS, squad.spacing() * Tuning.SEPARATION_SPACING_SHARE);
    }

    /**
     * Keep clear of the rest of the squad.
     *
     * @return a velocity to add to whatever the drone was already doing
     */
    public static Vec3 separation(DroneSnapshot self, SquadView squad) {
        double radius = separationRadius(squad);
        Vec3 push = Vec3.ZERO;
        for (DroneSnapshot other : squad.slots()) {
            if (other.id().equals(self.id())) {
                continue;
            }
            Vec3 delta = self.pos().subtract(other.pos());
            double distance = delta.length();
            if (distance > radius) {
                continue;
            }
            double urgency = 1.0 - distance / radius;
            double flat = delta.horizontalDistance();
            if (flat < 1.0E-3) {
                double angle = squad.indexOf(self) * 2.399963;
                push = push.add(Math.cos(angle) * urgency * Tuning.SEPARATION_STRENGTH, 0.0,
                        Math.sin(angle) * urgency * Tuning.SEPARATION_STRENGTH);
                continue;
            }
            push = push.add(delta.x * urgency * Tuning.SEPARATION_STRENGTH / flat, 0.0,
                    delta.z * urgency * Tuning.SEPARATION_STRENGTH / flat);
        }
        double magnitude = push.length();
        return magnitude > Tuning.SEPARATION_MAX ? push.scale(Tuning.SEPARATION_MAX / magnitude) : push;
    }
}
