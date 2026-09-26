package com.wf.wflib.flight;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@link MissileEntity.Phase#ASCEND} for a round fired off a rail or pylon: it falls clear of the launcher on the
 * velocity it was given, motor cold, then goes straight to {@link MissileEntity.Phase#ATTACK}. The launcher sets
 * that velocity.
 */
public final class AirLaunchStage implements FlightStage {

    public static final AirLaunchStage INSTANCE = new AirLaunchStage();

    /** Ticks off the rail before the motor lights. */
    private static final int DROP_TICKS = 8;
    /** Blocks/tick². */
    private static final double DROP_GRAVITY = 0.04;
    /** Launched slower than this (a parked launcher) => no drop: it would fall onto the ground under the rail. */
    private static final double MIN_DROP_SPEED = 0.3;

    private AirLaunchStage() {
    }

    @Override
    public Vec3 guide(MissileEntity missile, FlightContext ctx) {
        return missile.getDeltaMovement().add(0.0, -DROP_GRAVITY, 0.0);
    }

    @Override
    @Nullable
    public MissileEntity.Phase next(MissileEntity missile, FlightContext ctx) {
        return missile.tickCount > DROP_TICKS || missile.getDeltaMovement().length() < MIN_DROP_SPEED
                ? MissileEntity.Phase.ATTACK : null;
    }

    @Override
    public String id() {
        return "air_launch";
    }
}
