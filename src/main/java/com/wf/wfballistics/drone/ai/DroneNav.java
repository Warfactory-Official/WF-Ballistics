package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.nav.TerrainField;
import org.jetbrains.annotations.Nullable;

/**
 * What a drone knows about the ground, captured on the world thread for the planner.
 *
 * @param path             the route it is currently following, or null if it has none yet
 * @param field            terrain condensed for a replan, present only on the ticks a drone is actually due
 *                         one. Building it is the expensive part, so it is not carried around otherwise
 * @param requiredAltitude the height the drone must already be at to clear what is in front of it, from
 *                         {@code DroneNavigation#requiredAltitude}. Negative infinity means nothing is known
 *                         to be in the way: the sim, where there is no terrain to measure
 */
public record DroneNav(@Nullable DronePath path, @Nullable TerrainField field, double requiredAltitude) {

    /**
     * No route, no terrain, no floor.
     */
    public static final DroneNav NONE = new DroneNav(null, null, Double.NEGATIVE_INFINITY);

    public DroneNav withPath(@Nullable DronePath path) {
        return new DroneNav(path, field, requiredAltitude);
    }

    /**
     * @return true if a replan was asked for this tick and the terrain to do it is here.
     */
    public boolean canPlan() {
        return field != null;
    }
}
