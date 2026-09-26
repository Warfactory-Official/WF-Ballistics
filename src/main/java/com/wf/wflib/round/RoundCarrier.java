package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.warhead.WarheadCarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A round as the warhead sees it at the moment it goes off. */
record RoundCarrier(Level level, KineticPreset preset, Vec3 angle, @Nullable Entity exploder,
                    @Nullable UUID igniterFactionId) implements WarheadCarrier {

    @Override
    public int getFragmentCount() {
        return this.preset.fragmentCount();
    }

    @Override
    public float blastHalfAngleDeg() {
        return this.preset.blastHalfAngleDeg();
    }

    @Override
    public float blastSize() {
        return this.preset.blastSize();
    }

    @Override
    public boolean breaksBlocks() {
        return this.preset.breaksBlocks();
    }
}
