package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link MissileEntity.Phase#ASCEND} stage of a torpedo: everything between the launcher and the start of the
 * run.
 */
public final class TorpedoEntryStage implements FlightStage {

    public static final TorpedoEntryStage INSTANCE = new TorpedoEntryStage();

    /** Fraction of cruise speed carried horizontally while still in air, so the entry is steep. */
    private static final double AIR_LEAD = 0.25;
    /** Descent rate (blocks/tick) while still in air. Independent of cruise speed: this is a fall, not a run. */
    private static final double ENTRY_SINK = 0.9;
    /** Fraction of cruise speed carried horizontally while descending to depth, once wet. */
    private static final double WET_LEAD = 0.7;

    private TorpedoEntryStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        double speed = missile.flight().getCruiseSpeed();
        if (!missile.isSubmerged()) {
            return new Vec3(ctx.nx() * speed * AIR_LEAD, -ENTRY_SINK, ctx.nz() * speed * AIR_LEAD);
        }
        // Wet: sink toward the corridor, easing off as it arrives so it settles instead of driving into the bed.
        double want = ctx.clampToCorridor(ctx.safeAltitude());
        double error = want - missile.getY();
        double vy = Math.max(-speed, Math.min(speed, error * 0.5));
        return new Vec3(ctx.nx() * speed * WET_LEAD, vy, ctx.nz() * speed * WET_LEAD);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return missile.isSubmerged() && missile.getY() <= ctx.safeCeiling()
                ? MissileEntity.Phase.CRUISE : null;
    }

    @Override
    public String id() {
        return "torpedo_entry";
    }
}
