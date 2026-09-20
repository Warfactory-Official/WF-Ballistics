package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Near-vertical top-attack dive: the same resolved-angle pure-pursuit approach as {@link AttackStage} but with a
 * faster terminal speed - a drone / loitering-munition strike that keeps its speed through the pivot.
 */
public final class VerticalDiveStage implements FlightStage {

    public static final VerticalDiveStage INSTANCE = new VerticalDiveStage();

    private static final double DIVE_SPEED = 18.0;

    private VerticalDiveStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return AttackStage.guideDive(missile, ctx, DIVE_SPEED);
    }

    @Override
    public String id() {
        return "dive";
    }
}
