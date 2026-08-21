package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Climb-out: straight up to cruise altitude before heading anywhere, so a drone doesn't drag its crate
 * through whatever it was parked next to.
 */
public final class TakeoffHandler implements DroneStateHandler {

    public static final TakeoffHandler INSTANCE = new TakeoffHandler();

    private TakeoffHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.TAKEOFF;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.hasMission()) {
            return DroneState.LANDING;
        }
        if (self.altitudeAboveGround() < self.cruiseAltitude() * Tuning.CLIMB_COMPLETE) {
            return null;
        }
        return MusterHandler.required(self, squad) ? DroneState.MUSTER : DroneState.TRANSIT;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.stateTicks() == 0) {
            out.add(new DroneAction.Log(WFEventType.TAKEOFF, "climb to " + (int) self.cruiseAltitude() + " AGL"));
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        return new Vec3(0.0, Steering.verticalTo(self.pos().y, self.cruiseY(), self.climbRate()), 0.0);
    }

    @Override
    public String id() {
        return "takeoff";
    }
}
