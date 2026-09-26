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

/**
 * Where the armour system meets the rest of the game. The reduction step itself is in
 * {@code DamageEventHandler}, at {@code HIGH} priority, so that everything reading the damage amount
 * afterwards reads what actually got through.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class ArmorEvents {

    private ArmorEvents() {
    }

    /**
     * Strips vanilla's armour attributes off anything this system is the authority for.
     *
     * <p>Not optional, and not cosmetic. {@code LivingIncomingDamageEvent} fires <em>before</em>
     * {@code getDamageAfterArmorAbsorb}, so a piece left holding both would have its DT/DR applied and
     * then its armour points applied on top of that. One authority per item. The technique is already
     * proven in this stack: wfcore's {@code HazmatArmorStats} does exactly this to GregTech hazmat kit.
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

    /**
     * Stops vanilla charging durability as well. Vanilla spends a quarter of the incoming damage per
     * hit; this system spends what the armour actually absorbed, per layer. Leaving both on would wear
     * a plate out at roughly twice the intended rate, and the halves would disagree about which layer
     * did the work.
     */
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

    /**
     * A piece that has actually left takes its unwritten exposure with it.
     *
     * <p>The item check matters. This event also fires every time a worn piece's condition changes,
     * because a changed component is a changed stack, so it fires on the system's own writes; treating
     * those as a swap would throw away wear that was just accrued against the piece still being worn.
     */
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
