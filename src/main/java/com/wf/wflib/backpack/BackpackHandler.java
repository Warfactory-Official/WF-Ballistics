package com.wf.wflib.backpack;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** {@link Backpacks#HANDLER} over a worn backpack; same rules as its slots. */
final class BackpackHandler implements IItemHandlerModifiable {
    private final BackpackContainer backpack;

    BackpackHandler(BackpackContainer backpack) {
        this.backpack = backpack;
    }

    @Override
    public int getSlots() {
        return backpack.slots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return backpack.getItem(slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        backpack.setItem(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !backpack.canPlace(slot, stack)) {
            return stack;
        }
        ItemStack here = backpack.getItem(slot);
        if (!here.isEmpty() && !ItemStack.isSameItemSameComponents(here, stack)) {
            return stack;
        }
        int cap = backpack.room(slot, stack, stack.getMaxStackSize());
        int moved = Math.min(stack.getCount(), cap - here.getCount());
        if (moved <= 0) {
            return stack;
        }
        if (!simulate) {
            backpack.setItem(slot, stack.copyWithCount(here.getCount() + moved));
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack here = backpack.getItem(slot);
        int taken = Math.min(amount, here.getCount());
        if (taken <= 0) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            backpack.setItem(slot, here.copyWithCount(here.getCount() - taken));
        }
        return here.copyWithCount(taken);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 99;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return backpack.canPlace(slot, stack);
    }
}
