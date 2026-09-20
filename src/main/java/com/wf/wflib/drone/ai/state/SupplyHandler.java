package com.wf.wflib.drone.ai.state;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DroneStateHandler;
import com.wf.wflib.drone.ai.PowerPolicy;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.Steering;
import com.wf.wflib.work.WorkAssignment;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Over a station, exchanging cargo with it: loading building materials, or handing over what a demolition
 * recovered.
 */
public final class SupplyHandler implements DroneStateHandler {

    public static final SupplyHandler INSTANCE = new SupplyHandler();

    private SupplyHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.SUPPLY;
    }

    private static boolean onStation(DroneSnapshot self) {
        WorkAssignment work = self.assignment();
        if (work == null || !work.hasStation()) {
            return false;
        }
        boolean over = self.horizontalDistanceTo(work.station()) <= Tuning.RELEASE_RADIUS
                && self.altitudeAboveGround() <= Tuning.DROP_HEIGHT + 1.0;
        return over && (self.velocity().horizontalDistance() <= Tuning.RELEASE_DRIFT
                || self.stateTicks() >= Tuning.SETTLE_TIMEOUT);
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        WorkAssignment work = self.assignment();
        if (work == null) {
            return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
        }
        if (!work.hasStation()) {
            return work.hasOrder() && work.siteOpen() ? DroneState.TRANSIT : DroneState.EXFIL;
        }
        return null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        WorkAssignment work = self.assignment();
        if (work == null || !work.hasStation()) {
            return;
        }
        if (onStation(self)) {
            out.add(new DroneAction.ExchangeSupplies());
        } else if (self.stateTicks() == Tuning.SETTLE_TIMEOUT * 3) {
            out.add(new DroneAction.Log(WFEventType.CARGO_PICKUP, "cannot settle over the station"));
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        WorkAssignment work = self.assignment();
        Vec3 over = work != null && work.hasStation() ? work.station() : self.pos();
        return Steering.hold(self.pos(), over, self.groundY() + Tuning.DROP_HEIGHT,
                self.cruiseSpeed() * 0.4, self.climbRate(), self.airframe());
    }

    @Override
    public String id() {
        return "supply";
    }
}
