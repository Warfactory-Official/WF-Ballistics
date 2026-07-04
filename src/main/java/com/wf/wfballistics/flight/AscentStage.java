package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Vertical boost that gradually pitches over toward the target as it nears cruise altitude (a gravity turn).
 */
public final class AscentStage implements FlightStage {

    public static final AscentStage INSTANCE = new AscentStage();

    /**
     * Boost speed during launch (blocks/tick) — the missile accelerates up to this off the pad.
     */
    private static final double ASCENT_SPEED = 1.5;
    /**
     * Distance below cruise altitude at which the missile begins pitching over toward the target.
     */
    private static final double PITCHOVER_BAND = 48.0;

    private AscentStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double remaining = ctx.safeAltitude() - missile.getY();
        double lean = 1.0 - Mth.clamp(remaining / PITCHOVER_BAND, 0.0, 1.0); // 0 low, -> 1 at cruise altitude
        double up = ASCENT_SPEED * (1.0 - 0.6 * lean);
        double horiz = ASCENT_SPEED * 0.8 * lean;
        return new Vec3(ctx.nx() * horiz, up, ctx.nz() * horiz);
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
