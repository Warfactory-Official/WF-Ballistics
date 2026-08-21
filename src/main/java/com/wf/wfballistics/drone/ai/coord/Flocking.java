package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * No slots at all: the squad is a flock, and its shape is whatever falls out of three rules.
 *
 * <p>The behaviour-based architecture, and Reynolds' boids almost unchanged: cohesion pulls a drone toward
 * the rest of the group, alignment matches its velocity to theirs, and separation (applied by
 * {@code DroneBrain} for every model, so it is not repeated here) keeps them from touching. A fourth term,
 * migration, is what stops a flock that is beautifully coordinated about going nowhere: it is the pull
 * toward the leader, which is the one drone still flying the actual mission.
 *
 * <p><b>It will not hold a formation and it is not meant to.</b> There is no defined shape to hold, so there
 * is no error to measure, and asking it for the precision {@link VirtualStructure} gives is asking the wrong
 * question. What it has instead is that nothing can break it: no anchor to drift, no slot to be unreachable,
 * no leader whose noise becomes everybody's problem, and no assumption at all about how many drones there
 * are or where they started. A squad scattered across half a kilometre by a near miss re-gathers under this
 * when a slot-based model would still be flying each survivor at a station on the far side of the group.
 *
 * <p>Useful as a swarm (drones milling over an area, a search, an escort that only has to stay roughly
 * together) and as the thing to fall back on when the terrain is bad enough that holding a rigid shape is
 * fighting the ground rather than flying.
 */
public final class Flocking implements CoordinationModel {

    public static final Flocking INSTANCE = new Flocking();

    /**
     * How far apart the flock tries to sit, as a fraction of the squad's ordered spacing, and how hard it
     * pushes to get there.
     *
     * <p>The term that decides what size the flock is, and the one it is easiest to leave out. Cohesion has
     * no preferred distance in it, it pulls toward the centroid and keeps pulling, so a flock with only
     * cohesion collapses to a point and stays there. The shared {@code Tuning#SEPARATION_RADIUS} does not
     * save it either: that is a hull-clearance backstop measured in single-figure blocks, so the flock simply
     * settles at the width of the backstop and flies the whole mission grinding against it. Measured, that
     * was every drone touching another on essentially every tick of a two-thousand-tick leg.
     *
     * <p>Scaled off the squad's spacing so the one number a player sets means the same thing here as it does
     * to a formation: order a loose flight and you get a loose flight, rather than the same huddle either
     * way.
     */
    public static final double SEPARATION_FRACTION = 0.9;
    public static final double SEPARATION_GAIN = 0.35;
    /**
     * How strongly a drone is drawn toward the middle of the flock, as a fraction of the offset per tick.
     * Gentle, and much weaker than the separation it is balanced against: cohesion that is too eager wins the
     * argument close in, and the group spends the flight breathing in and out.
     */
    public static final double COHESION_GAIN = 0.04;
    /**
     * How much of the flock's mean velocity a drone adopts, against its own. This is the term that makes a
     * flock look like a flock rather than a crowd: it propagates a turn through the group faster than
     * cohesion alone could.
     */
    public static final double ALIGNMENT = 0.35;
    /**
     * How strongly the flock is drawn along after the leader, and how far behind it settles.
     *
     * <p>Aimed at a point short of the leader rather than at the leader itself, so the flock trails it rather
     * than piling onto it: with no slots there is nothing else keeping the group off the one drone
     * everybody is attracted to.
     */
    public static final double MIGRATION_GAIN = 0.10;
    public static final double MIGRATION_STANDOFF = 1.5;
    /**
     * How far a drone may stray before cohesion is all it is allowed to think about. Beyond this the
     * alignment and migration terms are dropped: a straggler's job is to rejoin, and matching the group's
     * velocity while it is a hundred blocks adrift is how it stays a hundred blocks adrift.
     */
    public static final double STRAGGLER_RANGE = 48.0;

