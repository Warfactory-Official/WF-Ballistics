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

/**
 * Form-up: hold over the launch point until the whole flight is up, in place and steady, then go together.
 *
 * <p>A squad launches one drone at a time, see {@code DroneLaunchQueue}, and without this the first one up
 * simply leaves. Everything downstream then spends the outbound leg compensating: the formation is built
 * around a leader that is already hundreds of blocks ahead, the stragglers fly the whole way at overspeed
 * trying to close a gap that was never a station-keeping error in the first place, and a squad ordered to
 * arrive together does not. Waiting costs a few seconds of battery at the start and saves all of it.
 *
 * <p>The wait is <b>not</b> the same question as "has everyone spawned". A drone that has spawned but is
 * still climbing is not ready, and one that has climbed but is nowhere near its station will be left behind
 * the moment the flight accelerates. Both are asked, of every member.
 *
 * <p>What is deliberately <em>not</em> asked is whether anybody has stopped moving. That was the first
 * version and it is a trap: a close formation never stops moving, because station-keeping and separation
 * settle into a limit cycle rather than a fixed point, so a speed test holds every tight flight over the pad
 * until the timeout while it sits there in perfect shape. See {@link Tuning#MUSTER_IN_PLACE}.
 *
 * <p><b>Nor is "in formation" a question this class is entitled to answer.</b> It used to be answered here,
 * as the {@link com.wf.wfballistics.drone.ai.coord.LeaderFollower} geometry written out by hand, and that is
 * a hard-coded architecture in the middle of a state handler that is meant to work under all of them. Two
 * flights could never satisfy it and so held over the pad for the whole {@link Tuning#MUSTER_TIMEOUT}, every
 * launch: a flock, which has no slots to be near, and any squad ordered into a formation tighter than the
 * avoidance rule's own radius, whose drones were being shoved off the very slots they were being measured
 * against. It is {@link CoordinationModel#formedUp} now.
 */
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
     *
     * <p>Asked by {@link TakeoffHandler} at the top of the climb-out, and again here every tick. A solo
     * drone, or one whose squad is already complete and airborne, never waits at all, so a flight launched
     * all at once behaves exactly as it did before any of this existed.
     */
    public static boolean required(DroneSnapshot self, SquadView squad) {
        return self.hasMission() && self.squadSize() > 1 && self.assignment() == null && !ready(self, squad);
    }

    /**
     * @return true when the whole flight is up and steady.
     *
     * <p>{@code squadSize} is what the mission ordered, not what has turned up, and the difference is the
     * whole point: a squad of six with two still on the pad has a {@link SquadView} of four and would
     * otherwise look complete to itself.
     *
     * <p>Two questions, and each is now asked of whoever owns the answer.
     *
     * <p><b>Has everyone climbed out?</b> By its state, not by re-deriving it from an altitude.
     * {@link TakeoffHandler} already decides that, with this same threshold, and a drone that has finished
     * climbing has left {@link DroneState#TAKEOFF} — so asking the altitude again here was a second copy of
     * one rule, and the copies disagreed for any model that does not hold every member at cruise altitude.
     * Under {@code Flocking} cohesion is entitled to pull a drone below cruise, which is not a drone that has
     * failed to climb out, and reading it as one held the flight until the timeout. A member still
     * {@link DroneState#IDLE} on the pad counts as not up too, which the old altitude loop skipped over
     * entirely because {@code IDLE} is not powered.
     *
     * <p><b>Is everyone in formation?</b> {@link CoordinationModel#formedUp} — because "in formation" means
     * something different to each architecture, and the shape this used to measure belonged to exactly one of
     * them.
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
            // Naming the gate, because "timed out" on its own is the same message whether a drone was shot
            // down on the pad or the flight was ordered into a shape it cannot hold, and those want opposite
            // responses from whoever is reading it. `/wfballistics drone muster` has the per-slot detail.
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

    /**
     * Stay put, at cruise altitude.
     *
     * <p>Holding the drone's <em>own</em> position rather than a rally point it was given, because it has
     * just climbed vertically out of wherever it launched from, so its own position already is the launch
     * point, exactly, with no field to keep in step and nothing to be wrong about for a mission whose exfil
     * has been set somewhere else entirely.
     *
     * <p>Only the leader's copy of this is ever flown. The followers are on stations by then, so the flight
     * assembles into its actual formation while it waits instead of milling about and re-forming on
     * departure.
     */
    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        return Steering.hold(self.pos(), self.pos(), self.cruiseY(), 0.0, self.climbRate(), self.airframe());
    }

    @Override
    public String id() {
        return "muster";
    }
}
