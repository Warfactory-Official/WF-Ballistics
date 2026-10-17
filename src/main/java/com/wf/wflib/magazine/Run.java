package com.wf.wflib.magazine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wf.wflib.ammo.AmmoCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** {@code count} rounds of one ammo variant, adjacent in a feed. */
public record Run(ResourceLocation round, int count) {
    public static final Codec<Run> CODEC = RecordCodecBuilder.create(i -> i.group(
            AmmoCodecs.VARIANT.fieldOf("round").forGetter(Run::round),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Run::count)
    ).apply(i, Run::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Run> STREAM_CODEC = StreamCodec.composite(
            AmmoCodecs.VARIANT_STREAM.cast(), Run::round, ByteBufCodecs.VAR_INT, Run::count, Run::new);

    public Run {
        if (count < 1) {
            throw new IllegalArgumentException("run count " + count + " < 1");
        }
    }
}
