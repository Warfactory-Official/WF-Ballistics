package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.PowerPolicy;

import java.util.List;

/**
 * What every arrival handler does once its step of the program is finished.
 *
 * <p>{@code DeliverHandler}, {@code CollectHandler}, {@code PayloadRunHandler} and {@code SurveilHandler} all
 * used to end the same way: go home, or land if home is out of reach. With a queue behind them the answer is
 * one question longer, and it is the same question for all four: is there another step? Keeping it here means
 * a new arrival state gets the behaviour by asking for it rather than by remembering to reimplement it.
 */
public final class Programs {

    private Programs() {
    }

    /**
     * @return the state to enter having finished the current step: back to cruise for the next one, or the
     * end of the mission when there is nothing queued behind it.
     *
     * <p>The battery still has the final say further up in {@code DroneBrain}: this only decides what the
     * drone would like to do next.
     */
    public static DroneState afterTask(DroneSnapshot self) {
        if (self.hasNextTask()) {
            return DroneState.TRANSIT;
        }
        return PowerPolicy.canReach(self, self.exfil()) ? DroneState.EXFIL : DroneState.LANDING;
    }

    /**
     * Step the queue on, if there is a queue. Emitted alongside whatever the handler did to finish the step,
     * so the drone's destination has already moved to the next one by the time the new state flies.
     */
    public static void advance(DroneSnapshot self, List<DroneAction> out) {
        if (self.currentTask() != null) {
            out.add(new DroneAction.AdvanceTask());
        }
    }
}
