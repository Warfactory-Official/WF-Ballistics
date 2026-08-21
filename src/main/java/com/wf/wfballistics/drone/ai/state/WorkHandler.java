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
 * On station over one block of a work job: place it, or take it down.
 *
 * <p>The same handler for both directions, because from the air they are the same manoeuvre: get over the
 * spot, stop moving, and tell the world thread you are there. What actually happens to the block is
 * {@code BuildPilot}'s, on the world thread, for the reason every world change in this AI is: a handler runs
 * off a snapshot on a worker and cannot touch a {@code Level}.
 *
 * <p>Settles before it works, like {@link DeliverHandler}, and for a related reason. A drone still sliding
 * sideways is a drone whose next order is claimed from the wrong place, and, for a demolition, one whose
 * dropped items land somewhere it is no longer hovering.
 */
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

    /**
     * Hold above the block, at working height.
     *
     * <p>Measured from the <em>block</em> and not from the ground under the drone, unlike almost everything
     * else that descends. The two are the same thing on flat ground and very different halfway up a tower,
     * and the height that matters is the one over the thing being worked on.
     */
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
