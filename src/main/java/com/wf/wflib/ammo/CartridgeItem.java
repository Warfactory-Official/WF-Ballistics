package com.wf.wflib.ammo;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** One item per {@link Calibre}; {@link AmmoItems#VARIANT} picks the load. */
public class CartridgeItem extends Item {
    private final Calibre calibre;

    public CartridgeItem(Calibre calibre, Properties properties) {
        super(properties);
        this.calibre = calibre;
    }

    public Calibre calibre() {
        return calibre;
    }

    /** Variant of another calibre (hand-edited component) => this calibre's default. */
    public AmmoVariant variant(ItemStack stack) {
        ResourceLocation id = stack.get(AmmoItems.VARIANT.get());
        AmmoVariant v = id == null ? null : Ammo.variant(id);
        return v != null && v.calibre().equals(calibre.id()) ? v : Ammo.defaultVariant(calibre.id());
    }

    @Override
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void initializeClient(java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common
            .IClientItemExtensions> consumer) {
        consumer.accept(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return com.wf.wflib.ammo.client.AmmoClient.renderer();
            }
        });
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal(calibre.name() + " " + variant(stack).name());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        AmmoVariant v = variant(stack);
        tooltip.add(Component.literal(String.format("%.0f m/s, %.1f g", v.speed(), v.projectileMassKg() * 1000))
                .withStyle(ChatFormatting.GRAY));
        if (v.pellets() > 1) {
            tooltip.add(Component.literal(v.pellets() + " pellets").withStyle(ChatFormatting.GRAY));
        }
        if (v.explosive() != null) {
            tooltip.add(Component.literal("Warhead " + v.explosive().warhead().getPath())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (v.armorPenMm() > 0) {
            tooltip.add(Component.literal(String.format("Armour %.0f mm", v.armorPenMm()))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (v.tracer() != AmmoVariant.NONE) {
            tooltip.add(Component.literal("Tracer").withStyle(ChatFormatting.RED));
        }
        if (flag.isAdvanced()) {
            tooltip.add(Component.literal(v.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
