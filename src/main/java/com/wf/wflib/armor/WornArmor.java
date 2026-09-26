package com.wf.wflib.armor;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What is actually on one equipment slot, with the stacks kept alongside the layers so wear can be
 * written back to the thing that earned it.
 *
 * <p>{@code layers.get(0)} is always the garment. Later layers are inserts, but not necessarily every
 * insert in order: an insert whose item this system does not know contributes no layer and must still
 * survive the write-back, so {@link #layerToInsert} carries the mapping rather than assuming one.
 *
 * <p>The insert stacks are copies. {@code ItemContainerContents} caches its hash at construction, so
 * a stack fetched out of it must never be mutated in place; wear goes onto the copies and the whole
 * component is rebuilt.
 */
public record WornArmor(EquipmentSlot slot, ItemStack garment, List<ItemStack> inserts,
                        List<ArmorLayer> layers, int[] layerToInsert) {

    /** The stack behind layer {@code index}: the garment, or one of the inserts. */
    public ItemStack stackFor(int index) {
        int insert = layerToInsert[index];
        return insert < 0 ? garment : inserts.get(insert);
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }
}
