package com.wf.wflib.backpack;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Worn in {@link Backpacks#WORN} (key {@code key.wflib.backpack}); contents in {@link Backpacks#CONTENTS}.
 * {@link #model}: glTF drawn on the wearer's body bone (armour frame); null => not drawn.
 */
public abstract class BackpackItem extends Item {

    protected BackpackItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public abstract BackpackSpec spec();

    @Nullable
    public abstract ResourceLocation model();

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BackpackSpec s = spec();
        tooltip.add(Component.translatable("wflib.backpack.tooltip", Backpacks.used(stack), s.slots(),
                String.format(Locale.ROOT, "%.1f", Backpacks.loadKg(stack)),
                s.maxLoadKg() > 0 ? String.format(Locale.ROOT, "%.0f", s.maxLoadKg()) : "-",
                Math.round(s.contentWeight() * 100)));
        s.limits().forEach(l -> tooltip.add(Component.translatable("wflib.backpack.limit", l.max(), l.name())));
    }
}
