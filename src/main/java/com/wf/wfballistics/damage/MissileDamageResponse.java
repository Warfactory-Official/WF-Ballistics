package com.wf.wfballistics.damage;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.world.damagesource.DamageSource;

/**
 * Per-missile hook deciding how a {@link MissileEntity} responds to an incoming {@link DamageSource}. Returns the
 * effective damage to apply — return {@code 0} to fully resist a hit (e.g. shrug off everything that isn't an
 * explosion), or scale {@code amount} for partial resistance. Evaluated server-side in {@link MissileEntity#hurt}.
 *
 * <p>Register a named response with {@link MissileDamageRegistry#register} and select it on a preset via
 * {@code MissilePreset.Builder.damageResponse(id)} / {@code MissileEntity.Builder.damageResponse(id)}. Responses
 * are keyed by id (rather than stored as a raw lambda on the entity) so the choice survives the missile's NBT
 * save/load — register the same id on both sides, at mod construction.
 */
@FunctionalInterface
public interface MissileDamageResponse {

    /**
     * @param missile the missile being hit
     * @param source  the incoming damage source
     * @param amount  the damage that would be dealt
     * @return the effective damage to apply ({@code 0} = fully resisted)
     */
    float apply(MissileEntity missile, DamageSource source, float amount);
}
