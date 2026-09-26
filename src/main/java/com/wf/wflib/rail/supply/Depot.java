package com.wf.wflib.rail.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a work train loads, and where it brings the spoil back to.
 *
 * <p>A block with an inventory in it and nothing more, on purpose. The station this eventually hangs
 * off is a multiblock somebody has to build, and the whole of what a station has to be for the railway
 * to work is a place that holds materials and can be reached by rail; keeping that as an ordinary
 * {@link Container} means the machine, the dispatcher and their tests never know which block it was,
 * and a controller with its own inventory becomes a depot by handing one over.</p>
 *
 * <p>It is read out of the world on every call rather than held. A chest can be broken, emptied or
 * unloaded while a train is a thousand blocks away down a tunnel, and a machine holding a stale
 * reference to it would go on taking blocks out of a chest that is not there any more.</p>
 */
public record Depot(BlockPos at) {

    /** How much of one item a single trip will take, so a train does not load nothing but lining. */
    private static final int MOST_PER_ITEM = 64 * 64;

    /** @return the inventory at this position, or null when there is not one any more. */
    public Container container(ServerLevel level) {
        return level.isLoaded(this.at) ? HopperBlockEntity.getContainerAt(level, this.at) : null;
    }

    public boolean exists(ServerLevel level) {
        return container(level) != null;
    }

    /**
     * Fill the train, in proportion to what is still to be built.
     *
     * <p>Proportion rather than one material at a time, because a tunnel is about thirty blocks of
     * lining to one of track: a train loaded evenly is a train that carries a wagon of rail it cannot
     * use for eleven trips. What is left over when the wagons are full stays in the depot.</p>
     *
     * @param want how much of each material the rest of the route will take
     * @return what actually went aboard
     */
    public Map<Item, Integer> load(ServerLevel level, Stores train, Map<Item, Integer> want) {
        Map<Item, Integer> loaded = new LinkedHashMap<>();
        Container store = container(level);
        if (store == null || want.isEmpty()) {
            return loaded;
        }
        int slots = train.freeSlots();
        long total = 0L;
        for (int count : want.values()) {
            total += Math.max(0, count);
        }
        if (slots <= 0 || total <= 0L) {
            return loaded;
        }
        for (Map.Entry<Item, Integer> line : want.entrySet()) {
            if (line.getValue() <= 0) {
                continue;
            }
            int share = Math.max(1, (int) (slots * (line.getValue() / (double) total)));
            int room = Math.min(MOST_PER_ITEM, share * line.getKey().getDefaultMaxStackSize());
            int asked = Math.min(line.getValue(), room);
            ItemStack got = pull(store, line.getKey(), asked);
            if (got.isEmpty()) {
                continue;
            }
            int aboard = train.put(got);
            if (!got.isEmpty()) {
                // The wagons filled up mid-stack. It goes back in the depot rather than on the floor.
                give(store, got);
            }
            if (aboard > 0) {
                loaded.put(line.getKey(), aboard);
            }
        }
        return loaded;
    }

    /**
     * Empty the spoil out of the train into the depot.
     *
     * @param keep the materials, which stay aboard: a train does not unload the lining it is about to
     *             go back out and use
     * @return how many items were put away, and how many would not fit
     */
    public int[] unload(ServerLevel level, Stores train, List<Item> keep) {
        Container store = container(level);
        if (store == null) {
            return new int[]{0, 0};
        }
        int away = 0;
        int stuck = 0;
        for (ItemStack stack : new ArrayList<>(train.allBut(keep))) {
            int was = stack.getCount();
            give(store, stack);
            away += was - stack.getCount();
            stuck += stack.getCount();
        }
        return new int[]{away, stuck};
    }

    /** @return up to {@code count} of an item, taken out of the store. */
    private static ItemStack pull(Container store, Item item, int count) {
        ItemStack out = ItemStack.EMPTY;
        int left = count;
        for (int slot = 0; slot < store.getContainerSize() && left > 0; slot++) {
            ItemStack in = store.getItem(slot);
            if (in.getItem() != item) {
                continue;
            }
            int moved = Math.min(left, in.getCount());
            if (out.isEmpty()) {
                out = in.copyWithCount(moved);
            } else {
                out.grow(moved);
            }
            in.shrink(moved);
            if (in.isEmpty()) {
                store.setItem(slot, ItemStack.EMPTY);
            }
            store.setChanged();
            left -= moved;
        }
        return out;
    }

    /** Put a stack away, shrinking it by whatever fitted. */
    private static void give(Container store, ItemStack stack) {
        for (int slot = 0; slot < store.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack in = store.getItem(slot);
            if (in.isEmpty()) {
                store.setItem(slot, stack.split(Math.min(stack.getCount(), store.getMaxStackSize())));
                store.setChanged();
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(in, stack)) {
                continue;
            }
            int room = Math.min(in.getMaxStackSize(), store.getMaxStackSize()) - in.getCount();
            int moved = Math.min(room, stack.getCount());
            if (moved > 0) {
                in.grow(moved);
                stack.shrink(moved);
                store.setChanged();
            }
        }
    }
}
