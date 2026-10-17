package com.wf.wflib.magazine;

import com.wf.wflib.ammo.AmmoVariant;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/** Loose cartridges by ammo variant id: a magazine fills from / unloads to one. */
public interface RoundStore {
    /** First held variant {@code ok} accepts, store order; null if none. */
    @Nullable
    ResourceLocation first(Predicate<AmmoVariant> ok);

    int count(ResourceLocation round);

    /** Removes up to {@code n}; returns removed. */
    int take(ResourceLocation round, int n);

    /** No room => the store decides (drop). */
    void give(ResourceLocation round, int n);

    default void give(List<Run> runs) {
        runs.forEach(r -> give(r.round(), r.count()));
    }
}
