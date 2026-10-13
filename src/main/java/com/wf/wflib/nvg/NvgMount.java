package com.wf.wflib.nvg;

import com.wf.wflib.armor.ArmorPreset;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

public final class NvgMount {
    private NvgMount() {
    }

    /** NVG on the cursor clicked onto a mountable helmet without one. Removal: {@link NvgDismountRecipe}. */
    public static boolean fit(ArmorPreset preset, ItemStack helmet, ItemStack nvg, Slot slot, Player player) {
        if (!preset.nvgMount() || helmet.getCount() != 1 || !slot.allowModification(player)
                || !NvgWorn.mounted(helmet).isEmpty()) {
            return false;
        }
        helmet.set(NvgComponents.MOUNT.get(), ItemContainerContents.fromItems(List.of(NvgWorn.raised(nvg.split(1)))));
        player.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.6f, 1.4f);
        return true;
    }
}
