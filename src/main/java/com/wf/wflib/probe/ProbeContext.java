package com.wf.wflib.probe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import java.util.function.Predicate;

/** What a provider is told about the moment it is being asked. */
public record ProbeContext(Player player, Level level, HitResult hit, ItemStack mainHand,
                           ItemStack offHand, CompoundTag data, float partialTick) {

    /** In either hand. Gates should prefer {@link #carrying}: see {@link ProbeInventory}. */
    public boolean holding(Item item) {
        return mainHand.is(item) || offHand.is(item);
    }

    /** Anywhere in the inventory. */
    public boolean carrying(Item item) {
        return ProbeInventory.has(player, stack -> stack.is(item));
    }

    public boolean carrying(TagKey<Item> tag) {
        return ProbeInventory.has(player, stack -> stack.is(tag));
    }

    /** First carried match, hands first; {@link ItemStack#EMPTY} when none. */
    public ItemStack find(Predicate<ItemStack> matches) {
        return ProbeInventory.find(player, matches);
    }

    public boolean hasData() {
        return !data.isEmpty();
    }
}
