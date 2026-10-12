package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Pure pursuit on the aim point, no terrain profile: a line-of-sight round (TV, ATGM). No seeker track and aim point
 * passed => straight on (sky aim point = sight line end: pursuit looped around it).
 */
public final class DirectStage implements FlightStage {

    public static final DirectStage INSTANCE = new DirectStage();

    private DirectStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        Vec3 los = ctx.target().subtract(ctx.position());
        double length = los.length();
        Vec3 v = missile.getDeltaMovement();
        if (length < 1.0E-3) {
            return v;
        }
        if (missile.seeker().getDesignatedTargetId() == null && missile.flight().passedAimPoint(los, v)) {
            double speed = v.length();
            return speed < 1.0E-6 ? v : v.scale(missile.flight().getCruiseSpeed() / speed);
        }
        return los.scale(missile.flight().getCruiseSpeed() / length);
    }

    @Override
    public String id() {
        return "direct";
    }
}
