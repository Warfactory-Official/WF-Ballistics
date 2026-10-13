package com.wf.wflib.pda;

import com.wf.wflib.item.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Who has a PDA: {@link #carries} = anywhere in the inventory (receivers), {@link #held} = in either hand. */
public final class Pda {
    private Pda() {
    }

    public static boolean is(ItemStack stack) {
        return stack.is(ModItems.PDA.get());
    }

    public static boolean carries(Player player) {
        return player.getInventory().hasAnyMatching(Pda::is);
    }

    public static boolean held(Player player) {
        return is(player.getMainHandItem()) || is(player.getOffhandItem());
    }

    /** Held stack, main hand first; {@link ItemStack#EMPTY} if none. */
    public static ItemStack heldStack(Player player) {
        ItemStack main = player.getMainHandItem();
        if (is(main)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return is(off) ? off : ItemStack.EMPTY;
    }
}
