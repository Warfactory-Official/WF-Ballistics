package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.MineLoad;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The mine-laying run. */
public final class MineLayHandler implements DroneStateHandler {

    public static final MineLayHandler INSTANCE = new MineLayHandler();

    /**
     * How far past the end of the lane the drone keeps flying before it is allowed to give up on a rack it could
     * not empty.
     */
    private static final double LANE_OVERRUN = 48.0;
    /** Same run-out the strike break-off uses: a direction to hold, not a place to reach. */
    private static final double BREAK_OFF_RUN_OUT = 512.0;

    private MineLayHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.MINELAY;
    }

    /** This drone's own lay point. */
    public static Vec3 layPoint(DroneSnapshot self, SquadView squad) {
        Vec3 destination = self.destination();
        if (destination == null || squad.size() <= 1) {
            return destination;
        }
        Vec3 offset = Formations.get(squad.formationId())
                .slot(squad.indexOf(self), Vec3.ZERO, runIn(squad, destination), squad.spacing());
        return destination.add(offset.scale(Tuning.PAYLOAD_AIM_SPREAD));
    }

    /**
     * @return the horizontal heading the lane runs along: the leader's run-in, so every drone in the flight
     *      lays parallel rather than each along its own bearing.
     */
    private static Vec3 runIn(SquadView squad, Vec3 destination) {
        Vec3 from = squad.leader()
                .pos();
        double dx = destination.x - from.x;
        double dz = destination.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(dx / horizontal, 0.0, dz / horizontal);
    }

    /**
     * @return how far ahead of itself a mine released now would land, blocks. The same ballistic lead a
     *      strike run solves, and for the same reason: the mine keeps the drone's velocity on the way down.
     */
    private static double releaseLead(DroneSnapshot self) {
        double height = self.pos().y - self.destinationGroundY();
        Vec3 velocity = self.velocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return Steering.ballisticLead(height, velocity.y, horizontal, Tuning.PAYLOAD_GRAVITY);
    }

    /**
     * @return where the drone's release solution currently sits along the lane, signed, measured from the
     *      lay point. Negative means the next mine would land short of the strip.
     */
    public static double alongTrack(DroneSnapshot self, SquadView squad) {
        Vec3 layPoint = layPoint(self, squad);
        if (layPoint == null) {
            return Double.NEGATIVE_INFINITY;
        }
        Vec3 heading = runIn(squad, layPoint);
        Vec3 impact = self.pos()
                .add(heading.scale(releaseLead(self)));
        return (impact.x - layPoint.x) * heading.x + (impact.z - layPoint.z) * heading.z;
    }

    /**
     * @return how many mines the rack owes the ground by now, minus how many are already on it. One or more
     *      means dispense.
     */
    private static boolean mineDue(DroneSnapshot self, SquadView squad) {
        MineLoad rack = self.mines();
        if (rack == null || rack.empty() || !self.hasMission()) {
            return false;
        }
        return rack.dueBy(alongTrack(self, squad)) > rack.laid();
    }

    /**
     * @return true once the drone has flown so far past its own lane that the rest of the rack is going
     *      into its wake. The run has to end somehow even when the drone never got up to speed or the lay point
     *      turned out to be over a cliff.
     */
    private static boolean overrun(DroneSnapshot self, SquadView squad) {
        MineLoad rack = self.mines();
        if (rack == null) {
            return true;
        }
        return alongTrack(self, squad) > rack.alongTrack(rack.capacity() - 1) + LANE_OVERRUN;
    }

    /**
     * @return true once the drone has put enough height between itself and the strip it has just laid.
     *      Measured against cruise altitude rather than counted in ticks, exactly as the strike break-off is.
     */
    private static boolean brokenOff(DroneSnapshot self) {
        return self.altitudeAboveGround() >= self.cruiseAltitude() + Tuning.BREAK_OFF_CLIMB;
    }

    /** @return true when there is still laying to do, whether or not this tick is a dispensing one. */
    private static boolean laying(DroneSnapshot self, SquadView squad) {
        return self.hasMines() && !overrun(self, squad);
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (laying(self, squad)) {
            return null;
        }
        return brokenOff(self) ? Programs.afterTask(self) : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        MineLoad rack = self.mines();
        if (self.stateTicks() == 0 && rack != null) {
            out.add(new DroneAction.Log(WFEventType.ATTACK, String.format(
                    "laying %d x %s over %.0fm at %.2f b/t", rack.capacity(), rack.preset().getPath(),
                    rack.laneLength(), self.releaseSpeed())));
        }
        if (laying(self, squad)) {
            if (mineDue(self, squad)) {
                Vec3 heading = runIn(squad, layPoint(self, squad));
                out.add(new DroneAction.LayMine(layPoint(self, squad)
                        .add(heading.scale(rack.alongTrack(rack.laid())))));
            }
            return;
        }
        if (brokenOff(self)) {
            if (rack != null && !rack.empty()) {
                out.add(new DroneAction.Log(WFEventType.CARGO_DROP,
                        "run overshot with " + rack.remaining() + " still on the rack"));
            }
            Programs.advance(self, out);
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        if (!laying(self, squad)) {
            return breakOff(self);
        }
        Vec3 layPoint = self.hasMission() ? layPoint(self, squad) : self.exfil();
        MineLoad rack = self.mines();
        Vec3 target = rack == null ? layPoint : rack.laneEnd(layPoint, runIn(squad, layPoint));
        Vec3 aim = Routing.aim(self, target);
        return Steering.dash(self.pos(), aim, aim.y, self.releaseSpeed(), self.climbRate());
    }

    /** Egress: hold the run-in heading and climb away from the strip, the way a strike run does. */
    private static Vec3 breakOff(DroneSnapshot self) {
        Vec3 velocity = self.velocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        Vec3 ahead = horizontal > 1.0E-4
                ? new Vec3(velocity.x / horizontal, 0.0, velocity.z / horizontal)
                : new Vec3(Math.sin(self.yaw()), 0.0, Math.cos(self.yaw()));
        Vec3 out = self.pos()
                .add(ahead.scale(BREAK_OFF_RUN_OUT));
        return Steering.dash(self.pos(), out, self.cruiseY() + Tuning.BREAK_OFF_CLIMB,
                self.releaseSpeed(), self.climbRate());
    }

    @Override
    public String id() {
        return "minelay";
    }
}
