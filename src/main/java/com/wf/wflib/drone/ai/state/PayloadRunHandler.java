package com.wf.wflib.drone.ai.state;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DroneStateHandler;
import com.wf.wflib.drone.ai.PowerPolicy;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.Steering;
import com.wf.wflib.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The attack run. */
public final class PayloadRunHandler implements DroneStateHandler {

    public static final PayloadRunHandler INSTANCE = new PayloadRunHandler();

    private PayloadRunHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.PAYLOAD_RUN;
    }

    /** This drone's own aim point. */
    public static Vec3 aimPoint(DroneSnapshot self, SquadView squad) {
        Vec3 destination = self.destination();
        if (destination == null || squad.size() <= 1) {
            return destination;
        }
        Vec3 offset = Formations.get(squad.formationId())
                .slot(squad.indexOf(self), Vec3.ZERO, runInFrame(squad, destination), squad.spacing());
        return destination.add(offset.scale(Tuning.PAYLOAD_AIM_SPREAD));
    }

    /**
     * @return the horizontal direction the whole flight is running in on.
     */
    private static Vec3 runInFrame(SquadView squad, Vec3 destination) {
        Vec3 from = squad.leader().pos();
        double dx = destination.x - from.x;
        double dz = destination.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(dx / horizontal, 0.0, dz / horizontal);
    }

    /**
     * @return the horizontal distance at which this drone should release, given how fast and how high it is.
     */
    private static double releaseRange(DroneSnapshot self) {
        double height = self.pos().y - self.destinationGroundY();
        Vec3 velocity = self.velocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return Steering.ballisticLead(height, velocity.y, horizontal, Tuning.PAYLOAD_GRAVITY);
    }

    /**
     * @return true when the aim point has come inside the lead distance and the drone is actually up to
     *      speed. Waiting for both is what keeps it from dropping short while still accelerating.
     */
    private static boolean readyToRelease(DroneSnapshot self, SquadView squad) {
        if (!self.hasMission() || !self.hasPayload()) {
            return false;
        }
        Vec3 velocity = self.velocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean upToSpeed = horizontal >= self.releaseSpeed() * Tuning.RELEASE_SPEED_TOLERANCE;
        double range = self.horizontalDistanceTo(aimPoint(self, squad));
        return (upToSpeed && range <= releaseRange(self)) || range <= Tuning.ARRIVAL_RADIUS;
    }

    /** How far ahead the break-off runs out to. */
    private static final double BREAK_OFF_RUN_OUT = 512.0;

    /**
     * @return true once the drone has put enough height between itself and the weapon it released.
     */
    private static boolean brokenOff(DroneSnapshot self) {
        return self.altitudeAboveGround() >= self.cruiseAltitude() + Tuning.BREAK_OFF_CLIMB;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (self.hasPayload()) {
            return null;
        }
        return brokenOff(self) ? Programs.afterTask(self) : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.stateTicks() == 0) {
            out.add(new DroneAction.Log(WFEventType.ATTACK,
                    String.format("attack run at %.2f b/t", self.releaseSpeed())));
        }
        if (self.hasPayload()) {
            if (readyToRelease(self, squad)) {
                out.add(new DroneAction.DropPayload(aimPoint(self, squad)));
                out.add(new DroneAction.Log(WFEventType.CARGO_DROP,
                        String.format("payload away, %.0fm lead", releaseRange(self))));
            }
            return;
        }
        if (brokenOff(self)) {
            Programs.advance(self, out);
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        if (!self.hasPayload()) {
            return breakOff(self);
        }
        Vec3 target = self.hasMission() ? aimPoint(self, squad) : self.exfil();
        Vec3 aim = Routing.aim(self, target);
        return Steering.dash(self.pos(), aim, aim.y, self.releaseSpeed(), self.climbRate());
    }

    /** Egress: hold the run-in heading and climb away from the weapon. */
    private static Vec3 breakOff(DroneSnapshot self) {
        Vec3 velocity = self.velocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        Vec3 ahead = horizontal > 1.0E-4
                ? new Vec3(velocity.x / horizontal, 0.0, velocity.z / horizontal)
                : new Vec3(Math.sin(self.yaw()), 0.0, Math.cos(self.yaw()));
        Vec3 out = self.pos().add(ahead.scale(BREAK_OFF_RUN_OUT));
        return Steering.dash(self.pos(), out, self.cruiseY() + Tuning.BREAK_OFF_CLIMB,
                self.releaseSpeed(), self.climbRate());
    }

    @Override
    public String id() {
        return "payload_run";
    }
}
