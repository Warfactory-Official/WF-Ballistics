package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A worker's answer for one drone: where to move this tick, how it is leaning to do it, which way to face, whether
 * to change state, and anything that has to happen in the world.
 *
 * @param sourceGameTime the tick the snapshot was taken on; a plan older than
 *      {@code DroneAiScheduler#MAX_PLAN_AGE} is dropped and the drone coasts instead
 * @param attitude the lean and throttle the flight model settled on. Carried through rather than
 *      recomputed on the world thread because it is state, not decoration: next tick's
 *      physics starts from the attitude this tick ended at
 * @param nextState the state to switch into, or null to stay put
 */
public record DronePlan(java.util.UUID droneId, long sourceGameTime, Vec3 velocity, FlightAttitude attitude,
                        float yaw, @Nullable DroneState nextState, List<DroneAction> actions) {

    public static DronePlan of(DroneSnapshot self, Vec3 velocity, FlightAttitude attitude, float yaw,
                               @Nullable DroneState nextState, List<DroneAction> actions) {
        return new DronePlan(self.id(), self.gameTime(), velocity, attitude, yaw, nextState, actions);
    }

    /**
     * @return a plan that changes nothing, used when a drone has no handler for its state.
     */
    public static DronePlan idle(DroneSnapshot self) {
        return new DronePlan(self.id(), self.gameTime(), Vec3.ZERO, self.attitude(), self.yaw(), null, List.of());
    }
}
