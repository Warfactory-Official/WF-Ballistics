package com.wf.wflib.magazine;

import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoVariant;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Player inventory, offhand first; no room => dropped. */
public record PlayerRounds(Player player) implements RoundStore {

    private Inventory inv() {
        return player.getInventory();
    }

    private ItemStack at(int i) {
        return i < 0 ? inv().offhand.getFirst() : inv().items.get(i);
    }

    @Nullable
    private ResourceLocation variant(ItemStack s) {
        AmmoVariant v = AmmoItems.variantOf(s);
        return v == null ? null : v.id();
    }

    @Override
    @Nullable
    public ResourceLocation first(Predicate<AmmoVariant> ok) {
        for (int i = -1; i < inv().items.size(); i++) {
            AmmoVariant v = AmmoItems.variantOf(at(i));
            if (v != null && ok.test(v)) {
                return v.id();
            }
        }
        return null;
    }

    @Override
    public int count(ResourceLocation round) {
        int n = 0;
        for (int i = -1; i < inv().items.size(); i++) {
            ItemStack s = at(i);
            if (round.equals(variant(s))) {
                n += s.getCount();
            }
        }
        return n;
    }

    @Override
    public int take(ResourceLocation round, int n) {
        int left = n;
        for (int i = -1; i < inv().items.size() && left > 0; i++) {
            ItemStack s = at(i);
            if (round.equals(variant(s))) {
                int t = Math.min(left, s.getCount());
                s.shrink(t);
                left -= t;
            }
        }
        if (left != n) {
            inv().setChanged();
        }
        return n - left;
    }

    @Override
    public void give(ResourceLocation round, int n) {
        MagFill.stacks(round, n).forEach(inv()::placeItemBackInInventory);
    }
}
