package com.wf.wflib.backpack;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * @param slots         1..{@link Backpacks#MAX_SLOTS}
 * @param emptyKg       own mass
 * @param maxLoadKg     contents cap (wfweight kg, before {@code contentWeight}); 0 = none
 * @param contentWeight share of contents mass the wearer carries, [0, 1]
 * @param limits        per-kind count caps
 */
public record BackpackSpec(int slots, double emptyKg, double maxLoadKg, double contentWeight, List<Limit> limits) {

    public BackpackSpec {
        if (slots < 1 || slots > Backpacks.MAX_SLOTS) {
            throw new IllegalArgumentException("backpack slots 1.." + Backpacks.MAX_SLOTS + ": " + slots);
        }
        if (contentWeight < 0 || contentWeight > 1 || maxLoadKg < 0 || emptyKg < 0) {
            throw new IllegalArgumentException("backpack weights: " + emptyKg + " / " + maxLoadKg + " / " + contentWeight);
        }
        limits = List.copyOf(limits);
    }

    /** At most {@code max} stacks matching {@code matches}; {@code name} shown when refused. */
    public record Limit(Predicate<ItemStack> matches, int max, Component name) {
    }
}
