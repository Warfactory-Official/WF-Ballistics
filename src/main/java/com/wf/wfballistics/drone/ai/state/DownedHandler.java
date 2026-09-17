package com.wf.wfballistics.drone.ai.state;

import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.RotorDamage;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateHandler;
import com.wf.wfballistics.drone.ai.SquadView;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Shot down. */
public final class DownedHandler implements DroneStateHandler {

    public static final DownedHandler INSTANCE = new DownedHandler();

    private DownedHandler() {
    }

    @Override
    public DroneState state() {
        return DroneState.DOWNED;
    }

    @Override
    @Nullable
    public DroneState next(DroneSnapshot self, SquadView squad) {
        return null;
    }

    @Override
    public Vec3 guide(DroneSnapshot self, SquadView squad) {
        if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
            return Vec3.ZERO;
        }
        Vec3 velocity = self.velocity();
        double fall = Math.max(Tuning.DOWNED_TERMINAL, velocity.y - Tuning.DOWNED_GRAVITY);
        Vec3 slip = sideslip(self);
        return new Vec3(velocity.x * Tuning.DOWNED_DRAG + slip.x,
                fall,
                velocity.z * Tuning.DOWNED_DRAG + slip.z);
    }

    /** The way a crippled airframe slides while it comes down. */
    private static Vec3 sideslip(DroneSnapshot self) {
        RotorDamage rotors = self.rotors();
        if (!rotors.damaged() || rotors.dead()) {
            return Vec3.ZERO;
        }
        double sin = Math.sin(self.yaw());
        double cos = Math.cos(self.yaw());
        double x = rotors.leanX() * cos + rotors.leanZ() * sin;
        double z = rotors.leanZ() * cos - rotors.leanX() * sin;
        return new Vec3(x * Tuning.DOWNED_SIDESLIP, 0.0, z * Tuning.DOWNED_SIDESLIP);
    }

    @Override
    public String id() {
        return "downed";
    }
}
