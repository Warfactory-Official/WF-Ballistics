package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import net.minecraft.world.phys.Vec3;

/**
 * The reference a squad is flown against for one tick: where the formation is anchored, how that anchor is
 * moving, and which way it is facing. Every slot in every {@link com.wf.wfballistics.drone.squad.Formation}
 * is measured off this and nothing else.
 *
 * <p><b>What distinguishes one {@link CoordinationModel} from another is entirely where this comes from.</b>
 * {@link LeaderFollower} fills it in from the leader's measured state, so it inherits every wobble the leader
 * has; {@link VirtualStructure} integrates it forward from the mission and never reads a drone's position at
 * all, so it has no wobble to inherit. The geometry downstream is identical either way: that is the point of
 * splitting it out.
 *
 * <p>Carries the derivatives, not just the position, because a follower that only knows where its station
 * <em>is</em> can never be on it while it is moving: the only way a position controller commands speed is by
 * being out of position. Velocity feeds forward so a settled follower is simply told to fly what its station
 * is flying, and acceleration feeds forward because on a multirotor acceleration <em>is</em> attitude: see
 * {@code Multirotor}. Knowing the reference acceleration means the airframe can start leaning into a turn
 * before any error has had time to build.
 *
 * @param pos          where the frame's origin is. Slot 0 of every formation resolves here
 * @param velocity     how fast the origin is travelling, blocks/tick
 * @param acceleration how fast that velocity is changing, blocks/tick². Zero for a frame in steady flight
 * @param yaw          the direction the frame is built along, radians
 * @param yawRate      how fast the frame is turning, radians/tick. This is what makes the slots <em>sweep</em>
 *                     as well as travel, and at any real spacing it is the larger part of what a follower on
 *                     the outside of a turn has to fly
 */
public record SquadAnchor(Vec3 pos, Vec3 velocity, Vec3 acceleration, float yaw, float yawRate) {

    /**
     * @return an anchor sitting on a drone exactly as it is now, with no rotation and no acceleration. The
     * seed every model starts from on the first tick of a flight, and what one falls back to when its own
     * reference has become nonsense.
     */
    public static SquadAnchor on(DroneSnapshot drone) {
        return new SquadAnchor(drone.pos(), drone.velocity(), Vec3.ZERO, drone.yaw(), 0.0f);
    }

    /**
     * @return this anchor moved to {@code pos} with everything else kept.
     */
    public SquadAnchor at(Vec3 pos) {
        return new SquadAnchor(pos, velocity, acceleration, yaw, yawRate);
    }

    /**
     * @return where the origin will be {@code ticks} from now, on a constant-acceleration extrapolation.
     */
    public Vec3 posAfter(double ticks) {
        return pos.add(velocity.scale(ticks)).add(acceleration.scale(0.5 * ticks * ticks));
    }

    /**
     * @return the heading {@code ticks} from now, on a constant yaw-rate extrapolation. Deliberately not
     * wrapped: it is only ever fed to sin/cos, which do not care, and wrapping it here would put a seam in
     * the middle of the finite differences {@link Slots} takes across it.
     */
    public float yawAfter(double ticks) {
        return (float) (yaw + yawRate * ticks);
    }
}
