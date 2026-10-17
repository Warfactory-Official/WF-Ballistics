package com.wf.wflib.magazine;

import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoVariant;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Item handler (vehicle stores, crate); no room => {@code overflow}. */
public record HandlerRounds(IItemHandler handler, Consumer<ItemStack> overflow) implements RoundStore {

    @Nullable
    private static ResourceLocation variant(ItemStack s) {
        AmmoVariant v = AmmoItems.variantOf(s);
        return v == null ? null : v.id();
    }

    @Override
    @Nullable
    public ResourceLocation first(Predicate<AmmoVariant> ok) {
        for (int i = 0; i < handler.getSlots(); i++) {
            AmmoVariant v = AmmoItems.variantOf(handler.getStackInSlot(i));
            if (v != null && ok.test(v)) {
                return v.id();
            }
        }
        return null;
    }

    @Override
    public int count(ResourceLocation round) {
        int n = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (round.equals(variant(s))) {
                n += s.getCount();
            }
        }
        return n;
    }

    @Override
    public int take(ResourceLocation round, int n) {
        int left = n;
        for (int i = 0; i < handler.getSlots() && left > 0; i++) {
            if (round.equals(variant(handler.getStackInSlot(i)))) {
                left -= handler.extractItem(i, left, false).getCount();
            }
        }
        return n - left;
    }

    @Override
    public void give(ResourceLocation round, int n) {
        for (ItemStack s : MagFill.stacks(round, n)) {
            for (int i = 0; i < handler.getSlots() && !s.isEmpty(); i++) {
                s = handler.insertItem(i, s, false);
            }
            if (!s.isEmpty()) {
                overflow.accept(s);
            }
        }
    }
}
