package com.wf.wflib.magazine;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;

/** Tube {@code i} -> its round, empty = empty tube; one item each. Equality by item + components. */
public record PodTubes(List<ItemStack> tubes) {
    public static final Codec<PodTubes> CODEC =
            ItemStack.OPTIONAL_CODEC.listOf().xmap(PodTubes::new, PodTubes::tubes);
    public static final StreamCodec<RegistryFriendlyByteBuf, PodTubes> STREAM_CODEC =
            ItemStack.OPTIONAL_LIST_STREAM_CODEC.map(PodTubes::new, PodTubes::tubes);

    public PodTubes {
        tubes = tubes.stream().map(s -> s.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(1)).toList();
    }

    public static PodTubes empty(int tubes) {
        return new PodTubes(Collections.nCopies(tubes, ItemStack.EMPTY));
    }

    public int loaded() {
        return (int) tubes.stream().filter(s -> !s.isEmpty()).count();
    }

    public PodTubes with(int tube, ItemStack round) {
        ItemStack[] out = tubes.toArray(ItemStack[]::new);
        out[tube] = round;
        return new PodTubes(List.of(out));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PodTubes p && ItemStack.listMatches(tubes, p.tubes);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashStackList(tubes);
    }
}
