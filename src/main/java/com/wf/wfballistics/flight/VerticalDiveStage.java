package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Near-vertical top-attack dive: the same resolved-angle pure-pursuit approach as {@link AttackStage} but with
 * a faster terminal speed - a drone / loitering-munition strike that keeps its speed through the pivot. Pair
 * with a steep dive-angle range (or an explicit angle) for a straight-down plunge.
 */
public final class VerticalDiveStage implements FlightStage {

    public static final VerticalDiveStage INSTANCE = new VerticalDiveStage();

    private static final double DIVE_SPEED = 18.0;

    private VerticalDiveStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return AttackStage.guideAngled(missile, ctx, missile.resolveDiveAngle(ctx), DIVE_SPEED);
    }

    @Override
    public String id() {
        return "dive";
    }
}
