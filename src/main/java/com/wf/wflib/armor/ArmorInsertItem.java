package com.wf.wflib.armor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A plate, a liner or a filter. Goes into a garment rather than onto the body, and wears out on its
 * own account: if the steel plate ate the rifle round then the steel plate is what needs replacing,
 * not the carrier it was in.
 */
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