    private Flocking() {
    }

    @Override
    public String id() {
        return "flocking";
    }

    /**
     * The flock's own centre and mean motion. There is no rigid frame here, so this is a summary of the group
     * rather than a reference anybody is being held to, but {@link SquadAnchor} is the right shape for it,
     * and computing it once per tick rather than once per drone is worth having.
     */
    @Override
    public SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous) {
        Vec3 centre = Vec3.ZERO;
        Vec3 motion = Vec3.ZERO;
        for (DroneSnapshot member : squad.slots()) {
            centre = centre.add(member.pos());
            motion = motion.add(member.velocity());
        }
        if (squad.size() == 0) {
            return SquadAnchor.on(squad.leader());
        }
        double scale = 1.0 / squad.size();
        centre = centre.scale(scale);
        motion = motion.scale(scale);
        Vec3 acceleration = previous == null ? Vec3.ZERO : motion.subtract(previous.velocity());
        float yaw = Steering.faceTravel(motion, previous == null ? squad.leader().yaw() : previous.yaw(),
                squad.leader().cruiseSpeed() * Steering.HEADING_MIN_SPEED);
        float yawRate = previous == null ? 0.0f : Steering.wrap(yaw - previous.yaw());
        return new SquadAnchor(centre, motion, acceleration, yaw, yawRate);
    }

    @Override
    public SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        DroneSnapshot leader = squad.leader();
        Vec3 toCentre = anchor.pos().subtract(self.pos());
        boolean straggling = toCentre.horizontalDistanceSqr() > STRAGGLER_RANGE * STRAGGLER_RANGE;

        Vec3 velocity = toCentre.scale(COHESION_GAIN).add(spread(self, squad));
        if (!straggling) {
            velocity = velocity.add(anchor.velocity().scale(ALIGNMENT));
            Vec3 behind = leader.pos().subtract(
                    Formation.forward(leader.yaw()).scale(squad.spacing() * MIGRATION_STANDOFF));
            velocity = velocity.add(behind.subtract(self.pos()).scale(MIGRATION_GAIN));
        }

        double cap = Math.max(self.cruiseSpeed(), anchor.velocity().horizontalDistance())
                * Tuning.FORMATION_MAX_OVERSPEED;
        double horizontal = velocity.horizontalDistance();
        if (horizontal > cap) {
            velocity = new Vec3(velocity.x * cap / horizontal, velocity.y, velocity.z * cap / horizontal);
        }
        double vy = Mth.clamp(velocity.y, -self.airframe().maxDescentRate(), self.airframe().maxClimbRate());
        return SquadCommand.of(new Vec3(velocity.x, vy, velocity.z));
    }

    /**
     * @return the push away from squadmates that are closer than the flock wants to sit. Falls off linearly
     * to nothing at the comfortable distance, so a flock at rest is not fighting itself: see
     * {@link #SEPARATION_FRACTION} for why this exists at all when {@code Cruising#separation} is already
     * applied to every model.
     */
    private static Vec3 spread(DroneSnapshot self, SquadView squad) {
        double want = squad.spacing() * SEPARATION_FRACTION;
        if (want <= 1.0E-4) {
            return Vec3.ZERO;
        }
        Vec3 push = Vec3.ZERO;
        for (DroneSnapshot other : squad.slots()) {
            if (other.id().equals(self.id())) {
                continue;
            }
            Vec3 delta = self.pos().subtract(other.pos());
            double distance = delta.length();
            if (distance > want) {
                continue;
            }
            if (distance < 1.0E-3) {
                double angle = squad.indexOf(self) * 2.399963;
                push = push.add(Math.cos(angle) * want * SEPARATION_GAIN, 0.0,
                        Math.sin(angle) * want * SEPARATION_GAIN);
                continue;
            }
            push = push.add(delta.scale((want - distance) * SEPARATION_GAIN / distance));
        }
        return push;
    }
}
