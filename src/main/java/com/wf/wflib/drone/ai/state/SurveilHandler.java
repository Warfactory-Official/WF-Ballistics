package com.wf.wflib.drone.ai.state;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DroneStateHandler;
import com.wf.wflib.drone.ai.PowerPolicy;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.Steering;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** On station over somewhere worth watching. */
public final class SurveilHandler implements DroneStateHandler {

    public static final SurveilHandler INSTANCE = new SurveilHandler();

    /** Radians per tick around the orbit. */
    private static final double ORBIT_RATE = 0.012;
    /** How far ahead around the circle the drone actually steers. */
    private static final double ORBIT_LEAD = 0.35;

    private SurveilHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.SURVEIL;
    }

    /**
     * @return the loiter step being flown, or null if the drone got here without one (a retasked mission, or
     *      a state forced from outside). Falling back to a hold is the safe reading: stay put rather than fly a
     *      circle of unknown size around a point nobody asked about.
     */
    @Nullable
    private static DroneTask.Loiter loiter(DroneSnapshot self) {
        return self.currentTask() instanceof DroneTask.Loiter task ? task : null;
    }

    /**
     * @return true when there is no reason to stay: the ordered time is up, or there is nowhere to be on
     *      station over. The battery's own version of this is upstream in {@code PowerPolicy}, which is what makes
     *      "watch it until you have just enough charge left to get home" the default rather than a special case.
     */
    private static boolean relieved(DroneSnapshot self) {
        if (!self.hasMission()) {
            return true;
        }
        DroneTask.Loiter task = loiter(self);
        return task != null && task.ticks() > 0 && self.stateTicks() >= task.ticks();
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        return relieved(self) ? Programs.afterTask(self) : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.stateTicks() == 0) {
            DroneTask.Loiter task = loiter(self);
            String how = task == null || task.stationary()
                    ? "holding station" : String.format("orbiting at %.0fm", task.radius());
            out.add(new DroneAction.Log(WFEventType.CRUISE, "on station, " + how));
        }
        for (String contact : self.contacts()) {
            out.add(new DroneAction.Log(WFEventType.SPOTTED, "contact: " + contact));
        }
        if (relieved(self)) {
            Programs.advance(self, out);
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 centre = self.hasMission() ? self.destination() : self.pos();
        double station = self.cruiseY();
        DroneTask.Loiter task = loiter(self);
        if (task == null || task.stationary()) {
            return Steering.hold(self.pos(), centre, station, self.cruiseSpeed() * 0.5, self.climbRate(),
                    self.airframe());
        }
        double dx = self.pos().x - centre.x;
        double dz = self.pos().z - centre.z;
        double bearing = (dx * dx + dz * dz) < 1.0E-6
                ? self.yaw() : Math.atan2(dz, dx);
        double aim = bearing + ORBIT_RATE + ORBIT_LEAD;
        Vec3 target = new Vec3(centre.x + Math.cos(aim) * task.radius(), station,
                centre.z + Math.sin(aim) * task.radius());
        return Steering.cruise(self.pos(), target, station, orbitSpeed(self, task), self.climbRate());
    }

    /**
     * @return how fast to fly the circle. Capped so a small orbit is flown slowly enough to actually turn
     *      inside it rather than sailing wide on every lap: the airframe's own turn is what sets the limit.
     */
    private static double orbitSpeed(DroneSnapshot self, DroneTask.Loiter task) {
        double comfortable = Math.sqrt(Math.max(1.0, task.radius()) * self.airframe().brakingAccel());
        return Math.min(self.cruiseSpeed(), comfortable);
    }

    @Override
    public String id() {
        return "surveil";
    }
}
