package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Vertical boost straight up until the missile clears its safe altitude, then hands off to cruise. */
public final class AscentStage implements FlightStage {

    public static final AscentStage INSTANCE = new AscentStage();

    /** Vertical boost speed during launch (blocks/tick). */
    private static final double ASCENT_SPEED = 1.25;

    private AscentStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return new Vec3(0.0, ASCENT_SPEED, 0.0);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return missile.getY() >= ctx.safeAltitude() ? MissileEntity.Phase.CRUISE : null;
    }

    @Override
    public String id() {
        return "ascent";
    }
}
