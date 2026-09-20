package com.wf.wflib.drone.ai.state;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.DroneStateHandler;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.Steering;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** The run home. */
public final class ExfilHandler implements DroneStateHandler {

    public static final ExfilHandler INSTANCE = new ExfilHandler();

    private ExfilHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.EXFIL;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        if (self.onLeg()) {
            return null;
        }
        return self.horizontalDistanceTo(self.exfil()) <= Tuning.ARRIVAL_RADIUS ? DroneState.LANDING : null;
    }

    @Override
    public void act(DroneSnapshot self, SquadView squad, List<DroneAction> out) {
        if (self.stateTicks() == 0) {
            out.add(new DroneAction.Log(WFEventType.EXFIL, "returning"));
        }
        if (self.onLeg() && self.horizontalDistanceTo(Objects.requireNonNull(self.leg())) <= Tuning.LEG_ARRIVAL_RADIUS) {
            out.add(new DroneAction.AdvanceLeg());
        }
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        Vec3 aim = Routing.aim(self, self.onLeg() ? self.leg() : self.exfil());
        return Steering.cruise(self.pos(), aim, aim.y, self.cruiseSpeed(), self.climbRate());
    }

    @Override
    public String id() {
        return "exfil";
    }
}
