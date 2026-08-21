package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Flat battery, still in the air. No guidance and no station-keeping: it sinks under its own weight with
 * the rotors windmilling, keeps whatever momentum it had, and comes to rest on the ground as an
 * {@link DroneState#IDLE} drone that can be recovered and recharged. Unlike a missile running dry, nothing
 * detonates.
 */
public final class DepletedHandler implements DroneStateHandler {

    public static final DepletedHandler INSTANCE = new DepletedHandler();

    private DepletedHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.DEPLETED;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        return self.altitudeAboveGround() <= Tuning.LAND_CONTACT ? DroneState.IDLE : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
            out.add(new DroneAction.Log(WFEventType.IDLE, "grounded, out of power"));
            out.add(new DroneAction.CompleteMission("out of power"));
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 vel = self.velocity();
        return new Vec3(vel.x * Tuning.SINK_DRAG, -Tuning.SINK_RATE, vel.z * Tuning.SINK_DRAG);
    }

    @Override
    public String id() {
        return "depleted";
    }
}
