package com.wf.wflib.backpack;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Live view of {@code owner}'s worn backpack; reloads when the worn stack is replaced, writes back on change. */
public final class BackpackContainer implements Container {
    private final Player owner;
    private ItemStack bound = ItemStack.EMPTY;
    private NonNullList<ItemStack> items = NonNullList.withSize(Backpacks.MAX_SLOTS, ItemStack.EMPTY);

    BackpackContainer(Player owner) {
        this.owner = owner;
    }

    public Player owner() {
        return owner;
    }

    /** Worn stack, or EMPTY. */
    public ItemStack bound() {
        bind();
        return bound;
    }

    /** Slots of the worn backpack; 0 if none. */
    public int slots() {
        return bound().getItem() instanceof BackpackItem b ? b.spec().slots() : 0;
    }

    private void bind() {
        ItemStack worn = Backpacks.worn(owner);
        if (worn != bound) {
            bound = worn;
            items = Backpacks.contents(worn);
        }
    }

    @Override
    public int getContainerSize() {
        return Backpacks.MAX_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        bind();
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        bind();
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        bind();
        ItemStack out = ContainerHelper.removeItem(items, slot, count);
        setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        bind();
        ItemStack out = ContainerHelper.takeItem(items, slot);
        setChanged();
        return out;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        bind();
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        if (!bound.isEmpty()) {
            bound.set(Backpacks.CONTENTS.get(), ItemContainerContents.fromItems(items));
        }
    }

    /** No nested backpacks; {@link BackpackSpec.Limit}s; load cap leaves room for at least one. */
    public boolean canPlace(int slot, ItemStack stack) {
        if (slot >= slots() || stack.getItem() instanceof BackpackItem) {
            return false;
        }
        BackpackSpec spec = ((BackpackItem) bound.getItem()).spec();
        for (BackpackSpec.Limit limit : spec.limits()) {
            if (limit.matches().test(stack)) {
                int held = 0;
                for (int i = 0; i < spec.slots(); i++) {
                    held += i != slot && limit.matches().test(items.get(i)) ? 1 : 0;
                }
                if (held >= limit.max()) {
                    return false;
                }
            }
        }
        return room(slot, stack, stack.getMaxStackSize()) > 0;
    }

    /** Units of {@code stack} slot {@code slot} may hold under the load cap (its current stack excluded), {@code <= cap}. */
    public int room(int slot, ItemStack stack, int cap) {
        if (!(bound().getItem() instanceof BackpackItem item) || item.spec().maxLoadKg() <= 0) {
            return cap;
        }
        double unit = Backpacks.weigher.applyAsDouble(stack.copyWithCount(1));
        if (unit <= 0) {
            return cap;
        }
        double free = item.spec().maxLoadKg() - Backpacks.loadKg(bound) + Backpacks.weigher.applyAsDouble(items.get(slot));
        return (int) Math.max(0, Math.min(cap, Math.floor(free / unit + 1e-9)));
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return canPlace(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        bind();
        items.clear();
        setChanged();
    }
}
