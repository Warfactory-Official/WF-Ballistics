package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.squad.Formation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** No slots at all: the squad is a flock, and its shape is whatever falls out of three rules. */
public final class Flocking implements CoordinationModel {

    public static final Flocking INSTANCE = new Flocking();

    /**
     * How far apart the flock tries to sit, as a fraction of the squad's ordered spacing, and how hard it pushes to
     * get there.
     */
    public static final double SEPARATION_FRACTION = 0.9;
    public static final double SEPARATION_GAIN = 0.35;
    /** How strongly a drone is drawn toward the middle of the flock, as a fraction of the offset per tick. */
    public static final double COHESION_GAIN = 0.04;
    /** How much of the flock's mean velocity a drone adopts, against its own. */
    public static final double ALIGNMENT = 0.35;
    /** How strongly the flock is drawn along after the leader, and how far behind it settles. */
    public static final double MIGRATION_GAIN = 0.10;
    public static final double MIGRATION_STANDOFF = 1.5;
    /** How far a drone may stray before cohesion is all it is allowed to think about. */
    public static final double STRAGGLER_RANGE = 48.0;

    private Flocking() {
    }

    @Override
    public String id() {
        return "flocking";
    }

    /** The flock's own centre and mean motion. */
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

    /** A flock is formed up when it is <em>together</em>, which is the only shape it has. */
    @Override
    public boolean formedUp(SquadView squad, @Nullable SquadAnchor anchor) {
        if (anchor == null) {
            return false;
        }
        for (DroneSnapshot member : squad.slots()) {
            if (!member.state().powered()) {
                continue;
            }
            if (member.pos().distanceToSqr(anchor.pos()) > STRAGGLER_RANGE * STRAGGLER_RANGE) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the push away from squadmates that are closer than the flock wants to sit. Falls off linearly
     *      to nothing at the comfortable distance, so a flock at rest is not fighting itself: see
     *      {@link #SEPARATION_FRACTION} for why this exists at all when {@code Cruising#separation} is already
     *      applied to every model.
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
