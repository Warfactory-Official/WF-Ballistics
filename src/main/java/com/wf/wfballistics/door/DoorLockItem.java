package com.wf.wfballistics.door;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** A padlock. */
public class DoorLockItem extends Item {

    private final double pickChance;

    public DoorLockItem(Properties props, double pickChance) {
        super(props);
        this.pickChance = pickChance;
    }

    /** Cuts a blank lock: gives it a random pin pattern and hands over the one key that matches. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (DoorKeyItem.pinsOf(stack) != 0) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        int pins = 1 + level.random.nextInt(Integer.MAX_VALUE - 1);
        DoorKeyItem.setPins(stack, pins);
        ItemStack key = new ItemStack(ModDoors.DOOR_KEY.get());
        DoorKeyItem.setPins(key, pins);
        if (!player.addItem(key)) {
            player.drop(key, false);
        }
        level.playSound(null, player.blockPosition(), DoorSounds.MOTOR_START.get(), SoundSource.PLAYERS,
                0.5f, 1.8f);
        return InteractionResultHolder.consume(stack);
    }

    ItemInteractionResult applyTo(DoorBlockEntity door, ItemStack stack, Player player, Level level) {
        int pins = DoorKeyItem.pinsOf(stack);
        if (pins == 0 || door.locked()) {
            return ItemInteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!door.applyLock(pins, pickChance)) {
            return ItemInteractionResult.FAIL;
        }
        level.playSound(null, door.getBlockPos(), DoorSounds.MOTOR_STOP.get(), SoundSource.BLOCKS, 0.6f, 1.6f);
        stack.consume(1, player);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        int pins = DoorKeyItem.pinsOf(stack);
        lines.add(pins == 0
                ? Component.translatable("tooltip.wfballistics.lock.blank").withStyle(ChatFormatting.RED)
                : Component.translatable("tooltip.wfballistics.key.pins", pins)
                        .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.wfballistics.lock.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
