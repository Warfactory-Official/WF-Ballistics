package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.PowerPolicy;

import java.util.List;

/** What every arrival handler does once its step of the program is finished. */
public final class Programs {

    private Programs() {
    }

    /**
     * @return the state to enter having finished the current step: back to cruise for the next one, or the
     *      end of the mission when there is nothing queued behind it.
     */
    public static DroneState afterTask(DroneSnapshot self) {
        if (self.hasNextTask()) {
            return DroneState.TRANSIT;
        }
        return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
    }

    /** Step the queue on, if there is a queue. */
    public static void advance(DroneSnapshot self, List<DroneAction> out) {
        if (self.currentTask() != null) {
            out.add(new DroneAction.AdvanceTask());
        }
    }
}
