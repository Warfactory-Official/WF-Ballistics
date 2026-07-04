package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;


public class MissileItem extends Item {

    private static final double AIM_RANGE = 256.0;
    private static final int LAUNCH_COOLDOWN = 20;

    private final MissilePreset preset;

    public MissileItem(MissilePreset preset, Properties props) {
        super(props);
        this.preset = preset;
    }

    /**
     * Ray-traces the player's aim to a block; on a miss, a point downrange at eye level.
     */
    private static Vec3 aimTarget(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    @Nullable
    private static MissileEntity lockCandidate(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        AABB search = new AABB(eye, end).inflate(1.0);
        MissileEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (MissileEntity m : level.getEntitiesOfClass(MissileEntity.class, search,
                e -> e.isAlive() && !e.isInterceptor())) {
            Optional<Vec3> clip = m.getBoundingBox().inflate(m.getPickRadius()).clip(eye, end);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(eye);
                if (d < bestSq) {
                    bestSq = d;
                    best = m;
                }
            }
        }
        return best;
    }


    @Nullable
    private static Entity aimEntity(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        AABB search = new AABB(eye, end).inflate(1.0);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities(player, search,
                x -> x != player && x.isAlive() && x.isPickable() && !(x instanceof MissileEntity))) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(0.3).clip(eye, end);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(eye);
                if (d < bestSq) {
                    bestSq = d;
                    best = e;
                }
            }
        }
        return best;
    }

    private static Component kv(String label, String value, ChatFormatting valueColour) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColour));
    }

    public MissilePreset preset() {
        return this.preset;
    }

    public ResourceLocation modelId() {
        return this.preset.modelId();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.getAbilities().instabuild) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            Vec3 target = aimTarget(level, player);
            MissileEntity missile = preset.build(level, target);
            missile.setControlId(player.getUUID());
            missile.setTeamId(WarforgeCompat.factionOfPlayer(player.getUUID()));
            if (preset.isInterceptor()) {
                MissileEntity locked = lockCandidate(level, player);
                if (locked != null) {
                    missile.setInterceptLock(locked.getUUID());
                }
            } else {
                Entity designated = aimEntity(level, player);
                if (designated != null) {
                    missile.setDesignatedTarget(designated.getUUID());
                }
            }
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

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(kv("Airframe", preset.modelId().getPath(), ChatFormatting.WHITE));
        tooltip.add(kv("Warhead", preset.warheadId().getPath(), ChatFormatting.WHITE));
        if (preset.isInterceptor()) {
            tooltip.add(kv("Damage vs missiles",
                    String.valueOf((int) MissileSimConfig.INTERCEPTOR_HIT_DAMAGE), ChatFormatting.RED));
        } else {
            int entityDamage = WarheadRegistry.peakEntityDamage(preset.warheadId());
            if (entityDamage > 0) {
                tooltip.add(kv("Damage vs entities", "~" + entityDamage, ChatFormatting.RED));
            }
        }


        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.literal("Hold ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("SHIFT").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" for details").withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        // Flight (aqua/blue).
        tooltip.add(kv("Cruise speed", String.format("%.1f blocks/tick", preset.cruiseSpeed()), ChatFormatting.AQUA));
        tooltip.add(kv("Accel / decel", String.format("%.2f / %.2f", preset.acceleration(), preset.deceleration()),
                ChatFormatting.AQUA));
        tooltip.add(kv("Turn rate", preset.turnRate() > 0.0 ? String.format("%.2f rad/tick", preset.turnRate()) : "auto",
                ChatFormatting.AQUA));
        tooltip.add(kv("Altitude", preset.highAltitude()
                ? "high, " + (int) preset.altitudeParam()
                : "terrain-follow +" + (int) preset.altitudeParam(), ChatFormatting.AQUA));
        if (!preset.isInterceptor()) {
            tooltip.add(kv("Attack angle", Double.isNaN(preset.attackAngle())
                    ? "best fit" : (int) preset.attackAngle() + " deg", ChatFormatting.AQUA));
        }
        int ticks = preset.fuelTicks();
        tooltip.add(kv("Fuel", preset.fuelType() + ", " + ticks + " ticks (~" + (ticks / 20) + "s)", ChatFormatting.BLUE));

        // Survivability (green / light purple).
        tooltip.add(kv("Health", String.valueOf((int) preset.health()), ChatFormatting.GREEN));
        if (preset.evasion() > 0.0f) {
            tooltip.add(kv("Evasion", Math.round(preset.evasion() * 100) + "%", ChatFormatting.GREEN));
        }
        if (preset.isStealth()) {
            tooltip.add(kv("Stealth", "low observable", ChatFormatting.LIGHT_PURPLE));
        }

        // Role / payload (red / gold).
        if (preset.isInterceptor()) {
            tooltip.add(kv("Interceptor kill chance", Math.round(preset.interceptChance() * 100) + "%", ChatFormatting.RED));
        }
        if (preset.explosionOffset() > 0.0f) {
            tooltip.add(kv("Airburst", (int) preset.explosionOffset() + " blocks above target", ChatFormatting.GOLD));
        }
        if (preset.splitDepth() > 0) {
            tooltip.add(kv("Split depth", String.valueOf(preset.splitDepth()), ChatFormatting.GOLD));
        }
        if (preset.fragmentCount() > 0 && ("fragmentation".equals(preset.warheadId().getPath()) || preset.splitDepth() > 0)) {
            tooltip.add(kv("Fragments", String.valueOf(preset.fragmentCount()), ChatFormatting.GOLD));
        }
        if (preset.cruiseStageId() != null || preset.attackStageId() != null) {
            String cruise = preset.cruiseStageId() != null ? preset.cruiseStageId().getPath() : "cruise";
            String attack = preset.attackStageId() != null ? preset.attackStageId().getPath() : "attack";
            tooltip.add(kv("Flight profile", cruise + " / " + attack, ChatFormatting.DARK_AQUA));
        }
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.wf.wfballistics.client.render.MissileItemRenderer.instance();
            }
        });
    }
}
