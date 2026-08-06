package com.wf.wfballistics.warhead;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public interface WarheadCarrier {

    Level level();

    int getFragmentCount();

    /**
     * The WarForge faction this warhead belongs to (a missile's {@code teamId}), or {@code null} when it has
     * none. Passed to {@link com.wf.wfballistics.aef.ExplosionAEF#igniterFaction} so the blast honours the
     * real chunk rules from the firing faction's perspective. Defaults to {@code null} (unattributed).
     */
    default UUID igniterFactionId() {
        return null;
    }

    /**
     * Unit direction the warhead is travelling at the moment it detonates — the jet axis for directional
     * warheads such as the shaped charge (see {@link WarheadRegistry#SHAPED_CHARGE}). Implementors return their
     * normalised velocity, falling back to straight down {@code (0,-1,0)} when effectively stationary.
     */
    Vec3 angle();
}
