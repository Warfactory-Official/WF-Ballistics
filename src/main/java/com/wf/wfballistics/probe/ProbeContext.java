package com.wf.wfballistics.probe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** What a provider is told about the moment it is being asked. */
public record ProbeContext(Player player, Level level, HitResult hit, ItemStack mainHand,
                           ItemStack offHand, CompoundTag data, float partialTick) {

    /** Whether the player is holding {@code item} in either hand: the "bolt gun in hand" test. */
    public boolean holding(net.minecraft.world.item.Item item) {
        return mainHand.is(item) || offHand.is(item);
    }

    public boolean hasData() {
        return !data.isEmpty();
    }
}
