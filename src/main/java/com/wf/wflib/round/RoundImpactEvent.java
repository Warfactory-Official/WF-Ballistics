package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

/** A round stopped on a block, before its warhead (impact marks, sparks). Game bus, server. */
public final class RoundImpactEvent extends Event {

    private final ServerLevel level;
    private final KineticPreset preset;
    private final BlockHitResult hit;
    private final Vec3 velocity;

    public RoundImpactEvent(ServerLevel level, KineticPreset preset, BlockHitResult hit, Vec3 velocity) {
        this.level = level;
        this.preset = preset;
        this.hit = hit;
        this.velocity = velocity;
    }

    public ServerLevel level() {
        return this.level;
    }

    public KineticPreset preset() {
        return this.preset;
    }

    public BlockHitResult hit() {
        return this.hit;
    }

    public Vec3 velocity() {
        return this.velocity;
    }
}
