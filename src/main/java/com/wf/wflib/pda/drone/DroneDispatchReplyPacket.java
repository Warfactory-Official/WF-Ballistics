package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** {@link DroneDispatchPacket} outcome; {@code err:} prefix = refused. */
public record DroneDispatchReplyPacket(String line) implements CustomPacketPayload {
    public static final Type<DroneDispatchReplyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_dispatch_reply"));
    public static final StreamCodec<FriendlyByteBuf, DroneDispatchReplyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(160), DroneDispatchReplyPacket::line, DroneDispatchReplyPacket::new);

    @Override
    public Type<DroneDispatchReplyPacket> type() {
        return TYPE;
    }
}
