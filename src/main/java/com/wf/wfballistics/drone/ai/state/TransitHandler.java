package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Outbound cruise to the delivery destination. Battery aborts are decided upstream by
 * {@code PowerPolicy}, so this only has to fly the leg and hand over on arrival.
 */
public final class TransitHandler implements DroneStateHandler {

    public static final TransitHandler INSTANCE = new TransitHandler();

    private TransitHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.TRANSIT;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.hasMission()) {
            return DroneState.EXFIL;
        }
        if (self.onLeg()) {
            return null;
        }
        DroneState arrival = self.arrivalState();
        double range = self.horizontalDistanceTo(self.destination());
        if (arrival == DroneState.PAYLOAD_RUN) {
            return range <= Tuning.PAYLOAD_RUN_IN ? DroneState.PAYLOAD_RUN : null;
        }
        if (arrival == null) {
            return null;
        }
        double approach = Math.max(Tuning.DELIVER_ENTRY_RADIUS,
                self.airframe().stoppingDistance(self.cruiseSpeed()));
        return range <= approach ? arrival : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, java.util.List<DroneAction> out) {
        if (self.onLeg() && self.horizontalDistanceTo(self.leg()) <= Tuning.LEG_ARRIVAL_RADIUS) {
            out.add(new DroneAction.AdvanceLeg());
            return;
        }
        if (self.arrivalState() == null && self.hasMission()
                && self.horizontalDistanceTo(self.destination()) <= Tuning.LEG_ARRIVAL_RADIUS) {
            Programs.advance(self, out);
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 target = self.onLeg() ? self.leg() : (self.hasMission() ? self.destination() : self.exfil());
        Vec3 aim = Routing.aim(self, target);
        return Steering.cruise(self.pos(), aim, aim.y, self.cruiseSpeed(), self.climbRate());
    }

    @Override
    public String id() {
        return "transit";
    }
}
