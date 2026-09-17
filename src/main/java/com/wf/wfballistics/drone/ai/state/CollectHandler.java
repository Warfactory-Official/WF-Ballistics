package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Over a rendezvous, going down for a crate somebody else left. */
public final class CollectHandler implements DroneStateHandler {

    public static final CollectHandler INSTANCE = new CollectHandler();

    private CollectHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.COLLECT;
    }

    /**
     * @return true once the drone is settled low over the pickup point.
     */
    private static boolean atPickupPoint(DroneSnapshot self) {
        if (!self.hasMission()) {
            return false;
        }
        boolean overIt = self.horizontalDistanceTo(self.destination()) <= Tuning.RELEASE_RADIUS
                && self.altitudeAboveGround() <= Tuning.DROP_HEIGHT + 0.5;
        return overIt && (self.velocity().horizontalDistance() <= Tuning.RELEASE_DRIFT
                || self.stateTicks() >= Tuning.SETTLE_TIMEOUT);
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.zoneClear()) {
            return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
        }
        if (self.hasCargo()) {
            return Programs.afterTask(self);
        }
        return atPickupPoint(self) ? Programs.afterTask(self) : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (!self.zoneClear()) {
            if (self.stateTicks() == 0 || !self.hasCargo()) {
                out.add(new DroneAction.Log(WFEventType.EXFIL, "zone occupied, leaving empty"));
                out.add(new DroneAction.AbortProgram("pickup zone occupied"));
            }
            return;
        }
        if (self.hasCargo()) {
            Programs.advance(self, out);
            return;
        }
        if (atPickupPoint(self)) {
            out.add(new DroneAction.PickUpCargo(self.destination()));
            out.add(new DroneAction.Log(WFEventType.CARGO_PICKUP, "collected"));
            Programs.advance(self, out);
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 over = self.hasMission() ? self.destination() : self.pos();
        double pickupY = self.groundY() + Tuning.DROP_HEIGHT;
        return Steering.hold(self.pos(), over, pickupY, self.cruiseSpeed() * 0.4, self.climbRate(),
                self.airframe());
    }

    @Override
    public String id() {
        return "collect";
    }
}
