package com.wf.wfballistics.damage;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Applies the {@link DamageResistanceHandler} DT/DR model to incoming damage. */
@EventBusSubscriber(modid = WFBallistics.MODID)
public final class DamageEventHandler {

    private DamageEventHandler() {
    }

    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
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
                DamageResistanceHandler.currentPierceDR(),
                // Passed so DynamicResistance implementors see the actual hit rather than only its category.
                source);

        if (reduced <= 0F) {
            event.setCanceled(true);
        } else if (reduced != amount) {
            event.setAmount(reduced);
        }
    }
}
