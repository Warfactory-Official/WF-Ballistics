package com.wf.wflib.kinetic;

import com.wf.wflib.client.render.MissileItemRenderer;
import com.wf.wflib.item.ModelledItem;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** One loadable round, as an item. */
public class KineticShellItem extends Item implements ModelledItem {

    private final KineticPreset preset;

    public KineticShellItem(KineticPreset preset, Properties properties) {
        super(properties);
        this.preset = preset;
    }

    public KineticPreset preset() {
        return preset;
    }

    @Override
    public ResourceLocation modelId() {
        return preset.modelId();
    }

    private static Component kv(String label, String value, ChatFormatting valueColour) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColour));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(kv("Warhead", preset.warheadId().getPath(), ChatFormatting.WHITE));
        tooltip.add(kv("Muzzle velocity", String.format("%.1f blocks/tick", preset.muzzleSpeed()),
                ChatFormatting.AQUA));
        int entityDamage = WarheadRegistry.peakEntityDamage(preset.warheadId());
        if (entityDamage > 0) {
            tooltip.add(kv("Damage vs entities", "~" + entityDamage, ChatFormatting.RED));
        }

        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.literal("Hold ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("SHIFT").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" for details").withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        // Flight: the four numbers a firing solution is computed from, plus what the round weighs.
        tooltip.add(kv("Drag", String.format("%.3f per tick", preset.drag()), ChatFormatting.AQUA));
        tooltip.add(kv("Gravity", String.format("%.4f blocks/tick2", preset.gravity()), ChatFormatting.AQUA));
        tooltip.add(kv("Fuse life", preset.lifeTicks() + " ticks", ChatFormatting.AQUA));
        tooltip.add(kv("Mass", String.format("%.0f kg", preset.mass()), ChatFormatting.AQUA));
        if (preset.dispersion() > 0.0f) {
            tooltip.add(kv("Dispersion", String.format("%.2f degrees", preset.dispersion()), ChatFormatting.AQUA));
        }

        // Terminal behaviour.
        if (preset.penetration() > 0) {
            tooltip.add(kv("Penetration", preset.penetration() + " blocks up to "
                    + String.format("%.0f", preset.penetrationResistance()) + " resistance", ChatFormatting.GOLD));
        }
        if (preset.airburstHeight() > 0.0) {
            tooltip.add(kv("Airburst", String.format("%.0f blocks up", preset.airburstHeight()),
                    ChatFormatting.GOLD));
        }
        if (preset.proximityRadius() > 0.0) {
            tooltip.add(kv("Proximity fuse", String.format("%.0f blocks", preset.proximityRadius()),
                    ChatFormatting.GOLD));
        }
        if (preset.fragmentCount() > 0) {
            tooltip.add(kv("Fragments", String.valueOf(preset.fragmentCount()), ChatFormatting.GOLD));
        }
        if (!preset.detonateOnEntity()) {
            tooltip.add(Component.literal("Passes through what it hits").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!preset.loadsChunks()) {
            tooltip.add(Component.literal("Lost over unloaded ground").withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return MissileItemRenderer.instance();
            }
        });
    }

    @Nullable
    public static KineticPreset presetOf(ItemStack stack) {
        return stack.getItem() instanceof KineticShellItem shell ? shell.preset() : null;
    }
}
