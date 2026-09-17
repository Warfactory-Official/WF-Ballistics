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

/** Controlled descent onto whatever is below, ending the mission. */
public final class LandingHandler implements DroneStateHandler {

    public static final LandingHandler INSTANCE = new LandingHandler();

    private LandingHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.LANDING;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        return self.altitudeAboveGround() <= Tuning.LAND_CONTACT ? DroneState.IDLE : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
            out.add(new DroneAction.Log(WFEventType.LANDED,
                    String.format("%.0f%% charge left", 100.0 * self.charge() / self.capacity())));
            out.add(new DroneAction.CompleteMission("landed"));
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        return Steering.hold(self.pos(), self.pos(), self.groundY(), 0.0, self.climbRate() * 0.6, self.airframe());
    }

    @Override
    public String id() {
        return "landing";
    }
}
