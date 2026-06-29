package com.wf.wfballistics.entity.mist;

import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;


public class MustardGasMistEffect implements MistEffect {

    @Override
    public void affect(MistEntity mist, Entity target, double intensity) {
        if (!(target instanceof LivingEntity living) || living.isInvulnerable()) return;

        int duration = (int) (100 * intensity) + 40;
        living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.WITHER, duration, (int) Math.min(intensity * 2, 1)));
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 1));

        if (living.tickCount % 20 == 0) {
            living.hurt(mist.level().damageSources().magic(), (float) (1.5 * intensity));
            // Blistering corrodes whatever the victim is wearing.
            for (ItemStack stack : living.getArmorSlots()) {
                if (!stack.isEmpty() && stack.isDamageableItem()) {
                    stack.hurtAndBreak(1, living, e -> { });
                }
            }
        }
    }

    @Override
    public int color(MistEntity mist) {
        return 0xB8A038; // mustard yellow-brown
    }
}
