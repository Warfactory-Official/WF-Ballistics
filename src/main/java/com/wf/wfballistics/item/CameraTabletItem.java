package com.wf.wfballistics.item;

import com.wf.wfballistics.block.CameraMonitorBlock;
import com.wf.wfballistics.block.entity.CameraMonitorBlockEntity;
import com.wf.wfballistics.drone.DroneRecall;
import com.wf.wfballistics.drone.cam.CameraChannels;
import com.wf.wfballistics.drone.cam.FeedTarget;
import com.wf.wfballistics.network.CameraPanelPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A monitor you can carry, holding the same bounded set of channels a monitor block does. */
public class CameraTabletItem extends Item {

    private static final int REFRESH_INTERVAL = 20;
    private static final Map<UUID, Long> REFRESHED = new HashMap<>();

    public CameraTabletItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockState state = level.getBlockState(ctx.getClickedPos());
        if (!(state.getBlock() instanceof CameraMonitorBlock)) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel sl) {
            BlockPos origin = CameraMonitorBlock.originOf(ctx.getClickedPos(), state);
            Player player = ctx.getPlayer();
            if (sl.getBlockEntity(origin) instanceof CameraMonitorBlockEntity monitor) {
                CameraChannels channels = monitor.channels();
                if (channels.isEmpty()) {
                    say(player, Component.literal("that monitor has nothing bound")
                            .withStyle(ChatFormatting.RED));
                } else {
                    ctx.getItemInHand().set(ModDataComponents.CHANNELS.get(), channels);
                    say(player, Component.literal("copied " + channels.size() + " channels")
                            .withStyle(ChatFormatting.GREEN));
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            CameraChannels channels = stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
            if (channels.isEmpty()) {
                say(player, Component.literal("no cameras bound: use a camera linker, or copy a monitor")
                        .withStyle(ChatFormatting.RED));
            } else {
                PacketDistributor.sendToPlayer(sp,
                        new CameraPanelPacket(channels.resolveAll(sl), channels.selected()));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Re-resolve and re-send the panel of whatever receiver this player is holding. */
    public static void refresh(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        Long last = REFRESHED.get(player.getUUID());
        if (last != null && now - last < REFRESH_INTERVAL) {
            return;
        }
        REFRESHED.put(player.getUUID(), now);
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof CameraTabletItem)) {
                continue;
            }
            CameraChannels channels = stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
            if (channels.isEmpty()) {
                return;
            }
            for (FeedTarget target : channels.targets()) {
                if (target.drone().isPresent() && target.resolve(level) == 0) {
                    DroneRecall.recall(level, target.drone().get());
                }
            }
            PacketDistributor.sendToPlayer(player,
                    new CameraPanelPacket(channels.resolveAll(level), channels.selected()));
            return;
        }
    }

    private static void say(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        CameraChannels channels = stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
        lines.add(channels.isEmpty()
                ? Component.literal("no cameras bound").withStyle(ChatFormatting.DARK_GRAY)
                : Component.literal(channels.size() + " cameras bound").withStyle(ChatFormatting.AQUA));
    }
}
