package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.work.WorkAssignment;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Over a station, exchanging cargo with it: loading building materials, or handing over what a demolition
 * recovered.
 *
 * <p>Descends to the same height a delivery does and settles the same way, but never lets go of a crate:
 * the transfer is item-for-item into the station's own container, so nothing is ever left on the ground for
 * somebody to walk off with. That is the difference between this and {@link DeliverHandler}, and it is why
 * they are not the same handler.
 *
 * <p>Which way the items move is the job's business, not this one's. The world thread knows whether it is
 * running a construction or a demolition; from up here both are "sit still over the station until the
 * assignment changes".
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
