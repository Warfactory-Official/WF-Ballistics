package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SurveyOpenPacket(long netId, int centreX, int centreZ) implements CustomPacketPayload {

    public static final Type<SurveyOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "survey_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyOpenPacket> STREAM_CODEC =
            StreamCodec.of((buf, pkt) -> {
                buf.writeLong(pkt.netId);
                buf.writeVarInt(pkt.centreX);
                buf.writeVarInt(pkt.centreZ);
            }, buf -> new SurveyOpenPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<SurveyOpenPacket> type() {
        return TYPE;
    }
}
