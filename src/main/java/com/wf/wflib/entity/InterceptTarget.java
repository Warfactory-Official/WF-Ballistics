package com.wf.wflib.entity;

import com.wf.wflib.recon.ContactClass;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Something air defence is allowed to shoot at. */
public interface InterceptTarget {

    /**
     * @return this target as an entity, for position, velocity and identity.
     */
    default Entity interceptEntity() {
        return (Entity) this;
    }

    /**
     * @return true while this is still worth spending a shot on: alive, and not already neutralised. An
     *      already-downed target is excluded deliberately: re-shooting one cuts its own death short.
     */
    boolean interceptEngageable();

    /**
     * @return how the sensor net classifies this target, so a battery can decide whether it engages the class
     *      at all.
     */
    ContactClass interceptClass();

    /**
     * @return the launcher/operator this target belongs to, or null if it has none. Compared against a
     *      battery's own control id so a battery never engages what it fired or launched itself.
     */
    @Nullable
    UUID interceptControlId();

    /**
     * @return the WarForge faction this target belongs to, or null if unaffiliated (which reads as hostile,
     *      because a thing with no side is exactly what air defence is for).
     */
    @Nullable
    UUID interceptTeamId();

    /** Whittle the shared health pool: CIWS bursts and interceptor chip-mode hits. */
    void interceptDamage(float amount);

    /**
     * A clean kill from a dedicated interceptor, overriding whatever the target would have rolled for itself.
     */
    void interceptKill();

    /**
     * A last-ditch attempt to break the intercept, rolled at closest approach.
     *
     * @return true if the target got away. Defaults to false: only a powered target with fuel to burn can
     *      boost clear, so anything else simply takes the hit.
     */
    default boolean interceptEvade(double interceptorSpeed, Vec3 interceptorPos) {
        return false;
    }
}
