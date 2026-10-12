package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** S2C: one-shot layers at a point; the client adds travel time. {@code seed}: same variant + pitch for everyone. */
public record SoundAtPacket(List<SoundSpec> layers, double x, double y, double z, AudioCategory category,
                            long seed) implements CustomPacketPayload {
    public static final Type<SoundAtPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "sound_at"));

    public static final StreamCodec<ByteBuf, SoundAtPacket> STREAM_CODEC = StreamCodec.of((b, p) -> {
        SoundSpec.LIST_STREAM_CODEC.encode(b, p.layers);
        b.writeDouble(p.x);
        b.writeDouble(p.y);
        b.writeDouble(p.z);
        ByteBufCodecs.VAR_INT.encode(b, p.category.ordinal());
        b.writeLong(p.seed);
    }, b -> new SoundAtPacket(SoundSpec.LIST_STREAM_CODEC.decode(b), b.readDouble(), b.readDouble(), b.readDouble(),
            AudioCategory.values()[ByteBufCodecs.VAR_INT.decode(b)], b.readLong()));

    @Override
    public Type<SoundAtPacket> type() {
        return TYPE;
    }
}
