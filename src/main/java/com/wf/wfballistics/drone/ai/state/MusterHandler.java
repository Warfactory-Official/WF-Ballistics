package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.coord.CoordinationModel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Form-up: hold over the launch point until the whole flight is up, in place and steady, then go together. */
public final class MusterHandler implements DroneStateHandler {

    public static final MusterHandler INSTANCE = new MusterHandler();

    private MusterHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.MUSTER;
    }

    /**
     * @return true if this drone should wait for the rest of its flight rather than setting off.
     */
    public static boolean required(DroneSnapshot self, SquadView squad) {
        return self.hasMission() && self.squadSize() > 1 && self.assignment() == null && !ready(self, squad);
    }

    /**
     * @return true when the whole flight is up and steady.
     */
    private static boolean ready(DroneSnapshot self, SquadView squad) {
        if (squad.size() < self.squadSize()) {
            return false;
        }
        for (DroneSnapshot member : squad.slots()) {
            if (member.state() == DroneState.IDLE || member.state() == DroneState.TAKEOFF) {
                return false;
            }
        }
        return squad.coordination().formedUp(squad, squad.anchor());
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (!self.hasMission()) {
            return DroneState.LANDING;
        }
        if (ready(self, squad) || self.stateTicks() >= Tuning.MUSTER_TIMEOUT) {
            return DroneState.TRANSIT;
        }
        return null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.stateTicks() == 0) {
            out.add(new DroneAction.Log(WFEventType.TAKEOFF,
                    String.format("holding for the flight (%d of %d up)", squad.size(), self.squadSize())));
        } else if (self.stateTicks() == Tuning.MUSTER_TIMEOUT) {
            out.add(new DroneAction.Log(WFEventType.TAKEOFF, String.format(
                    "form-up timed out (%s), departing with %d", why(self, squad), squad.size())));
        }
    }

    /**
     * @return which of the readiness conditions was still unmet when the wait ran out.
     */
    private static String why(DroneSnapshot self, SquadView squad) {
        if (squad.size() < self.squadSize()) {
            return String.format("only %d of %d ever turned up", squad.size(), self.squadSize());
        }
        for (DroneSnapshot member : squad.slots()) {
            if (member.state() == DroneState.IDLE || member.state() == DroneState.TAKEOFF) {
                return "someone never finished climbing out";
            }
        }
        return "the flight never came together at this spacing";
    }

    /** Stay put, at cruise altitude. */
    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        return Steering.hold(self.pos(), self.pos(), self.cruiseY(), 0.0, self.climbRate(), self.airframe());
    }

    @Override
    public String id() {
        return "muster";
    }
}
