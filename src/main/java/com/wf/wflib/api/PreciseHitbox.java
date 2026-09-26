package com.wf.wflib.api;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** An entity whose shape is not its bounding box. Other mods' hit tests ask it instead of the box. */
public interface PreciseHitbox {

    /** First point of {@code from -> to} on the shape; null = missed. */
    @Nullable
    Vec3 clip(Vec3 from, Vec3 to);
}
