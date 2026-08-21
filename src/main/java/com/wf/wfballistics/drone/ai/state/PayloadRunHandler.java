package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.squad.Formations;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The attack run. Unlike a delivery, which stops over the destination and lowers its crate, a payload drone
 * stays at altitude, holds its ordered release speed straight down the line, and pickles <em>early</em>: the
 * bomb's own fall carries it the rest of the way onto the aim point.
 *
 * <p>Dropping at speed is the whole point, so this deliberately does not use the arrival slowdown that the
 * cruise states rely on.
 */
public final class PayloadRunHandler implements DroneStateHandler {

    public static final PayloadRunHandler INSTANCE = new PayloadRunHandler();

    private PayloadRunHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.PAYLOAD_RUN;
    }

    /**
     * This drone's own aim point.
     *
     * <p>A flight breaks formation for the run: every drone needs its own release solution, because each
     * reaches its own lead distance at its own moment. Aiming them all at one point then converges them onto
     * it: they arrive together, at the same height, over the same spot, each dropping a live warhead through
     * the others. Fanning the aim points out in the shape of the formation keeps the run separated all the
     * way through the release, and puts the pattern where the pattern was aimed.
     *
     * <p>The offsets are taken in the <em>leader's</em> run-in frame rather than each drone's own, so every
     * member measures its slot against the same axis and the pattern holds together.
     */
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
     * speed. Waiting for both is what keeps it from dropping short while still accelerating.
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

    /**
     * How far ahead the break-off runs out to. Only ever used as a direction to hold, so it wants to be
     * further than the drone could possibly cover before it has finished climbing.
     */
    private static final double BREAK_OFF_RUN_OUT = 512.0;

    /**
     * @return true once the drone has put enough height between itself and the weapon it released.
     *
     * <p>Measured against cruise altitude rather than counted in ticks because the handler is handed a
     * snapshot and nothing else: there is nowhere to keep a timer, and a height the drone can be asked to
     * reach is a better statement of the requirement anyway. It also means a low run-in has to climb the
     * whole way back up before it rejoins, which is right: it released lower, so it has further to go.
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

    /**
     * Egress: hold the run-in heading and climb away from the weapon.
     *
     * <p>Straight ahead rather than at anything, and that is the point of it. The one place the drone must
     * not be is back over the aim point, because that is where its own bomb is going, and steering at a
     * target it has already flown past would turn it round and take it there. Speed is held all the way
     * through the climb for the same reason a run is flown fast in the first place: the object of the
     * manoeuvre is to be somewhere else, and easing off while the weapon is still falling underneath is the
     * opposite of that.
     */
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
