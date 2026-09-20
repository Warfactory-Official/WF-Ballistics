package com.wf.wflib.sim;

import net.minecraft.world.phys.Vec3;

/** Something that wants a chance to interact with a passing missile (radar, SAM, launch pad, ...). */
public interface IMissileListener {
    /**
     * World-space center of the detection sphere.
     */
    Vec3 listenerCenter();

    /**
     * Detection radius, in blocks.
     */
    double listenerRange();

    /**
     * Whether this listener is still active (e.g. loaded and not removed). Invalid ones are purged.
     */
    boolean listenerValid();
}
