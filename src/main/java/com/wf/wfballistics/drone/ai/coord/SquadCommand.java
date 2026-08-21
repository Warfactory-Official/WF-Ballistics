package com.wf.wfballistics.drone.ai.coord;

import net.minecraft.world.phys.Vec3;

/**
 * What a {@link CoordinationModel} asks one drone to do: a velocity to fly, and the rate that velocity is
 * itself changing at.
 *
 * <p>The second half is the part that is easy to leave out and expensive to. {@code Multirotor} turns a
 * velocity error into a lean, so a drone given only a velocity has to <em>fall behind first</em> before it
 * will tip at all, and with an attitude rate limit measured in whole ticks it never catches up while the
 * reference keeps turning. A steady turn is exactly that case: the station's velocity rotates every tick, so
 * the lag never clears and the follower sits permanently outside its slot. Handing the acceleration over
 * separately lets the airframe lean into the turn on the model's say-so instead of waiting to be proved
 * wrong by its own tracking error.
 *
 * @param velocity     the velocity to fly, blocks/tick
 * @param acceleration what the reference is accelerating at, blocks/tick², fed forward into the flight model
 *                     rather than being something the drone is asked to achieve on its own
 */
public record SquadCommand(Vec3 velocity, Vec3 acceleration) {

    /**
     * @return a command with nothing fed forward, for the guidance paths that have no reference to
     * differentiate: a drone flying its own route rather than a station.
     */
    public static SquadCommand of(Vec3 velocity) {
        return new SquadCommand(velocity, Vec3.ZERO);
    }

    public SquadCommand withVelocity(Vec3 velocity) {
        return new SquadCommand(velocity, acceleration);
    }
}
