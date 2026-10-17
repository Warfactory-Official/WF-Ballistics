package com.wf.wflib.ammo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Ammo variant id codecs; registry-free => loadable in unit tests. */
public final class AmmoCodecs {
    /** Unknown variant id => decode error. */
    public static final Codec<ResourceLocation> VARIANT = ResourceLocation.CODEC.validate(id ->
            Ammo.variant(id) != null ? DataResult.success(id) : DataResult.error(() -> "unknown ammo variant " + id));
    /** Unknown variant => {@link DecoderException}. */
    public static final StreamCodec<ByteBuf, ResourceLocation> VARIANT_STREAM = ResourceLocation.STREAM_CODEC.map(id -> {
        if (Ammo.variant(id) == null) {
            throw new DecoderException("unknown ammo variant " + id);
        }
        return id;
    }, id -> id);

    private AmmoCodecs() {
    }
}
