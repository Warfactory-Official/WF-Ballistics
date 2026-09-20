package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SurveyTilePacket(long netId, int tileX, int tileZ, long stamped, int imaged,
                               byte[] material, byte[] height, byte[] changed) implements CustomPacketPayload {

    public static final Type<SurveyTilePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "survey_tile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyTilePacket> STREAM_CODEC =
            StreamCodec.of(SurveyTilePacket::encode, SurveyTilePacket::decode);

    private static final int PLANE = 128 * 128;

    @Override
    public Type<SurveyTilePacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SurveyTilePacket pkt) {
        buf.writeLong(pkt.netId);
        buf.writeVarInt(pkt.tileX);
        buf.writeVarInt(pkt.tileZ);
        buf.writeLong(pkt.stamped);
        buf.writeVarInt(pkt.imaged);
        buf.writeBytes(pkt.material, 0, PLANE);
        buf.writeBytes(pkt.height, 0, PLANE);
        buf.writeBytes(pkt.changed, 0, PLANE);
    }

    private static SurveyTilePacket decode(RegistryFriendlyByteBuf buf) {
        long netId = buf.readLong();
        int tileX = buf.readVarInt();
        int tileZ = buf.readVarInt();
        long stamped = buf.readLong();
        int imaged = buf.readVarInt();
        byte[] material = new byte[PLANE];
        byte[] height = new byte[PLANE];
        byte[] changed = new byte[PLANE];
        buf.readBytes(material);
        buf.readBytes(height);
        buf.readBytes(changed);
        return new SurveyTilePacket(netId, tileX, tileZ, stamped, imaged, material, height, changed);
    }
}
