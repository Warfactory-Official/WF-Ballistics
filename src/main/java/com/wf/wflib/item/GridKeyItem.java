package com.wf.wflib.item;

import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.SourceIds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Carries a network identity, and puts it on things. */
public class GridKeyItem extends Item {

    public GridKeyItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ReconBound bound)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player != null && player.isShiftKeyDown()) {
            copy(player, stack, bound);
        } else {
            apply(player, stack, bound, be);
        }
        return InteractionResult.SUCCESS;
    }

    /** Take a block's network onto the key. */
    private static void copy(Player player, ItemStack stack, ReconBound bound) {
        UUID id = bound.identity();
        if (id == null) {
            say(player, Component.literal("nothing to copy: that block has no network of its own")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        stack.set(ModDataComponents.GRID_ID.get(), id);
        say(player, Component.literal("holding grid " + Long.toHexString(SourceIds.of(id)))
                .withStyle(ChatFormatting.AQUA));
    }

    /** Put the key's network onto a block, or hand the block back its own judgement if the key is empty. */
    private static void apply(@Nullable Player player, ItemStack stack, ReconBound bound, BlockEntity be) {
        UUID id = stack.get(ModDataComponents.GRID_ID.get());
        bound.bindNet(id);
        Component name = be.getBlockState().getBlock().getName();
        say(player, Component.empty().append(name).append(id == null
                        ? Component.literal(" back on automatic (net " + Long.toHexString(bound.netId()) + ")")
                        : Component.literal(" on grid " + Long.toHexString(bound.netId())))
                .withStyle(id == null ? ChatFormatting.GRAY : ChatFormatting.GREEN));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            if (stack.has(ModDataComponents.GRID_ID.get())) {
                stack.remove(ModDataComponents.GRID_ID.get());
                say(player, Component.literal("key cleared: use it on a block to release that block")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                UUID minted = UUID.randomUUID();
                stack.set(ModDataComponents.GRID_ID.get(), minted);
                say(player, Component.literal("new grid " + Long.toHexString(SourceIds.of(minted)))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void say(@Nullable Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    /**
     * The full UUID, not the folded net id, because this is the one place it can be read and copied: the folded
     * form is one-way, so a net id off a readout cannot be typed back into {@code recon bind}.
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        UUID id = stack.get(ModDataComponents.GRID_ID.get());
        if (id == null) {
            lines.add(Component.literal("empty: sneak-use to mint one")
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("use on a recon block to put it back on automatic")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        lines.add(Component.literal(String.format(Locale.ROOT, "grid %s", Long.toHexString(SourceIds.of(id))))
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
