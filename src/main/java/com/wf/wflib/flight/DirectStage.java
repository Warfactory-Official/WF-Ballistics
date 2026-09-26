package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;

/** Pure pursuit on the aim point, no terrain profile: a line-of-sight round (TV, ATGM). */
public final class DirectStage implements FlightStage {

    public static final DirectStage INSTANCE = new DirectStage();

    private DirectStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        Vec3 los = ctx.target().subtract(ctx.position());
        double length = los.length();
        if (length < 1.0E-3) {
            return missile.getDeltaMovement();
        }
        return los.scale(missile.flight().getCruiseSpeed() / length);
    }

    @Override
    public String id() {
        return "direct";
    }
}
