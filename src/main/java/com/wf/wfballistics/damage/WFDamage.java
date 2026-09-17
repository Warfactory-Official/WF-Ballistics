package com.wf.wfballistics.damage;

import com.wf.wfballistics.mixin.AccessorLivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Damage that ignores the victim's invulnerability window ("i-frames"), ported from NTM's {@code
 * EntityDamageUtil.attackEntityFromNT(..., ignoreIFrame = true)}.
 */
public final class WFDamage {

    private WFDamage() {
    }

    /**
     * Hurts {@code victim} for {@code amount}, ignoring any invulnerability window it is currently in and without
     * opening one.
     *
     * @return whether the hit registered
     */
    public static boolean hurtIgnoringIFrames(LivingEntity victim, DamageSource source, float amount) {
        if (victim.level().isClientSide) {
            return false;
        }

        AccessorLivingEntity access = (AccessorLivingEntity) victim;
        int invulnerableTime = victim.invulnerableTime;
        float lastHurt = access.wfLastHurt();

        victim.invulnerableTime = 0;
        access.wfSetLastHurt(0F);
        try {
            return victim.hurt(source, amount);
        } finally {
            victim.invulnerableTime = invulnerableTime;
            access.wfSetLastHurt(lastHurt);
        }
    }
}
