package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Loitering-munition cruise: fly to the target area, then orbit it at cruise altitude for {@link #LOITER_TICKS}
 * ticks (a drone holding over its objective) before handing off to the terminal attack. Drop this into the
 * cruise slot in place of {@link CruiseStage} to turn a missile into a loitering drone. The orbit timer is
 * per-missile state kept on the entity so it persists.
 */
public final class LoiterStage implements FlightStage {

    public static final LoiterStage INSTANCE = new LoiterStage();
    /**
     * How long (ticks) to loiter on-station before diving (~10s at 20 TPS).
     */
    public static final int LOITER_TICKS = 200;
    /**
     * Horizontal radius (blocks) the drone orbits the target at.
     */
    private static final double ORBIT_RADIUS = 24.0;
    /**
     * Altitude-hold gain toward the safe altitude.
     */
    private static final double ALTITUDE_GAIN = 0.1;

    private LoiterStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double maxSpeed = missile.getCruiseSpeed();
        double vy = Mth.clamp((ctx.safeAltitude() - missile.getY()) * ALTITUDE_GAIN, -maxSpeed, maxSpeed);

        double dist = ctx.horizontalDist();
        double vx;
        double vz;
        if (dist > ORBIT_RADIUS + 4.0) {
            // Still inbound: close on the target like a normal cruise.
            vx = ctx.nx() * maxSpeed;
            vz = ctx.nz() * maxSpeed;
        } else {
            // On station: circle the target (tangent = radial rotated 90 degrees) with a gentle radius
            // correction, and count up the loiter timer that gates the dive.
            double tx = -ctx.nz();
            double tz = ctx.nx();
            double radialErr = Mth.clamp((dist - ORBIT_RADIUS) / ORBIT_RADIUS, -1.0, 1.0);
            vx = (tx + ctx.nx() * radialErr) * maxSpeed;
            vz = (tz + ctx.nz() * radialErr) * maxSpeed;
            missile.setLoiterTicks(missile.getLoiterTicks() + 1);
        }
        return new Vec3(vx, vy, vz);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return missile.getLoiterTicks() >= LOITER_TICKS ? MissileEntity.Phase.ATTACK : null;
    }

    @Override
    public String id() {
        return "loiter";
    }
}
