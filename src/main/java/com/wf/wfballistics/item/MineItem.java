package com.wf.wfballistics.item;

import com.wf.wfballistics.mine.DefuseMethod;
import com.wf.wfballistics.mine.MineEntity;
import com.wf.wfballistics.recon.ReconOwners;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** Lays one {@link MinePreset} where it is pointed. */
public class MineItem extends Item {

    private final MinePreset preset;

    public MineItem(MinePreset preset, Properties props) {
        super(props);
        this.preset = preset;
    }

    private static Component kv(String label, String value, ChatFormatting colour) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value)
                        .withStyle(colour));
    }

    public MinePreset preset() {
        return this.preset;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (preset.sownOnly()) {
            if (level.isClientSide && context.getPlayer() != null) {
                context.getPlayer()
                        .displayClientMessage(Component.literal(
                                        "This mine is sown, not laid - load it onto a minelayer")
                                .withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        Direction face = context.getClickedFace();
        BlockPos pos = context.getClickedPos()
                .relative(face);
        float yaw = player != null ? player.getYRot() : 0.0f;

        MineEntity mine = preset.build(level, yaw);
        Integer load = context.getItemInHand()
                .get(ModDataComponents.MINE_CANISTERS.get());
        if (load != null) {
            mine.loadCanisters(load);
        }
        mine.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        if (player != null) {
            mine.setOwnerId(player.getUUID());
            mine.setTeamId(ReconOwners.owningEntity(player));
        }
        boolean laidSafe = player != null && player.isSecondaryUseActive() && mine.layInert();
        level.addFreshEntity(mine);

        if (player != null && mine.getState() == MineEntity.State.SAFE) {
            player.displayClientMessage(Component.literal(laidSafe
                            ? "Laid SAFE - arm it from the probe overlay"
                            : "Mine is SAFE - arm it from the probe overlay")
                    .withStyle(ChatFormatting.YELLOW), true);
        }

        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand()
                    .shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (preset.sownOnly()) {
            tooltip.add(Component.literal("Sown, not laid - load it onto a minelayer")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        Integer load = stack.get(ModDataComponents.MINE_CANISTERS.get());
        if (load != null) {
            tooltip.add(kv("Loaded", load + " / " + preset.canisterCount(),
                    load > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED));
        }
        tooltip.add(kv("Warhead", preset.warheadId()
                .getPath(), ChatFormatting.WHITE));
        tooltip.add(kv("Trips on", preset.triggerId()
                .getPath(), ChatFormatting.WHITE));
        int damage = WarheadRegistry.peakEntityDamage(preset.warheadId());
        if (damage > 0) {
            tooltip.add(kv("Damage vs entities", "~" + damage, ChatFormatting.RED));
        }

        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.literal("Hold ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("SHIFT")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" for details")
                            .withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        tooltip.add(kv("Trigger range", String.format("%.1f blocks", preset.triggerRange()), ChatFormatting.AQUA));
        tooltip.add(kv("Crouching approach", String.format("%.1f blocks", preset.sneakTriggerRange()),
                ChatFormatting.AQUA));
        if (preset.arc() < 360.0) {
            tooltip.add(kv("Arc", (int) preset.arc() + " deg, along its facing", ChatFormatting.AQUA));
        }
        if (preset.canisterCount() > 0 && preset.canisterMineId() != null) {
            tooltip.add(kv("Carries", preset.canisterCount() + " x " + preset.canisterMineId()
                    .getPath(), ChatFormatting.WHITE));
            tooltip.add(kv("Reusable", "sheds its canisters, reload it in place", ChatFormatting.GREEN));
            tooltip.add(kv("Recovery", "crouch to pack it up, load and all", ChatFormatting.GREEN));
        }
        if (preset.bounds()) {
            tooltip.add(kv("Bounds to", String.format("%.1f blocks", preset.bounceHeight()), ChatFormatting.GOLD));
        }
        if (preset.floats()) {
            tooltip.add(kv("Buoyancy", "floats", ChatFormatting.BLUE));
        }
        if (preset.armsOnlyInWater()) {
            tooltip.add(kv("Fuze", "inert out of water", ChatFormatting.AQUA));
        }
        if (preset.selfDestructTicks() > 0) {
            int seconds = preset.selfDestructTicks() / 20;
            tooltip.add(kv("Self-destruct", seconds / 60 + "m " + seconds % 60 + "s after laying",
                    ChatFormatting.GOLD));
        }
        if (preset.buriable()) {
            tooltip.add(kv("Emplacement", "dig in with a shovel", ChatFormatting.GREEN));
        }

        tooltip.add(kv("Arming", preset.requiresActivation()
                ? "manual, then " + preset.armDelay() + " ticks"
                : preset.armDelay() + " ticks after deployment", ChatFormatting.GREEN));
        if (!preset.requiresActivation()) {
            tooltip.add(kv("Lay crouching", "place it safe, arm it later", ChatFormatting.GREEN));
        }
        if (preset.defuseMethod() == DefuseMethod.NONE) {
            tooltip.add(kv("Defusal", "not possible", ChatFormatting.RED));
        } else {
            String how = preset.defuseMethod() == DefuseMethod.TOOL
                    ? "crouch and hold a " + toolName(preset.defuseTool()) : "crouch and hold still";
            tooltip.add(kv("Defusal", how + ", " + preset.defuseTicks() + " ticks", ChatFormatting.GREEN));
            if (preset.defuseFailChance() > 0.0f) {
                tooltip.add(kv("Defusal risk", Math.round(preset.defuseFailChance() * 100) + "%",
                        ChatFormatting.RED));
            }
        }
    }

    /** The tag a tool-defusable mine wants, as something a player can act on. */
    private static String toolName(@Nullable TagKey<Item> tool) {
        return tool == null ? "tool" : tool.location()
                .getPath();
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer renderer =
                        com.wf.wfballistics.client.render.MineItemRenderers.get(preset.modelId());
                return renderer != null ? renderer : IClientItemExtensions.super.getCustomRenderer();
            }
        });
    }
}
