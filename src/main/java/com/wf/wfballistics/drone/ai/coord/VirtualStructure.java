package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateRegistry;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Slots measured off a point that is not a drone.
 *
 * <p>The formation is treated as a rigid body with its own state, and that body is <em>flown</em>: it starts
 * on the squad, integrates forward along the mission each tick, and every drone in the squad, the leader
 * included, holds a station on it. Nobody chases anybody.
 *
 * <p><b>This is how a formation gets tight, and the reason is one sentence: the reference is computed rather
 * than measured.</b> Under {@link LeaderFollower} a follower's error is its own tracking error plus every
 * disturbance the leader suffered, delivered through the lever arm of the spacing. Here the anchor has no
 * tracking error to pass on, because it is not tracking anything, so each drone's error stays its own and
 * errors do not couple. It is the architecture behind drone light shows and the research swarms that hold
 * formation to a hand's breadth: every vehicle independently flies a reference it was given, and the
 * formation is the emergent result of all of them succeeding at the same moment, not of any of them watching
 * the others.
 *
 * <p>The leader still runs its handler, so arrivals, state changes and the program queue all step on exactly
 * as before: it is only the <em>flying</em> that is delegated to a station. What the handler produces is
 * read as the mission's <em>intent</em> for the frame, never as the leader's achieved motion, which is what
 * keeps the leader's own wobble out of everyone else's orders.
 *
 * <p>The cost of a computed reference is that it can be wrong about reality, and the two guards below are
 * the price of admission. A virtual structure that sails on while the drones supposedly flying it are stuck
 * behind a hill is not a reference, it is a rumour.
 */
public final class VirtualStructure implements CoordinationModel {

    public static final VirtualStructure INSTANCE = new VirtualStructure();

    /**
     * How much of the gap between what the frame is doing and what the mission is asking for it to do is
     * closed each tick.
     *
     * <p>Low on purpose: this is the low-pass filter that the whole model is built to provide. The mission's
     * intent is a raw guidance output and it steps about: a route is adopted, a carrot crosses a corner, a
     * terrain clamp bites. Under {@link LeaderFollower} every one of those reached the followers at full
     * strength. Here they move the frame at an eighth of their size per tick, which is a couple of seconds
     * to swing onto a new heading: slower than any drone can turn, so the frame is never asking for
     * something the airframes cannot deliver.
     */
    public static final double STEER_SMOOTHING = 0.12;
    /**
     * How hard the frame is pulled back toward the squad actually flying it, per tick.
     *
     * <p>A concession, and a deliberately small one. Strictly, a virtual structure should ignore where the
     * drones are, but nothing else here bounds the accumulated difference between a frame integrated from
     * intent and a squad that has been shoved about by terrain, collisions and its own tracking error, and
     * an unbounded difference eventually puts every slot somewhere nobody can reach. At this gain the frame
     * is corrected by twentieths of the discrepancy, so measurement noise reaches the followers at a
     * twentieth of the strength it would under {@link LeaderFollower} while the frame still cannot wander
     * off for good.
     *
     * <p>Measured against each member's <em>implied</em> anchor, where the frame would have to be for that
     * drone to be on station, and not against the squad's centroid. The centroid of a wedge sits behind its
     * leader, so pulling the frame toward it would drag the whole formation backwards a little every tick
     * for as long as the flight lasted.
     */
    public static final double LEASH_GAIN = 0.05;
    /**
     * The same correction on the vertical, where it can afford to be five times harder.
     *
     * <p>The reason the horizontal gain is small is that measurement noise there <em>rotates the frame</em>,
     * and a rotating frame swings every slot by the lever arm of the spacing. Altitude does not rotate
     * anything: an error on this axis moves the formation up or down as one body and distorts nothing. The
     * airframe agrees, the rotors answer within a tick where the lean takes several, which is the same
     * reason {@code Steering#station} gives the vertical its own budget instead of sharing one.
     *
     * <p>There is a real fault underneath it, too, even if it turned out to be a small one. The frame
     * integrates the leader handler's intent, and that intent's vertical component was computed for the
     * <em>leader's</em> altitude rather than the frame's: {@code Steering#verticalTo} is proportional, so
     * holding a climb at all means holding a standing altitude error, and the frame was applying the
     * leader's error signal to its own quite different height. Splitting the gain closes that.
     *
     * <p><b>It is not what makes a turning climb this model's weakest leg.</b> That was the theory when this
     * was written and the measurement did not support it: separating the axes moved a mean error of 0.29
     * blocks by 0.01. The remaining gap is the smoothing above lagging a turn rate that keeps changing, which
     * is inherent to filtering the reference and is the price the model pays for everything else it buys.
     * Recorded here so the next person to look does not re-run the same experiment.
     */
    public static final double VERTICAL_LEASH_GAIN = 0.25;
    /**
     * How far the frame may end up from where the squad implies it should be before it is simply put back on
     * the leader and started again. Covers the discontinuities the leash cannot: a drone coming back from
     * unloaded terrain, a squad re-forming around a new leader, a teleport.
     */
    public static final double MAX_DRIFT = 64.0;
    /**
     * How far the worst-placed member may be from its station before the frame starts easing off to let it
     * catch up.
     *
     * <p>This is the formation-feedback loop, and it is what makes a virtual structure a control system
     * rather than an animation. Without it the frame flies the mission at full speed regardless of whether
     * anybody is managing to stay on it, and a squad held up by a climb, a headwind of drag or a battery
     * abort watches its stations recede forever.
     */
    public static final double LAG_TOLERANCE = 8.0;
    /**
     * Slowest the frame will be throttled to while it waits, as a fraction of what the mission asked for. Not
     * zero: a squad that has genuinely lost a member should still complete its mission rather than hover
     * until the battery runs out waiting for a drone that is never coming.
     */
    public static final double MIN_LAG_THROTTLE = 0.2;

