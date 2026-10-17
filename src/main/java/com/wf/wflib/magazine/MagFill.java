package com.wf.wflib.magazine;

import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoVariant;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loose rounds <-> magazine stack. */
public final class MagFill {
    private MagFill() {
    }

    public static MagContents contents(ItemStack mag) {
        return Objects.requireNonNull(mag.get(MagazineItems.CONTENTS.get()), "mag_contents");
    }

    public static Magazine magazine(ItemStack mag) {
        return (Magazine) mag.getItem();
    }

    public static boolean isMagazine(ItemStack stack) {
        return stack.getItem() instanceof Magazine;
    }

    public static boolean fits(Magazine mag, ResourceLocation round) {
        AmmoVariant v = Ammo.variant(round);
        return v != null && mag.accepts(v);
    }

    /** Loads on top from {@code store} following {@code preset}; stops at the first missing kind. Returns loaded. */
    public static int fill(RoundStore store, ItemStack mag, LoadPreset preset) {
        Magazine m = magazine(mag);
        requireFits(m, preset.primary());
        if (preset.mix() != null) {
            requireFits(m, preset.mix().round());
        }
        MagContents c = contents(mag);
        Map<ResourceLocation, Integer> avail = new HashMap<>();
        int free = c.free(m.perLink());
        int n = 0;
        while (n < free) {
            ResourceLocation k = preset.roundAt(c.total() + n + 1);
            int a = avail.computeIfAbsent(k, store::count);
            if (a == 0) {
                break;
            }
            avail.put(k, a - 1);
            n++;
        }
        if (n == 0) {
            return 0;
        }
        MagContents loaded = c.load(preset, n, m.perLink());
        avail.forEach((k, left) -> store.take(k, store.count(k) - left));
        mag.set(MagazineItems.CONTENTS.get(), loaded);
        return n;
    }

    /** Single kind: the store's first match. */
    public static int fill(RoundStore store, ItemStack mag) {
        Magazine m = magazine(mag);
        ResourceLocation r = store.first(m::accepts);
        return r == null ? 0 : fill(store, mag, LoadPreset.of(r));
    }

    /** All rounds back to {@code store}; links kept. Returns unloaded. */
    public static int unload(RoundStore store, ItemStack mag) {
        MagContents c = contents(mag);
        store.give(c.runs());
        mag.set(MagazineItems.CONTENTS.get(), new MagContents(List.of(), c.links()));
        return c.total();
    }

    /** {@code mag} (one link) full of {@code round}. */
    public static ItemStack full(ItemStack mag, ResourceLocation round) {
        Magazine m = magazine(mag);
        requireFits(m, round);
        ItemStack out = mag.copyWithCount(1);
        out.set(MagazineItems.CONTENTS.get(), MagContents.of(List.of(new Run(round, m.perLink())), 1));
        return out;
    }

    /** {@code n} cartridges of {@code round}, max-size stacks. */
    public static List<ItemStack> stacks(ResourceLocation round, int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            ItemStack s = AmmoItems.stack(round, 1);
            int k = Math.min(n, s.getMaxStackSize());
            s.setCount(k);
            out.add(s);
            n -= k;
        }
        return out;
    }

    private static void requireFits(Magazine mag, ResourceLocation round) {
        if (!fits(mag, round)) {
            throw new IllegalArgumentException(round + " does not fit " + mag.calibre());
        }
    }
}
