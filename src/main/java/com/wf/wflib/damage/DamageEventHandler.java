package com.wf.wflib.damage;

import com.wf.wflib.WFLib;
import com.wf.wflib.armor.ArmorSystem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The one reduction step, and the only place in this mod that changes how much a hit does.
 *
 * <p><b>Priority is load-bearing.</b> This used to sit at {@code NORMAL}, which is also where
 * WFMedical's own handler sits, so which of them saw the raw damage was decided by registration
 * order, which is mod load order. That is not cosmetic: WFMedical reads the amount as wound energy
 * and gates internal bleeding, fracture chance and severity off it, so the same shot could produce
 * two different injuries depending on which mod happened to load first. Armour resolves first, by
 * name, at {@code HIGH}.
 *
 * <p>The arithmetic itself moved to {@code com.wf.wflib.armor}: DT and DR per protection type,
 * per equipment slot, with the durability the hit cost charged to the layer that actually stopped it.
 * {@link DamageResistanceHandler} keeps the piercing context and entities' innate resistance, both of
 * which the resolver reads.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class DamageEventHandler {

    private DamageEventHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        ArmorSystem.onIncomingDamage(event);
    }
}
