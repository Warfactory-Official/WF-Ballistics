package com.wf.wflib.api;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.missile.SeekerMode;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;

/** An emitting seeker (radar) is on {@code target}; posted every few ticks for warning receivers. */
public final class SeekerLockEvent extends Event {

    private final MissileEntity missile;
    private final Entity target;
    private final SeekerMode mode;

    public SeekerLockEvent(MissileEntity missile, Entity target, SeekerMode mode) {
        this.missile = missile;
        this.target = target;
        this.mode = mode;
    }

    public MissileEntity missile() {
        return this.missile;
    }

    public Entity target() {
        return this.target;
    }

    public SeekerMode mode() {
        return this.mode;
    }
}
