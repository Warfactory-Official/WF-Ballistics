package com.wf.wflib.door;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** A key cut to one lock's pin pattern. */
public class DoorKeyItem extends Item {

    public DoorKeyItem(Properties props) {
        super(props);
    }

    /** The pattern a key is cut to; 0 for a blank. */
    public static int pinsOf(ItemStack stack) {
        Integer pins = stack.get(DoorComponents.PINS.get());
        return pins == null ? 0 : pins;
    }

    public static void setPins(ItemStack stack, int pins) {
        stack.set(DoorComponents.PINS.get(), pins);
    }

    /**
     * Both key actions live here rather than on the block, because a sneaking player holding an item never reaches
     * a block's own use handler (vanilla skips it so sneaking can place blocks against a chest), and cycling a skin
     * is exactly that gesture.
     */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock door)) {
            return InteractionResult.PASS;
        }
        BlockPos core = DoorFrame.findCore(level, pos, door);
        if (core == null || !(level.getBlockEntity(core) instanceof DoorBlockEntity entity)) {
            return InteractionResult.PASS;
        }
        Player player = ctx.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        boolean worked = player.isSecondaryUseActive()
                ? entity.cycleSkin(ctx.getItemInHand())
                : entity.toggleWithKey(player, ctx.getItemInHand());
        return worked ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }

    /** Whether {@code stack} works a lock cut to {@code pins}. */
    public static boolean opens(ItemStack stack, int pins) {
        return stack.getItem() instanceof DoorKeyItem && pinsOf(stack) == pins && pins != 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        int pins = pinsOf(stack);
        lines.add(pins == 0
                ? Component.translatable("tooltip.wflib.key.blank").withStyle(ChatFormatting.RED)
                : Component.translatable("tooltip.wflib.key.pins", pins)
                        .withStyle(ChatFormatting.GRAY));
    }
}
