package com.wf.wflib.nvg;

import com.wf.wflib.armor.ArmorMaterials;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Image-intensifier goggle. Head slot standalone (harness), or mounted on an {@link
 * com.wf.wflib.armor.ArmorPreset#nvgMount()} helmet. Optics: client resource {@code <ns>:nvg/<path>.json}.
 * ArmorItem: a plain head item renders through {@code CustomHeadLayer} as an item model, not the harness.
 */
public class NvgItem extends ArmorItem {
    public NvgItem(Properties properties) {
        super(ArmorMaterials.NVG, Type.HELMET, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        NvgState state = NvgWorn.state(stack);
        tooltip.add(Component.translatable("item.wflib.nvg.gain", Math.round(state.gain() * 100.0f)));
        if (state.ir()) {
            tooltip.add(Component.translatable("item.wflib.nvg.ir"));
        }
    }

    @Override
    public void initializeClient(java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(com.wf.wflib.nvg.client.NvgItemClient.INSTANCE);
    }
}
