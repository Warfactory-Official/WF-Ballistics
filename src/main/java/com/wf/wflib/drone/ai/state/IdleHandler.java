package com.wf.wflib.drone.ai.state;

import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DroneStateHandler;
import com.wf.wflib.drone.ai.PowerPolicy;
import com.wf.wflib.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Parked on the ground, waiting for a mission. */
public final class IdleHandler implements DroneStateHandler {

    public static final IdleHandler INSTANCE = new IdleHandler();

    private IdleHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.IDLE;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.hasMission() || self.charge() <= 0.0) {
            return null;
        }
        return PowerPolicy.canReach(self, self.destination()) ? DroneState.TAKEOFF : null;
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        return Vec3.ZERO;
    }

    @Override
    public String id() {
        return "idle";
    }
}
