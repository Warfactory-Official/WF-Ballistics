package com.wf.wflib.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A launcher projecting a guidance beam (SACLOS / beam riding): the round flies onto the line. */
public interface BeamSource {

    /** @return the beam for {@code missile} now; null = no beam (round flies on). */
    @Nullable
    Beam guidanceBeam(Entity missile);

    /** @param direction unit */
    record Beam(Vec3 origin, Vec3 direction) {
    }
}
