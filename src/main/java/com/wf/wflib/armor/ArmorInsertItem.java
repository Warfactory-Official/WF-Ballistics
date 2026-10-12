package com.wf.wflib.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Insert item (plate/liner/filter): wears on its own condition, independent of the carrier it's fitted to. */
public class ArmorInsertItem extends Item {

    private final ArmorPreset preset;

    public ArmorInsertItem(ArmorPreset preset, Properties properties) {
        super(properties);
        this.preset = preset;
    }

    public ArmorPreset preset() {
        return preset;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ArmorTooltip.append(stack, tooltip, flag);
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
