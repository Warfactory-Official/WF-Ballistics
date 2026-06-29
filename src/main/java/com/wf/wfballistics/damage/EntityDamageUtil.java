package com.wf.wfballistics.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Entry point for dealing damage that respects the DT/DR model with weapon piercing.
 */
public final class EntityDamageUtil {

    private EntityDamageUtil() { }

    /**
     * @param ignoreIFrame clear the victim's invulnerability window first, so rapid/multi-hit sources land
     *                     every hit instead of being swallowed by i-frames
     * @param pierceDT     flat amount subtracted from the target's damage threshold for this hit
     * @param pierceDR     fraction (0..1+) of the target's resistance ignored for this hit
     * @return whether the hit registered (see {@link LivingEntity#hurt})
     */
    public static boolean dealDamage(LivingEntity victim, DamageSource source, float amount,
                                     boolean ignoreIFrame, float pierceDT, float pierceDR) {
        if (ignoreIFrame) {
            victim.invulnerableTime = 0;
        }
        DamageResistanceHandler.setup(pierceDT, pierceDR);
        try {
            return victim.hurt(source, amount);
        } finally {
            DamageResistanceHandler.reset();
        }
    }

    /** Convenience overload with no piercing. */
    public static boolean dealDamage(LivingEntity victim, DamageSource source, float amount, boolean ignoreIFrame) {
        return dealDamage(victim, source, amount, ignoreIFrame, 0F, 0F);
    }
}
