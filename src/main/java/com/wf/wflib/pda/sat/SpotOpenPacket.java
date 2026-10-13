package com.wf.wflib.pda.sat;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Spot granted: watch feed {@code feedId}. */
public record SpotOpenPacket(int feedId) implements CustomPacketPayload {
    public static final Type<SpotOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_spot_open"));
    public static final StreamCodec<FriendlyByteBuf, SpotOpenPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpotOpenPacket::feedId, SpotOpenPacket::new);

    @Override
    public Type<SpotOpenPacket> type() {
        return TYPE;
    }
}
