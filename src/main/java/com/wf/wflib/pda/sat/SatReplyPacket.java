package com.wf.wflib.pda.sat;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Command bus answer to a {@link SatCommandPacket}, for the Satellites console. */
public record SatReplyPacket(long satId, String line) implements CustomPacketPayload {
    public static final Type<SatReplyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_sat_reply"));
    public static final StreamCodec<FriendlyByteBuf, SatReplyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SatReplyPacket::satId, ByteBufCodecs.stringUtf8(512), SatReplyPacket::line,
            SatReplyPacket::new);

    @Override
    public Type<SatReplyPacket> type() {
        return TYPE;
    }
}
