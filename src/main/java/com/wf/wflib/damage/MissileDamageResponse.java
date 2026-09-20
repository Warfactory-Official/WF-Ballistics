package com.wf.wflib.damage;

import com.wf.wflib.MissileEntity;
import net.minecraft.world.damagesource.DamageSource;

/** Per-missile hook deciding how a {@link MissileEntity} responds to an incoming {@link DamageSource}. */
@FunctionalInterface
public interface MissileDamageResponse {

    /**
     * @param missile the missile being hit
     * @param source the incoming damage source
     * @param amount the damage that would be dealt
     * @return the effective damage to apply ({@code 0} = fully resisted)
     */
    float apply(MissileEntity missile, DamageSource source, float amount);
}
