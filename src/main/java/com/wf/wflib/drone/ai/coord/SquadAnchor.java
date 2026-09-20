package com.wf.wflib.drone.ai.coord;

import com.wf.wflib.drone.ai.DroneSnapshot;
import net.minecraft.world.phys.Vec3;

/**
 * The reference a squad is flown against for one tick: where the formation is anchored, how that anchor is moving,
 * and which way it is facing.
 *
 * @param pos where the frame's origin is. Slot 0 of every formation resolves here
 * @param velocity how fast the origin is travelling, blocks/tick
 * @param acceleration how fast that velocity is changing, blocks/tick². Zero for a frame in steady flight
 * @param yaw the direction the frame is built along, radians
 * @param yawRate how fast the frame is turning, radians/tick. This is what makes the slots <em>sweep</em>
 *      as well as travel, and at any real spacing it is the larger part of what a follower on
 *      the outside of a turn has to fly
 */
public record SquadAnchor(Vec3 pos, Vec3 velocity, Vec3 acceleration, float yaw, float yawRate) {

    /**
     * @return an anchor sitting on a drone exactly as it is now, with no rotation and no acceleration. The
     *      seed every model starts from on the first tick of a flight, and what one falls back to when its own
     *      reference has become nonsense.
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
     *      wrapped: it is only ever fed to sin/cos, which do not care, and wrapping it here would put a seam in
     *      the middle of the finite differences {@link Slots} takes across it.
     */
    public float yawAfter(double ticks) {
        return (float) (yaw + yawRate * ticks);
    }
}
