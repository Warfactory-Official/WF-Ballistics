package com.wf.wfballistics.item;

import com.wf.wfballistics.block.CameraMonitorBlock;
import com.wf.wfballistics.block.SecurityCameraBlock;
import com.wf.wfballistics.block.entity.CameraMonitorBlockEntity;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.cam.CameraChannels;
import com.wf.wfballistics.drone.cam.FeedTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** Picks a camera up and puts it down on a panel. */
public class CameraLinkerItem extends Item {

    /** How far it will reach for a drone. Roughly arm's length; a drone on its pad, not one in the air. */
    private static final double REACH = 6.0;

    public CameraLinkerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();

        if (state.getBlock() instanceof SecurityCameraBlock) {
            if (!level.isClientSide) {
                stack.set(ModDataComponents.LINK_TARGET.get(), FeedTarget.ofCamera(level, pos));
                say(player, Component.literal(
                                String.format("holding camera at %d %d %d", pos.getX(), pos.getY(), pos.getZ()))
                        .withStyle(ChatFormatting.AQUA));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (state.getBlock() instanceof CameraMonitorBlock) {
            if (level instanceof ServerLevel sl) {
                bind(sl, CameraMonitorBlock.originOf(pos, state), player, stack);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                FeedTarget held = stack.get(ModDataComponents.LINK_TARGET.get());
                if (other.getItem() instanceof CameraTabletItem && held != null && !held.empty()) {
                    CameraChannels after = add(other.getOrDefault(ModDataComponents.CHANNELS.get(),
                            CameraChannels.EMPTY), held, player);
                    if (after != null) {
                        other.set(ModDataComponents.CHANNELS.get(), after);
                        stack.remove(ModDataComponents.LINK_TARGET.get());
                    }
                } else {
                    stack.remove(ModDataComponents.LINK_TARGET.get());
                    say(player, Component.literal("linker cleared").withStyle(ChatFormatting.GRAY));
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        DroneEntity drone = lookedAtDrone(level, player);
        if (drone == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            if (drone.cameraSpec() == null) {
                say(player, Component.literal("that drone has no camera fitted").withStyle(ChatFormatting.RED));
            } else {
                stack.set(ModDataComponents.LINK_TARGET.get(), FeedTarget.ofDrone(drone.getUUID()));
                say(player, Component.literal("holding drone camera").withStyle(ChatFormatting.AQUA));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Put the held camera onto this panel. */
    private static void bind(ServerLevel level, BlockPos origin, @Nullable Player player, ItemStack stack) {
        if (!(level.getBlockEntity(origin) instanceof CameraMonitorBlockEntity monitor)) {
            return;
        }
        FeedTarget held = stack.get(ModDataComponents.LINK_TARGET.get());
        if (held == null || held.empty()) {
            say(player, Component.literal("linker is empty: use it on a camera first")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        CameraChannels after = add(monitor.channels(), held, player);
        if (after != null) {
            monitor.setChannels(after, level);
            stack.remove(ModDataComponents.LINK_TARGET.get());
        }
    }

    /**
     * @return the panel with this camera added, or null if it was refused, in which case the player has
     *      already been told which refusal it was. Shared by the monitor block and the handheld receiver so the
     *      cap, and the wording of hitting it, are one thing rather than two that drift apart.
     */
    @Nullable
    private static CameraChannels add(CameraChannels before, FeedTarget held, @Nullable Player player) {
        if (before.has(held)) {
            say(player, Component.literal("already on this panel").withStyle(ChatFormatting.YELLOW));
            return null;
        }
        int cap = WFConfig.CAMERA_MAX_CHANNELS.get();
        if (before.full(cap)) {
            say(player, Component.literal("panel full: " + cap + " cameras is the limit")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        CameraChannels after = before.with(held, cap);
        say(player, Component.literal(String.format("bound to channel %d of %d", after.size(), cap))
                .withStyle(ChatFormatting.GREEN));
        return after;
    }

    /**
     * @return the camera drone the player is looking at, or null.
     */
    @Nullable
    private static DroneEntity lookedAtDrone(Level level, Player player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(REACH));
        AABB box = new AABB(start, end).inflate(1.0);
        DroneEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(player, box, e -> e instanceof DroneEntity)) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(0.3).clip(start, end);
            if (hit.isEmpty()) {
                continue;
            }
            double dist = start.distanceToSqr(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                best = (DroneEntity) entity;
            }
        }
        return best;
    }

    private static void say(@Nullable Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        FeedTarget held = stack.get(ModDataComponents.LINK_TARGET.get());
        if (held == null || held.empty()) {
            lines.add(Component.literal("empty: use on a camera").withStyle(ChatFormatting.DARK_GRAY));
        } else if (held.camera().isPresent()) {
            BlockPos at = held.camera().get().pos();
            lines.add(Component.literal(String.format("camera at %d %d %d", at.getX(), at.getY(), at.getZ()))
                    .withStyle(ChatFormatting.AQUA));
        } else {
            lines.add(Component.literal("drone camera").withStyle(ChatFormatting.AQUA));
        }
    }
}
