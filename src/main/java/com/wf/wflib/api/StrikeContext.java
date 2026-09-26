package com.wf.wflib.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What struck: the one view armour and hit-location code reads, whether the striker is a projectile entity or an
 * entity-less round ({@link #of} on the damage source). {@link #key} identifies the strike across
 * {@link ProjectileStrikeEvent} and the damage call that follows it.
 */
public interface StrikeContext {

    Threat threat();

    /** Striker position at the start of its travel this tick. */
    Vec3 position();

    /** This tick's travel. */
    Vec3 velocity();

    /** Identity for per-strike ledgers: the projectile entity, or a round's key. */
    Object key();

    @Nullable
    Entity shooter();

    /** Round damage sources are contexts; a {@link ThreatSource} projectile is adapted; else null. */
    @Nullable
    static StrikeContext of(DamageSource source) {
        if (source instanceof StrikeContext context) {
            return context;
        }
        if (source.getDirectEntity() instanceof Projectile projectile && projectile instanceof ThreatSource threat) {
            return new Of(projectile, threat);
        }
        return null;
    }

    record Of(Projectile projectile, ThreatSource source) implements StrikeContext {
        @Override
        public Threat threat() {
            return this.source.threat();
        }

        @Override
        public Vec3 position() {
            return this.projectile.position();
        }

        @Override
        public Vec3 velocity() {
            return this.projectile.getDeltaMovement();
        }

        @Override
        public Object key() {
            return this.projectile;
        }

        @Nullable
        @Override
        public Entity shooter() {
            return this.projectile.getOwner();
        }
    }
}
