package com.wf.wflib.tv;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Operator asks for a round to fly; server picks ({@link TvGuidance#connect}) and answers with a {@link TvLinkPacket}. */
public record TvConnectPacket() implements CustomPacketPayload {

    public static final Type<TvConnectPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tv_connect"));

    public static final StreamCodec<ByteBuf, TvConnectPacket> STREAM_CODEC = StreamCodec.unit(new TvConnectPacket());

    @Override
    public Type<TvConnectPacket> type() {
        return TYPE;
    }
}
