package com.wf.wflib.probe;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * "Carried" for probe gates: main hand, off hand, then every other slot. Same answer on both sides, so a
 * provider's availability and its handler's re-check agree.
 */
public final class ProbeInventory {

    private ProbeInventory() {
    }

    /** First match in search order, or {@link ItemStack#EMPTY}. The live stack: damage or shrink it in place. */
    public static ItemStack find(Player player, Predicate<ItemStack> matches) {
        Inventory inventory = player.getInventory();
        ItemStack main = inventory.getSelected();
        if (!main.isEmpty() && matches.test(main)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && matches.test(off)) {
            return off;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean has(Player player, Predicate<ItemStack> matches) {
        return !find(player, matches).isEmpty();
    }

    /** Units carried; creative counts as plenty. */
    public static int count(Player player, Predicate<ItemStack> matches) {
        if (player.getAbilities().instabuild) {
            return Integer.MAX_VALUE;
        }
        Inventory inventory = player.getInventory();
        int have = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                have += stack.getCount();
            }
        }
        return have;
    }

    /** Removes {@code count} units, all or nothing. Creative is free. */
    public static boolean take(Player player, Predicate<ItemStack> matches, int count) {
        if (count <= 0 || player.getAbilities().instabuild) {
            return true;
        }
        if (count(player, matches) < count) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int left = count;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                int used = Math.min(left, stack.getCount());
                stack.shrink(used);
                left -= used;
            }
        }
        inventory.setChanged();
        return true;
    }
}
