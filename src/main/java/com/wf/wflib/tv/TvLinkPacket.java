package com.wf.wflib.tv;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * @param missileId round to fly (0 = none to connect to)
 * @param lost the round is gone; drop the link
 */
public record TvLinkPacket(int missileId, boolean lost) implements CustomPacketPayload {

    public static final Type<TvLinkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tv_link"));

    public static final StreamCodec<ByteBuf, TvLinkPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TvLinkPacket::missileId,
            ByteBufCodecs.BOOL, TvLinkPacket::lost,
            TvLinkPacket::new);

    @Override
    public Type<TvLinkPacket> type() {
        return TYPE;
    }
}
