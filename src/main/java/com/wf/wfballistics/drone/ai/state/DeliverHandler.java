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

/**
 * Over the destination: descend to release height, drop the crate, then head home, or, if the battery can't cover
 * the return leg, land here and park.
 */
public final class DeliverHandler implements DroneStateHandler {

    public static final DeliverHandler INSTANCE = new DeliverHandler();

    private DeliverHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.DELIVER;
    }

    /**
     * @return true once the drone is settled over the destination and low enough to let go of the crate.
     */
    private static boolean atReleasePoint(DroneSnapshot self) {
        if (!self.hasMission()) {
            return true;
        }
        boolean overTheSpot = self.horizontalDistanceTo(self.destination()) <= Tuning.RELEASE_RADIUS
                && self.altitudeAboveGround() <= Tuning.DROP_HEIGHT + 0.5;
        if (!overTheSpot) {
            return false;
        }
        return self.velocity().horizontalDistance() <= Tuning.RELEASE_DRIFT
                || self.stateTicks() >= Tuning.SETTLE_TIMEOUT;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.zoneClear()) {
            return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
        }
        if (!atReleasePoint(self)) {
            return null;
        }
        return Programs.afterTask(self);
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (!self.zoneClear()) {
            if (self.stateTicks() == 0) {
                out.add(new DroneAction.Log(WFEventType.EXFIL, "drop aborted, zone occupied"));
                out.add(new DroneAction.AbortProgram("drop zone occupied"));
            }
            return;
        }
        if (!atReleasePoint(self)) {
            return;
        }
        Programs.advance(self, out);
        if (self.hasCargo()) {
            Vec3 at = self.hasMission()
                    ? new Vec3(self.destination().x, self.groundY(), self.destination().z)
                    : self.pos();
            out.add(new DroneAction.DropCargo(at));
            out.add(new DroneAction.Log(WFEventType.CARGO_DROP, "delivered"));
        }
        if (!PowerPolicy.canReach(self, self.exfil())) {
            out.add(new DroneAction.Log(WFEventType.POWER_LOW,
                    String.format("one-way trip, %.0f%% left", 100.0 * self.charge() / self.capacity())));
            out.add(new DroneAction.AbortProgram("stranded at destination"));
            out.add(new DroneAction.CompleteMission("stranded at destination"));
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 over = self.hasMission() ? self.destination() : self.pos();
        double releaseY = self.groundY() + Tuning.DROP_HEIGHT;
        return Steering.hold(self.pos(), over, releaseY, self.cruiseSpeed() * 0.4, self.climbRate(), self.airframe());
    }

    @Override
    public String id() {
        return "deliver";
    }
}
