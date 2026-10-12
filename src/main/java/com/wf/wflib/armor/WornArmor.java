package com.wf.wflib.armor;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What is on one equipment slot; stacks kept alongside layers so wear writes back to the stack that
 * earned it. {@code layers.get(0)} is the garment; later layers are inserts, not necessarily every
 * inserted item in order (an unrecognized insert contributes no layer but must still round-trip), so
 * {@link #layerToInsert} carries the index mapping rather than assuming one.
 *
 * <p>Insert stacks are copies: {@code ItemContainerContents} caches its hash at construction, so a
 * stack pulled from it must never be mutated in place. Wear writes go onto the copies; the whole
 * component is rebuilt.
 */
public record WornArmor(EquipmentSlot slot, ItemStack garment, List<ItemStack> inserts,
                        List<ArmorLayer> layers, int[] layerToInsert) {

    public ItemStack stackFor(int index) {
        int insert = layerToInsert[index];
        return insert < 0 ? garment : inserts.get(insert);
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }
}
