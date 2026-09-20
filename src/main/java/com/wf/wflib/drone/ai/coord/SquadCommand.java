package com.wf.wflib.drone.ai.coord;

import net.minecraft.world.phys.Vec3;

/**
 * What a {@link CoordinationModel} asks one drone to do: a velocity to fly, and the rate that velocity is itself
 * changing at.
 *
 * @param velocity the velocity to fly, blocks/tick
 * @param acceleration what the reference is accelerating at, blocks/tick², fed forward into the flight model
 *      rather than being something the drone is asked to achieve on its own
 */
public record SquadCommand(Vec3 velocity, Vec3 acceleration) {

    /**
     * @return a command with nothing fed forward, for the guidance paths that have no reference to
     *      differentiate: a drone flying its own route rather than a station.
     */
    public static SquadCommand of(Vec3 velocity) {
        return new SquadCommand(velocity, Vec3.ZERO);
    }

    public SquadCommand withVelocity(Vec3 velocity) {
        return new SquadCommand(velocity, acceleration);
    }
}
