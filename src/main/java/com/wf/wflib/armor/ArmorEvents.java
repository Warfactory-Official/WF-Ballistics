package com.wf.wflib.armor;

import com.wf.wflib.WFLib;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.entity.living.ArmorHurtEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Armour/game event glue. Reduction itself runs in {@code DamageEventHandler} at {@code HIGH} priority, so later listeners read the post-armour amount. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class ArmorEvents {

    private ArmorEvents() {
    }

    /**
     * Strips vanilla armour attributes off items this system manages: {@code LivingIncomingDamageEvent}
     * fires before vanilla's {@code getDamageAfterArmorAbsorb}, so leaving both would double-apply.
     * Same technique as wfcore's {@code HazmatArmorStats} on GregTech hazmat kit.
     */
    @SubscribeEvent
    public static void onAttributes(ItemAttributeModifierEvent event) {
        if (!ArmorConfig.enabled) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!ArmorProfiles.managed(stack)) {
            return;
        }
        event.removeAllModifiersFor(Attributes.ARMOR);
        event.removeAllModifiersFor(Attributes.ARMOR_TOUGHNESS);
    }

    /** Zeroes vanilla's own durability charge (flat 1/4 of hit, regardless of absorption); both active would roughly double wear and disagree on which layer took it. */
    @SubscribeEvent
    public static void onArmorHurt(ArmorHurtEvent event) {
        if (!ArmorConfig.enabled) {
            return;
        }
        for (EquipmentSlot slot : ArmorSystem.SLOTS) {
            ItemStack stack = event.getArmorItemStack(slot);
            if (!stack.isEmpty() && ArmorProfiles.managed(stack)) {
                event.setNewDamage(slot, 0.0F);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ArmorExposure.tick(event.getServer());
    }

    /** Item-identity check matters: this event also fires on this system's own component writes (changed condition = changed stack), which must not be mistaken for the piece leaving. */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return;
        }
        if (event.getFrom().getItem() != event.getTo().getItem()) {
            ArmorExposure.discard(event.getEntity(), event.getSlot());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        ArmorExposure.forget(event.getEntity());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ArmorExposure.forget(event.getEntity());
    }
}
