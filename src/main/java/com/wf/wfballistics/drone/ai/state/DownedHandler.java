package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Shot down. The rotors are gone, so there is no lift and no steering: the airframe keeps whatever momentum
 * it had, accelerates downward, and spins about its own axis on the way (the spin itself is applied in
 * {@code DroneBrain}, which owns the yaw). It comes to rest as a wreck and stays one: a downed drone never
 * returns to {@link DroneState#IDLE}, so it can't take off again, but it can still be looted.
 */
public final class DownedHandler implements DroneStateHandler {

    public static final DownedHandler INSTANCE = new DownedHandler();

    private DownedHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.DOWNED;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        return null;
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
            return Vec3.ZERO;
        }
        Vec3 velocity = self.velocity();
        return new Vec3(velocity.x * Tuning.DOWNED_DRAG,
                Math.max(Tuning.DOWNED_TERMINAL, velocity.y - Tuning.DOWNED_GRAVITY),
                velocity.z * Tuning.DOWNED_DRAG);
    }

    @Override
    public String id() {
        return "downed";
    }
}
