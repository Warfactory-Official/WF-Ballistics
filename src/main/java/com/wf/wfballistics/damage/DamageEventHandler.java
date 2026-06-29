package com.wf.wfballistics.damage;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Applies the {@link DamageResistanceHandler} DT/DR model to incoming damage. Registered on the Forge event
 * bus so it covers <em>all</em> living damage: this mod's weapons, vanilla mobs, other mods. With no armour
 * profiles registered it changes nothing.
 *
 * <p>Sources that bypass armour (void, {@code /kill}, starvation) are left alone — DT/DR is an armour-style
 * mitigation and should not make those survivable.
 *
 * <p>Note: this reduction layers <em>on top of</em> vanilla armour/enchantment mitigation, which still runs
 * later in {@code actuallyHurt}. If you want DT/DR to be the sole authority for a damage type, give that
 * type the {@code bypasses_armor} tag.
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
public final class DamageEventHandler {

    private DamageEventHandler() { }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_ARMOR) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        float amount = event.getAmount();
        float reduced = DamageResistanceHandler.calculateDamage(
                event.getEntity(),
                DamageResistanceHandler.categoryFor(source),
                amount,
                DamageResistanceHandler.currentPierceDT(),
                DamageResistanceHandler.currentPierceDR());

        if (reduced <= 0F) {
            event.setCanceled(true);
        } else if (reduced != amount) {
            event.setAmount(reduced);
        }
    }
}
