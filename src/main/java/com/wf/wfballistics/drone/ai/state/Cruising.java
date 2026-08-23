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
     * @return how close two of this squad's drones may get before they push apart.
     *
     * <p>The hull figure, or the formation's own spacing where that is tighter. Holding this as an invariant
     * rather than asserting it in a comment is the whole change: a rule that insists on more room than the
     * formation allots does not keep a tight squad safe, it keeps it from ever being in formation, and
     * {@code MusterHandler} reads "not in formation" as "not ready to leave".
     */
    public static double separationRadius(SquadView squad) {
        return Math.min(Tuning.SEPARATION_RADIUS, squad.spacing() * Tuning.SEPARATION_SPACING_SHARE);
    }

    /**
     * Keep clear of the rest of the squad.
     *
     * <p>Hulls are solid to one another, see {@code Contacts}, but a collision is a failure, not a plan.
     * By the time two drones are touching they have already been thrown off their slots and had their
     * closing speed taken off them, and a squad launching from one pad or breaking formation for an attack
     * run will do that repeatedly if nothing keeps them apart in the first place. This is the usual flocking
     * separation rule and it is what stops it coming to that: push away from anything inside
     * {@link #separationRadius}, harder the closer it is, capped so a crowded drone is nudged out
     * rather than flung. It is also the only thing keeping an off-world squad apart, since a simulated drone
     * has no hull to collide with.
     *
     * <p><b>Horizontal, which is the glyphid rule and was worth borrowing.</b> {@code GlyphidSeparation}
     * pushes a swarm apart in the plane and never touches the vertical, and drones need that more than
     * glyphids do: a squad launches from one pad and climbs out of it in a column, so the drones nearest each
     * other are the ones stacked one above another, and a three-dimensional push resolves that stack by
     * shoving the lower drone back down. That is the one command a climb-out consists of, answered with its
     * own negation, and at {@link Tuning#SEPARATION_MAX} the push is nearly three times the airframe's climb
     * rate — enough to hold a drone under the flight indefinitely while the flight waits for it. Pushed in the
     * plane instead, the same pair opens out into the shape it was going to have to fly anyway, and the climb
     * is left to the handler that owns it.
     *
     * <p>Free to compute because the whole squad is planned by one job that already holds every member's
     * snapshot: the reason jobs are per squad rather than per drone.
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
            // Nearness is still judged in three dimensions -- a squadmate thirty blocks below is not crowding
            // anybody -- and only the answer is confined to the plane.
            Vec3 delta = self.pos().subtract(other.pos());
            double distance = delta.length();
            if (distance > radius) {
                continue;
            }
            double urgency = 1.0 - distance / radius;
            double flat = delta.horizontalDistance();
            if (flat < 1.0E-3) {
                // Stacked one directly above the other, which is how a squad leaves a shared pad. The angle
                // comes off the slot index for the same reason the glyphid version takes it off the entity
                // id: the two have to disagree about which way to go, and a fresh random every tick would
                // leave them shivering instead of parting.
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
