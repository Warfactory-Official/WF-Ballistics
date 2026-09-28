package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.round.pen.BlockPen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

/**
 * A round met a block with {@link KineticPreset#blockPen}: exited ({@code pass().exited()}) or stopped inside, before
 * the round moves on or its impact. Game bus, server; the block is untouched. Unloaded chunks included.
 */
public final class RoundPierceEvent extends Event {

    private final ServerLevel level;
    private final KineticPreset preset;
    private final long key;
    private final BlockHitResult entry;
    private final BlockPen.Pass pass;
    private final Vec3 velocity;

    public RoundPierceEvent(ServerLevel level, KineticPreset preset, long key, BlockHitResult entry, BlockPen.Pass pass,
                            Vec3 velocity) {
        this.level = level;
        this.preset = preset;
        this.key = key;
        this.entry = entry;
        this.pass = pass;
        this.velocity = velocity;
    }

    public ServerLevel level() {
        return this.level;
    }

    public KineticPreset preset() {
        return this.preset;
    }

    public long key() {
        return this.key;
    }

    public BlockHitResult entry() {
        return this.entry;
    }

    public BlockPen.Pass pass() {
        return this.pass;
    }

    /** Before the block. */
    public Vec3 velocity() {
        return this.velocity;
    }
}
