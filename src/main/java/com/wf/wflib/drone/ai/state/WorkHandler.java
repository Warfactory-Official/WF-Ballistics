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

/** On station over one block of a work job: place it, or take it down. */
public final class WorkHandler implements DroneStateHandler {

    public static final WorkHandler INSTANCE = new WorkHandler();

    private WorkHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.WORK;
    }

    /**
     * @return true once the drone is stopped over the block it claimed.
     */
    private static boolean onStation(DroneSnapshot self) {
        WorkAssignment work = self.assignment();
        if (work == null || !work.hasOrder()) {
            return false;
        }
        Vec3 over = Vec3.atCenterOf(work.order());
        boolean above = self.horizontalDistanceTo(over) <= Tuning.RELEASE_RADIUS
                && self.pos().y - over.y <= Tuning.WORK_HEIGHT + 1.0
                && self.pos().y > over.y;
        return above && (self.velocity().horizontalDistance() <= Tuning.RELEASE_DRIFT
                || self.stateTicks() >= Tuning.SETTLE_TIMEOUT);
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        WorkAssignment work = self.assignment();
        if (work == null) {
            return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
        }
        if (!work.siteOpen()) {
            return DroneState.EXFIL;
        }
        if (!work.hasOrder()) {
            return work.hasStation() ? DroneState.TRANSIT : DroneState.EXFIL;
        }
        return null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        WorkAssignment work = self.assignment();
        if (work == null || !work.hasOrder() || !work.siteOpen()) {
            return;
        }
        if (self.stateTicks() == 0) {
            out.add(new DroneAction.Log(WFEventType.CARGO_DROP, "working " + work.order().toShortString()));
        }
        if (onStation(self)) {
            out.add(new DroneAction.FinishWork(work.order()));
        }
    }

    /** Hold above the block, at working height. */
    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        WorkAssignment work = self.assignment();
        if (work == null || !work.hasOrder()) {
            return Steering.hold(self.pos(), self.pos(), self.cruiseY(), 0.0, self.climbRate(),
                    self.airframe());
        }
        Vec3 over = Vec3.atCenterOf(work.order());
        return Steering.hold(self.pos(), over, over.y + Tuning.WORK_HEIGHT, self.cruiseSpeed() * 0.4,
                self.climbRate(), self.airframe());
    }

    @Override
    public String id() {
        return "work";
    }
}
