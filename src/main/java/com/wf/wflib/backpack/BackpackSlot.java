package com.wf.wflib.backpack;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Index {@code i} of a backpack; x/y moved by the screen (scrolling). Rules: {@link BackpackContainer#canPlace}. */
public class BackpackSlot extends Slot {
    private final BackpackContainer backpack;
    /** Owner's inventory panel: active only while it is shown. */
    private final boolean panel;

    public BackpackSlot(BackpackContainer backpack, int index, boolean panel) {
        super(backpack, index, -10000, -10000);
        this.backpack = backpack;
        this.panel = panel;
    }

    @Override
    public boolean isActive() {
        return getContainerSlot() < backpack.slots() && (!panel || !backpack.owner().level().isClientSide
                || Backpacks.panelShown.getAsBoolean());
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return backpack.canPlace(getContainerSlot(), stack);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return backpack.room(getContainerSlot(), stack, super.getMaxStackSize(stack));
    }
}
