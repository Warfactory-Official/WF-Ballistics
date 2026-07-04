package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Vertical boost that rotates toward the target as it nears cruise altitude: a constant-speed gravity turn.
 * The climb runs at the missile's own {@link MissileEntity#getAscentSpeed() ascent speed} (which scales with
 * cruise speed unless overridden), so a fast missile climbs fast, and the pitch-over is spread across the top
 * fraction of the <em>actual</em> climb height, so a low terrain-follow hop and a tall high-altitude climb both
 * pitch over proportionally rather than on a fixed absolute band.
 */
public final class AscentStage implements FlightStage {

    public static final AscentStage INSTANCE = new AscentStage();

    private static final double PITCHOVER_FRACTION = 0.5;
    private static final double MIN_PITCHOVER_BAND = 16.0;
    private static final double MAX_PITCHOVER_ANGLE = Math.toRadians(72.0);

    private AscentStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double speed = missile.getAscentSpeed();
        double climbTo = ctx.safeAltitude();
        double climbHeight = Math.max(1.0, climbTo - missile.getLaunchY());
        double band = Math.min(climbHeight, Math.max(MIN_PITCHOVER_BAND, climbHeight * PITCHOVER_FRACTION));
        double remaining = climbTo - missile.getY();
        double lean = 1.0 - Mth.clamp(remaining / band, 0.0, 1.0);
        double theta = lean * MAX_PITCHOVER_ANGLE;
        double horiz = speed * Math.sin(theta);
        return new Vec3(ctx.nx() * horiz, speed * Math.cos(theta), ctx.nz() * horiz);
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
