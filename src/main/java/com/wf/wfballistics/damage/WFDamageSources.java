package com.wf.wfballistics.damage;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Builds {@link DamageSource}s for the mod's {@link DamageClass}es. In 1.20 a damage source is just a
 * {@link Holder Holder&lt;DamageType&gt;} plus optional entities, and the holder has to be resolved from the
 * level's registry access (damage types are datapack objects), which this hides behind one call.
 */
public final class WFDamageSources {

    private WFDamageSources() {
    }

    /**
     * @param attacker the entity ultimately responsible (the shooter / the placer of the charge), or null
     * @param direct   the immediate cause (the projectile), or null to reuse {@code attacker}
     */
    public static DamageSource create(Level level, DamageClass clazz, @Nullable Entity attacker, @Nullable Entity direct) {
        Holder<DamageType> holder = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(clazz.type);
        return new DamageSource(holder, direct, attacker);
    }

    public static DamageSource create(Level level, DamageClass clazz, @Nullable Entity attacker) {
        return create(level, clazz, attacker, attacker);
    }
}
