package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A carryable preset missile. Right-click to launch it at whatever the player is aiming at (a block hit, or a
 * point downrange if aiming at the sky). Renders as the actual 3D missile model in hand and inventory via
 * {@link com.wf.wfballistics.client.render.MissileItemRenderer} (see {@link #initializeClient}).
 */
public class MissileItem extends Item {

    private static final double AIM_RANGE = 256.0;
    private static final int LAUNCH_COOLDOWN = 20;

    private final MissilePreset preset;

    public MissileItem(MissilePreset preset, Properties props) {
        super(props);
        this.preset = preset;
    }

    public MissilePreset preset() {
        return this.preset;
    }

    public String modelId() {
        return this.preset.modelId();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            Vec3 target = aimTarget(level, player);
            MissileEntity missile = preset.build(level, target);
            Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
            missile.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
            level.addFreshEntity(missile);

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.getCooldowns().addCooldown(this, LAUNCH_COOLDOWN);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** Ray-traces the player's aim to a block; on a miss, a point downrange at eye level. */
    private static Vec3 aimTarget(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wfballistics.missile.model", preset.modelId())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wfballistics.missile.warhead", preset.warheadId())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // Client-only: swaps in the 3D missile-model renderer. Never loaded on the dedicated server.
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.wf.wfballistics.client.render.MissileItemRenderer.instance();
            }
        });
    }
}
