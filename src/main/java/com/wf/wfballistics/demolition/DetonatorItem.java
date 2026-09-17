package com.wf.wfballistics.demolition;

import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.item.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Wireless detonator. */
public class DetonatorItem extends Item {

    public static final int MAX_CHARGES = 5;

    public DetonatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player != null && player.isSecondaryUseActive()) {
            BlockState state = level.getBlockState(context.getClickedPos());
            if (!IDetonatable.isExplosive(state)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide) {
                link(level, context.getItemInHand(), context.getClickedPos(), player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            detonateAll(context.getItemInHand(), (ServerLevel) level, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            return InteractionResultHolder.pass(stack); // sneaking links via useOn; in air it does nothing
        }
        if (!level.isClientSide) {
            detonateAll(stack, (ServerLevel) level, player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** The charges this detonator is wired to. Never null; an unwired detonator has no component at all. */
    private static List<BlockPos> charges(ItemStack stack) {
        List<BlockPos> list = stack.get(ModDataComponents.DETONATOR_CHARGES.get());
        return list == null ? List.of() : list;
    }

    /** The entities (mines) this detonator is wired to, by id. Never null. */
    private static List<UUID> mines(ItemStack stack) {
        List<UUID> list = stack.get(ModDataComponents.DETONATOR_MINES.get());
        return list == null ? List.of() : list;
    }

    /** Everything on the circuit, blocks and entities together: what {@link #MAX_CHARGES} caps. */
    public static int wired(ItemStack stack) {
        return charges(stack).size() + mines(stack).size();
    }

    private static boolean full(ItemStack stack, Player player) {
        if (wired(stack) < MAX_CHARGES) {
            return false;
        }
        player.displayClientMessage(
                Component.translatable("wfballistics.tool.detonator.full", MAX_CHARGES), true);
        return true;
    }

    private static void linked(ItemStack stack, Player player) {
        player.displayClientMessage(
                Component.translatable("wfballistics.tool.detonator.linked", wired(stack), MAX_CHARGES), true);
    }

    private static void link(Level level, ItemStack stack, BlockPos pos, Player player) {
        List<BlockPos> existing = charges(stack);
        if (existing.contains(pos)) {
            player.displayClientMessage(Component.translatable("wfballistics.tool.detonator.already"), true);
            return;
        }
        if (full(stack, player)) {
            return;
        }
        List<BlockPos> list = new ArrayList<>(existing);
        list.add(pos.immutable());
        stack.set(ModDataComponents.DETONATOR_CHARGES.get(), List.copyOf(list));
        arm(level, pos);
        level.playSound(null, pos, WFSounds.DETONATOR_ARM.get(), SoundSource.BLOCKS, 0.8F, 1.0F);
        linked(stack, player);
    }

    /**
     * Right-clicking a detonatable entity with a detonator: sneaking wires it (or unwires one already on the
     * circuit), a plain click fires the whole circuit the way clicking anything else does.
     *
     * @return true if the click was used up
     */
    public static boolean interactWithEntity(ServerLevel level, Player player, ItemStack stack,
                                             Entity entity) {
        if (!(entity instanceof IDetonatableEntity detonatable)) {
            return false;
        }
        if (!player.isSecondaryUseActive()) {
            detonateAll(stack, level, player);
            return true;
        }
        wire(level, player, stack, entity, !isWired(stack, entity));
        return true;
    }

    /** @return the detonator in either of {@code player}'s hands, or empty. */
    public static ItemStack inHand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof DetonatorItem) {
                return held;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Whether {@code entity} is already on this detonator's circuit. */
    public static boolean isWired(ItemStack stack, Entity entity) {
        return mines(stack).contains(entity.getUUID());
    }

    /**
     * Puts {@code entity} on this detonator's circuit or takes it off, and says what happened.
     *
     * @return true if the circuit changed
     */
    public static boolean wire(ServerLevel level, Player player, ItemStack stack, Entity entity,
                               boolean on) {
        if (!(entity instanceof IDetonatableEntity detonatable)) {
            return false;
        }
        UUID id = entity.getUUID();
        List<UUID> existing = mines(stack);
        if (!on) {
            if (!existing.contains(id)) {
                return false;
            }
            List<UUID> list = new ArrayList<>(existing);
            list.remove(id);
            store(stack, list);
            player.displayClientMessage(
                    Component.translatable("wfballistics.tool.detonator.unlinked",
                            detonatable.detonatorLabel()), true);
            return true;
        }
        if (existing.contains(id)) {
            player.displayClientMessage(Component.translatable("wfballistics.tool.detonator.already"), true);
            return false;
        }
        if (!detonatable.canWireDetonator(player)) {
            player.displayClientMessage(Component.translatable("wfballistics.tool.detonator.refused"), true);
            return false;
        }
        if (full(stack, player)) {
            return false;
        }
        List<UUID> list = new ArrayList<>(existing);
        list.add(id);
        store(stack, list);
        level.playSound(null, entity.blockPosition(), WFSounds.DETONATOR_ARM.get(), SoundSource.BLOCKS,
                0.8F, 1.2F);
        linked(stack, player);
        return true;
    }

    /** An empty list is stored as no component at all, so an unwired detonator stacks with a fresh one. */
    private static void store(ItemStack stack, List<UUID> list) {
        if (list.isEmpty()) {
            stack.remove(ModDataComponents.DETONATOR_MINES.get());
        } else {
            stack.set(ModDataComponents.DETONATOR_MINES.get(), List.copyOf(list));
        }
    }

    /** Flip a charge to its armed blockstate (visual feedback that it's wired up). */
    private static void arm(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(MiningChargeBlock.ARMED) && !state.getValue(MiningChargeBlock.ARMED)) {
            level.setBlock(pos, state.setValue(MiningChargeBlock.ARMED, true), Block.UPDATE_CLIENTS);
        }
    }

    private static void detonateAll(ItemStack stack, ServerLevel level, @Nullable Player player) {
        List<BlockPos> blocks = charges(stack);
        List<UUID> entities = mines(stack);
        if (blocks.isEmpty() && entities.isEmpty()) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("wfballistics.tool.detonator.empty"), true);
            }
            return;
        }
        if (player != null) {
            level.playSound(null, player.blockPosition(), WFSounds.DETONATOR_DETONATE.get(),
                    SoundSource.PLAYERS, 0.9F, 1.0F);
        }
        int fired = 0;
        for (BlockPos pos : blocks) {
            if (IDetonatable.tryDetonate(level, pos, player)) {
                fired++;
            }
        }
        for (UUID id : entities) {
            if (IDetonatableEntity.tryDetonate(level, id, player)) {
                fired++;
            }
        }
        stack.remove(ModDataComponents.DETONATOR_CHARGES.get());
        stack.remove(ModDataComponents.DETONATOR_MINES.get());
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable("wfballistics.tool.detonator.detonated", fired), true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("wfballistics.tooltip.detonator.count", wired(stack), MAX_CHARGES)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("wfballistics.tooltip.detonator.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
