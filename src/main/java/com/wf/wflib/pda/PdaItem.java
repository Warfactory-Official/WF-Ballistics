package com.wf.wflib.pda;

import com.wf.wflib.block.CameraMonitorBlock;
import com.wf.wflib.block.entity.CameraMonitorBlockEntity;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.item.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Handheld PDA. Use = raise / lower, handled client-side ({@code PdaClient}), never reaches the server;
 * sneak + use = the target's link action (camera monitor => copy its channels).
 */
public final class PdaItem extends Item {
    public PdaItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockState state = level.getBlockState(ctx.getClickedPos());
        if (!(state.getBlock() instanceof CameraMonitorBlock)) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel sl && sl.getBlockEntity(CameraMonitorBlock.originOf(ctx.getClickedPos(),
                state)) instanceof CameraMonitorBlockEntity monitor) {
            CameraChannels channels = monitor.channels();
            if (channels.isEmpty()) {
                say(ctx.getPlayer(), Component.translatable("wflib.pda.monitor_empty").withStyle(ChatFormatting.RED));
            } else {
                ctx.getItemInHand().set(ModDataComponents.CHANNELS.get(), channels);
                say(ctx.getPlayer(), Component.translatable("wflib.pda.monitor_copied", channels.size())
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void say(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CameraChannels channels = stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
        lines.add(Component.translatable("wflib.pda.cameras", channels.size()).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.wf.wflib.pda.client.PdaRenderer.itemRenderer();
            }
        });
    }
}
