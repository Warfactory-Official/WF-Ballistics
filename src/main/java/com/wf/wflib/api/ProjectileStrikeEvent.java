package com.wf.wflib.api;

import com.norwood.ahf.part.HitboxPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A WFLib projectile has met an entity, before it does anything about it. Server side, game bus.
 *
 * <p>Posted by missiles and rounds. A listener that owns the target's armour decides
 * {@link #outcome}; the projectile then carries it out.
 */
public class ProjectileStrikeEvent extends Event {

    public enum Outcome {
        /** Hurt the target and detonate as usual. */
        PROCEED,
        /** Fuze failed: no damage, no detonation. A missile drops as a dud; a shell is lost. */
        DUD,
        /** Nothing solid in the path after all: keep flying. */
        PASS
    }

    @Nullable
    private final Entity projectile;
    private final Object key;
    private final Entity target;
    private final Vec3 location;
    private final Vec3 velocity;
    private final Threat threat;
    @Nullable
    private final List<HitboxPart> parts;
    private Outcome outcome = Outcome.PROCEED;
    @Nullable
    private Vec3 detonation;

    public ProjectileStrikeEvent(Entity projectile, Entity target, Vec3 location, Vec3 velocity, Threat threat) {
        this(projectile, projectile, target, location, velocity, threat);
    }

    /** Entity-less striker: {@code key} = {@link StrikeContext#key}. */
    public ProjectileStrikeEvent(@Nullable Entity projectile, Object key, Entity target, Vec3 location, Vec3 velocity,
                                 Threat threat) {
        this(projectile, key, target, location, velocity, threat, null);
    }

    /** {@code parts} = {@link StrikeContext#parts} of the damage that follows. */
    public ProjectileStrikeEvent(@Nullable Entity projectile, Object key, Entity target, Vec3 location, Vec3 velocity,
                                 Threat threat, @Nullable List<HitboxPart> parts) {
        this.parts = parts;
        this.projectile = projectile;
        this.key = key;
        this.target = target;
        this.location = location;
        this.velocity = velocity;
        this.threat = threat;
    }

    /** Null for an entity-less round. */
    @Nullable
    public Entity projectile() {
        return projectile;
    }

    /** {@link StrikeContext#key} of the damage that follows. */
    public Object key() {
        return key;
    }

    public Entity target() {
        return target;
    }

    public Vec3 location() {
        return location;
    }

    public Vec3 velocity() {
        return velocity;
    }

    public Threat threat() {
        return threat;
    }

    /** {@link StrikeContext#parts}. */
    @Nullable
    public List<HitboxPart> parts() {
        return parts;
    }

    public Outcome outcome() {
        return outcome;
    }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    /** Where the warhead functioned when a listener moved it off {@link #location} (a cage, a brick); else null. */
    @Nullable
    public Vec3 detonation() {
        return detonation;
    }

    public void setDetonation(@Nullable Vec3 detonation) {
        this.detonation = detonation;
    }
}
