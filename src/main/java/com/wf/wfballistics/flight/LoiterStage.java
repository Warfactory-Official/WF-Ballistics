package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Loitering-munition cruise: fly to the target area, then orbit it at cruise altitude for a per-variant number
 * of ticks before handing off to the terminal attack. Register tuned variants with {@link #of} rather than
 * subclassing; each is a distinct registry id (see {@link FlightStageRegistry}), so persistence stays id-only.
 * The orbit timer is per-missile state kept on the entity so it persists.
 */
public final class LoiterStage implements FlightStage {

    private static final double ALTITUDE_GAIN = 0.1;

    private final String id;
    private final double orbitRadius;
    private final int loiterTicks;

    private LoiterStage(String id, double orbitRadius, int loiterTicks) {
        this.id = id;
        this.orbitRadius = orbitRadius;
        this.loiterTicks = loiterTicks;
    }

    public static LoiterStage of(String id, double orbitRadius, int loiterTicks) {
        return new LoiterStage(id, orbitRadius, loiterTicks);
    }

    public int loiterTicks() {
        return loiterTicks;
    }

    public static int loiterTicksOf(ResourceLocation cruiseStageId) {
        return FlightStageRegistry.get(MissileEntity.Phase.CRUISE, cruiseStageId) instanceof LoiterStage ls
                ? ls.loiterTicks() : 0;
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double maxSpeed = missile.getCruiseSpeed();
        double vy = Mth.clamp((ctx.safeAltitude() - missile.getY()) * ALTITUDE_GAIN, -maxSpeed, maxSpeed);

        double dist = ctx.horizontalDist();
        double vx;
        double vz;
        if (dist > orbitRadius + 4.0) {
            vx = ctx.nx() * maxSpeed;
            vz = ctx.nz() * maxSpeed;
        } else {
            double tx = -ctx.nz();
            double tz = ctx.nx();
            double radialErr = Mth.clamp((dist - orbitRadius) / orbitRadius, -1.0, 1.0);
            vx = (tx + ctx.nx() * radialErr) * maxSpeed;
            vz = (tz + ctx.nz() * radialErr) * maxSpeed;
            missile.setLoiterTicks(missile.getLoiterTicks() + 1);
        }
        return new Vec3(vx, vy, vz);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        if (missile.hasDesignatedTarget()) {
            boolean onStation = ctx.horizontalDist() <= orbitRadius + 6.0;
            return (missile.hasLiveDesignatedTarget() && onStation) ? MissileEntity.Phase.ATTACK : null;
        }
        return missile.getLoiterTicks() >= loiterTicks ? MissileEntity.Phase.ATTACK : null;
    }

    @Override
    public String id() {
        return id;
    }
}
