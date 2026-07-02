package com.wf.wfballistics.sim;

import net.minecraft.world.phys.Vec3;

/**
 * Something that wants a chance to interact with a passing missile (radar, SAM, launch pad, ...).
 * A simulated missile whose straight-line path enters a valid listener's sphere is respawned as a
 * real entity just before it crosses the boundary. Implementations self-register with
 * {@link MissileListenerRegistry} (block entities on their server tick, deregister on removal).
 */
public interface IMissileListener {
    /** World-space center of the detection sphere. */
    Vec3 listenerCenter();

    /** Detection radius, in blocks. */
    double listenerRange();

    /** Whether this listener is still active (e.g. loaded and not removed). Invalid ones are purged. */
    boolean listenerValid();
}
