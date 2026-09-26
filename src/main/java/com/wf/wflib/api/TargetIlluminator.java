package com.wf.wflib.api;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** A launcher whose fire control holds a target for its missiles: semi-active radar, active radar midcourse. */
public interface TargetIlluminator {

    /** @return what the launcher's fire control holds for {@code missile} now; null = no track. */
    @Nullable
    Entity illuminatedTarget(Entity missile);
}
