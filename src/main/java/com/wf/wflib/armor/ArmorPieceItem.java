package com.wf.wflib.armor;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.List;
import java.util.function.Consumer;

/** A garment: what it protects against comes from its {@link ArmorPreset}, not from vanilla armour points. */
public class ArmorPieceItem extends ArmorItem {

    private final ArmorPreset preset;

    public ArmorPieceItem(ArmorPreset preset, Holder<ArmorMaterial> material, Properties properties) {
        super(material, preset.type(), properties);
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

    /** Right-click insert onto garment = fit; right-click garment with empty cursor = pop last insert. Any click with an NVG = mount. Bundle-style gesture, no screen; menu click handling is already server-authoritative. */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action,
                                            Player player, SlotAccess access) {
        if (other.getItem() instanceof com.wf.wflib.nvg.NvgItem) {
            return com.wf.wflib.nvg.NvgMount.fit(preset, stack, other, slot, player);
        }
        if (action != ClickAction.SECONDARY || stack.getCount() != 1 || !slot.allowModification(player)) {
            return false;
        }
        ArmorSpec spec = ArmorProfiles.specFor(stack);
        if (spec == null || spec.insertSlots() <= 0) {
            return false;
        }
        List<ItemStack> inserts = ArmorStacks.inserts(stack);
        if (other.isEmpty()) {
            if (inserts.isEmpty()) {
                return false;
            }
            ItemStack removed = inserts.remove(inserts.size() - 1);
            ArmorStacks.setInserts(stack, inserts);
            access.set(removed);
            return true;
        }
        if (!ArmorStacks.canInsert(stack, other)) {
            return false;
        }
        inserts.add(other.split(1));
        ArmorStacks.setInserts(stack, inserts);
        return true;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(com.wf.wflib.armor.client.ArmorPieceClient.of(preset));
    }
}
