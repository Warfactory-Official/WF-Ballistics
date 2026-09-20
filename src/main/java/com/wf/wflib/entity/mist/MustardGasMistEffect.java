package com.wf.wflib.entity.mist;

import com.wf.wflib.damage.WFDamage;
import com.wf.wflib.damage.WFDamageTypes;
import com.wf.wflib.entity.MistEntity;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Mustard gas: a blistering agent that damages continuously for as long as a victim stands in it. */
public class MustardGasMistEffect implements MistEffect {

    /** Damage per application at full concentration, and how often it is applied. */
    private static final float DAMAGE = 4.0F;
    private static final int DAMAGE_INTERVAL = 10;

    /** How often the agent eats a point of durability out of each armour piece. */
    private static final int CORROSION_INTERVAL = 20;

    private static final EquipmentSlot[] ARMOUR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * How often stale entries are swept out of {@link #lastAffected}, and how old an entry has to be to go.
     */
    private static final int PRUNE_INTERVAL = 200;

    /**
     * Victim entity id to the game tick it was last affected on, so overlapping cells of the same cloud count as
     * one.
     */
    private final Int2LongOpenHashMap lastAffected = new Int2LongOpenHashMap();
    private long lastPrune = Long.MIN_VALUE;

    public MustardGasMistEffect() {
        lastAffected.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public void affect(MistEntity mist, Entity target, double intensity) {
        if (!(target instanceof LivingEntity living) || living.isInvulnerable()) return;

        long now = mist.level().getGameTime();
        if (!claim(living, now)) return;

        int duration = (int) (100 * intensity) + 40;
        living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, duration, 0));
        living.addEffect(new MobEffectInstance(MobEffects.WITHER, duration, (int) Math.min(intensity * 2, 1)));
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 1));

        if (now % DAMAGE_INTERVAL == 0) {
            WFDamage.hurtIgnoringIFrames(living, gasSource(mist), (float) (DAMAGE * intensity));
        }

        if (now % CORROSION_INTERVAL == 0) {
            for (EquipmentSlot slot : ARMOUR_SLOTS) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty() && stack.isDamageableItem()) {
                    stack.hurtAndBreak(1, living, slot);
                }
            }
        }
    }

    /**
     * @return true if this call is the first to reach {@code victim} on tick {@code now}, i.e. the one that
     *      should do the work
     */
    private boolean claim(LivingEntity victim, long now) {
        if (now - lastPrune >= PRUNE_INTERVAL) {
            lastPrune = now;
            lastAffected.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > PRUNE_INTERVAL);
        }
        return lastAffected.put(victim.getId(), now) != now;
    }

    private static DamageSource gasSource(MistEntity mist) {
        return new DamageSource(mist.level().registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(WFDamageTypes.GAS));
    }

    @Override
    public int color(MistEntity mist) {
        return 0xB8A038; // mustard yellow-brown
    }
}