    private VirtualStructure() {
    }

    @Override
    public String id() {
        return "virtual_structure";
    }

    @Override
    public boolean stationsLeader() {
        return true;
    }

    @Override
    public SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous) {
        DroneSnapshot leader = squad.leader();
        if (previous == null || !leader.state().powered()) {
            return SquadAnchor.on(leader);
        }

        Vec3 intent = DroneStateRegistry.get(leader.state()).guide(leader, squad)
                .scale(throttle(squad, previous));
        Vec3 velocity = previous.velocity().add(intent.subtract(previous.velocity()).scale(STEER_SMOOTHING));
        Vec3 acceleration = velocity.subtract(previous.velocity());
        Vec3 pos = previous.pos().add(velocity);

        Vec3 implied = impliedAnchor(squad, previous);
        Vec3 pull = implied.subtract(pos);
        if (pull.lengthSqr() > MAX_DRIFT * MAX_DRIFT) {
            return SquadAnchor.on(leader);
        }
        pos = pos.add(pull.x * LEASH_GAIN, pull.y * VERTICAL_LEASH_GAIN, pull.z * LEASH_GAIN);

        float yaw = velocity.horizontalDistance() < leader.cruiseSpeed() * Steering.HEADING_MIN_SPEED
                ? leader.yaw()
                : Steering.faceTravel(velocity, previous.yaw(),
                        leader.cruiseSpeed() * Steering.HEADING_MIN_SPEED);
        return new SquadAnchor(pos, velocity, acceleration, yaw, Steering.wrap(yaw - previous.yaw()));
    }

    @Override
    public SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        return Slots.hold(self, Slots.track(self, squad, anchor));
    }

    /**
     * @return how much of the mission's asking speed the frame may use, given how far behind the worst-placed
     * member of the squad is.
     */
    private static double throttle(SquadView squad, SquadAnchor anchor) {
        double worst = Slots.worstError(squad, anchor);
        if (worst <= LAG_TOLERANCE) {
            return 1.0;
        }
        return Mth.clamp(LAG_TOLERANCE / worst, MIN_LAG_THROTTLE, 1.0);
    }

    /**
     * @return the average of where every member implies the frame ought to be. See {@link #LEASH_GAIN} for
     * why this is not the squad's centroid.
     */
    private static Vec3 impliedAnchor(SquadView squad, SquadAnchor anchor) {
        Vec3 sum = Vec3.ZERO;
        for (DroneSnapshot member : squad.slots()) {
            sum = sum.add(Slots.impliedAnchor(member, squad, anchor));
        }
        return squad.size() == 0 ? anchor.pos() : sum.scale(1.0 / squad.size());
    }
}
