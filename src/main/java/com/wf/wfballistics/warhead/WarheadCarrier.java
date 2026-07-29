package com.wf.wfballistics.warhead;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public interface WarheadCarrier {

    Level level();

    int getFragmentCount();

    /**
     * Unit direction the warhead is travelling at the moment it detonates — the jet axis for directional
     * warheads such as the shaped charge (see {@link WarheadRegistry#SHAPED_CHARGE}). Implementors return their
     * normalised velocity, falling back to straight down {@code (0,-1,0)} when effectively stationary.
     */
    Vec3 angle();
}
