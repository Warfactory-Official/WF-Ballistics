package com.wf.wflib.rail.supply;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The cargo of a work train, read as one hold rather than as a row of wagons.
 *
 * <p>Materials come out of it and spoil goes into it, and they share the space on purpose: a works
 * train arrives at the railhead loaded with lining and leaves loaded with rock, and the wagon that
 * emptied is the one the rock goes in. That is also what makes the resupply trip do two jobs, which is
 * why the cycle is worth having at all.</p>
 *
 * <p>{@link #take} is all or nothing. A slice of tunnel is walled, drained and cut inside one tick
 * because that order is the only thing keeping the water out, so a machine that ran out of lining
 * halfway through one would have to leave a hole in its own wall. Asking for the whole slice up front
 * means running out is a machine that has not started cutting rather than one that cannot finish.</p>
 */
public final class Stores {

    private final List<Container> holds;

    public Stores(List<Container> holds) {
        this.holds = holds;
    }

    /** @return how many of an item are aboard. */
    public int count(Item item) {
        int found = 0;
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize(); slot++) {
                ItemStack in = hold.getItem(slot);
                if (in.getItem() == item) {
                    found += in.getCount();
                }
            }
        }
        return found;
    }

    /**
     * Take a whole order off the train, or none of it.
     *
     * @return false when the train is not carrying that many, having removed nothing
     */
    public boolean take(Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (count(item) < count) {
            return false;
        }
        int left = count;
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize() && left > 0; slot++) {
                ItemStack in = hold.getItem(slot);
                if (in.getItem() != item) {
                    continue;
                }
                int moved = Math.min(left, in.getCount());
                in.shrink(moved);
                if (in.isEmpty()) {
                    hold.setItem(slot, ItemStack.EMPTY);
                }
                hold.setChanged();
                left -= moved;
            }
        }
        return true;
    }

    /**
     * Put what will fit aboard.
     *
     * @param stack shrunk by whatever was stowed, so what is left is what did not fit
     * @return how many went in
     */
    public int put(ItemStack stack) {
        int was = stack.getCount();
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack in = hold.getItem(slot);
                if (in.isEmpty()) {
                    int moved = Math.min(stack.getCount(), hold.getMaxStackSize());
                    hold.setItem(slot, stack.split(moved));
                    hold.setChanged();
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(in, stack)) {
                    continue;
                }
                int room = Math.min(in.getMaxStackSize(), hold.getMaxStackSize()) - in.getCount();
                int moved = Math.min(room, stack.getCount());
                if (moved > 0) {
                    in.grow(moved);
                    stack.shrink(moved);
                    hold.setChanged();
                }
            }
        }
        return was - stack.getCount();
    }

    /** @return slots with nothing in them, which is what decides how much can still be loaded. */
    public int freeSlots() {
        int free = 0;
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize(); slot++) {
                if (hold.getItem(slot).isEmpty()) {
                    free++;
                }
            }
        }
        return free;
    }

    /** @return everything aboard that is not one of these, which is what a return trip unloads. */
    public List<ItemStack> allBut(List<Item> keep) {
        List<ItemStack> out = new java.util.ArrayList<>();
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize(); slot++) {
                ItemStack in = hold.getItem(slot);
                if (!in.isEmpty() && !keep.contains(in.getItem())) {
                    out.add(in);
                }
            }
        }
        return out;
    }

    /**
     * Throw everything that is not a material off the train.
     *
     * <p>The spoil heap. A works train that came home to a depot with no room left in it would
     * otherwise stand there forever holding a tunnel's worth of rock in the wagons it needs for
     * bricks, which is a railway stopped by its own success.</p>
     *
     * @return how many items went over the side
     */
    public int clear(List<Item> keep) {
        int gone = 0;
        for (Container hold : this.holds) {
            for (int slot = 0; slot < hold.getContainerSize(); slot++) {
                ItemStack in = hold.getItem(slot);
                if (!in.isEmpty() && !keep.contains(in.getItem())) {
                    gone += in.getCount();
                    hold.setItem(slot, ItemStack.EMPTY);
                    hold.setChanged();
                }
            }
        }
        return gone;
    }

    public boolean isEmpty() {
        return this.holds.isEmpty();
    }
}
