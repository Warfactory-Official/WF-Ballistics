package com.wf.wflib.grenade;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C flashbang at a point; {@code flash}/{@code deaf} 0..1 already scaled for the receiving player. */
public record FlashPacket(double x, double y, double z, float flash, float deaf) implements CustomPacketPayload {

    public static final Type<FlashPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "flashbang"));

    public static final StreamCodec<ByteBuf, FlashPacket> STREAM_CODEC = StreamCodec.of((b, p) -> {
        b.writeDouble(p.x);
        b.writeDouble(p.y);
        b.writeDouble(p.z);
        b.writeFloat(p.flash);
        b.writeFloat(p.deaf);
    }, b -> new FlashPacket(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat()));

    @Override
    public Type<FlashPacket> type() {
        return TYPE;
    }
}
