package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;

/** The {@link MissileEntity.Phase#ATTACK} stage of a torpedo: a straight sprint onto the aim point. */
public final class TorpedoTerminalStage implements FlightStage {

    public static final TorpedoTerminalStage INSTANCE = new TorpedoTerminalStage();

    /** Multiple of cruise speed the terminal run is flown at. */
    private static final double SPRINT = 1.6;

    private TorpedoTerminalStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        Vec3 toTarget = ctx.target().subtract(ctx.position());
        double range = toTarget.length();
        if (range < 1.0E-4) {
            return missile.getDeltaMovement(); // sitting on the aim point; coast and let the fuze do it
        }
        return toTarget.scale(missile.getCruiseSpeed() * SPRINT / range);
    }

    @Override
    public String id() {
        return "torpedo_terminal";
    }
}
