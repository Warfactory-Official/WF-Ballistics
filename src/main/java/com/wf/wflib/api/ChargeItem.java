package com.wf.wflib.api;

import net.minecraft.world.item.ItemStack;

/** Item that is a hand charge; lets other mods (vehicle barrel insertion) read it without depending on its owner. */
public interface ChargeItem {

    ChargeSpec charge(ItemStack stack);
}
