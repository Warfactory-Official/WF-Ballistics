package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.DroneState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a drone does while in one {@link DroneState}: the drone counterpart of {@code FlightStage}, and the same
 * contract: a stateless strategy that reads a snapshot and returns intent.
 */
public interface DroneStateHandler {

    /**
     * @return which state this handles.
     */
    DroneState state();

    /**
     * @return the state to switch into this tick, or null to stay. Evaluated before {@link #guide}, so the
     *      state being entered is the one that flies this tick.
     */
    @Nullable
    default DroneState next(DroneSnapshot self, SquadView squad) {
        return null;
    }

    /**
     * @return the desired velocity (blocks/tick) for this tick.
     */
    Vec3 guide(DroneSnapshot self, SquadView squad);

    /** Queue world changes (drop the crate, log an event). */
    default void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
    }

    String id();
}
