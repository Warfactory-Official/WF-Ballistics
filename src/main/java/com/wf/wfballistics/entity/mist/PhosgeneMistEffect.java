package com.wf.wfballistics.entity.mist;

import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;


public class PhosgeneMistEffect implements MistEffect {

    @Override
    public void affect(MistEntity mist, Entity target, double intensity) {
        if (!(target instanceof LivingEntity living) || living.isInvulnerable()) return;

        int duration = (int) (80 * intensity) + 20;
        living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.POISON, duration, (int) Math.min(intensity * 2, 2)));

        // Once a second so the i-frame window doesn't swallow the damage.
        if (living.tickCount % 20 == 0) {
            living.hurt(mist.level().damageSources().magic(), (float) (2.0 * intensity));
        }
    }

    @Override
    public int color(MistEntity mist) {
        return 0xCFE0C0; // pale sickly green-white
    }
}
