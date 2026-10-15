package com.wf.wflib.item;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.env.Interference;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Carried RF jammer. On (use) and anywhere in the inventory: {@link Interference} RADAR area around the carrier
 * (radar returns x {@link #FACTOR}, datalinks with either end inside cut). Durability = seconds of charge; anvil +
 * redstone block recharges.
 */
public class PortableJammerItem extends Item {

    public static final int CHARGE_SECONDS = 180;
    public static final double RADIUS = 32.0;
    /** Radar range ~ rcs^1/4 => x0.1 reach (burn-through inside ~1/10 of normal range). */
    public static final float FACTOR = 1.0e-4f;

    public PortableJammerItem(Properties properties) {
        super(properties);
    }

    public static boolean on(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.ACTIVE.get(), false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            boolean next = !on(stack) && stack.getDamageValue() < stack.getMaxDamage() - 1;
            stack.set(ModDataComponents.ACTIVE.get(), next);
            level.playSound(null, player.blockPosition(), next ? SoundEvents.BEACON_ACTIVATE
                    : SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5f, 1.6f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity carrier, int slot, boolean selected) {
        if (!(level instanceof ServerLevel server) || !on(stack)) {
            return;
        }
        long now = server.getGameTime();
        Interference.put(server, carrier.getUUID(), new Interference.Area(Band.RADAR, Interference.Scope.TARGET,
                carrier.position(), RADIUS, FACTOR, now + 2, 0.0f));
        if (now % 20 == 0) {
            stack.setDamageValue(stack.getDamageValue() + 1);
            if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                stack.set(ModDataComponents.ACTIVE.get(), false);
            }
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return on(stack);
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
        return repair.is(Items.REDSTONE_BLOCK);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(on(stack) ? "item.wflib.portable_jammer.on"
                : "item.wflib.portable_jammer.off"));
    }
}
