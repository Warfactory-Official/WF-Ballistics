package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.RightOfWay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Whose land the line crosses, as runs along it. */
public record RailRightOfWayPacket(RightOfWay rightOfWay) implements CustomPacketPayload {

    public static final Type<RailRightOfWayPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_row"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailRightOfWayPacket> STREAM_CODEC =
            StreamCodec.of(RailRightOfWayPacket::encode, RailRightOfWayPacket::decode);

    @Override
    public Type<RailRightOfWayPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RailRightOfWayPacket pkt) {
        RightOfWay row = pkt.rightOfWay;
        buf.writeVarInt(row.chunksCrossed());
        buf.writeVarInt(row.chunksBlocked());
        List<RightOfWay.Span> spans = row.spans();
        buf.writeVarInt(Math.min(spans.size(), RightOfWay.MAX_SPANS));
        for (int i = 0; i < spans.size() && i < RightOfWay.MAX_SPANS; i++) {
            RightOfWay.Span span = spans.get(i);
            buf.writeDouble(span.from());
            buf.writeDouble(span.to());
            buf.writeUtf(span.ownerName(), Alignment.MAX_NAME);
            buf.writeInt(span.ownerColour());
            buf.writeBoolean(span.blocked());
        }
    }

    private static RailRightOfWayPacket decode(RegistryFriendlyByteBuf buf) {
        int crossed = buf.readVarInt();
        int blocked = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), RightOfWay.MAX_SPANS);
        List<RightOfWay.Span> spans = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            spans.add(new RightOfWay.Span(buf.readDouble(), buf.readDouble(),
                    buf.readUtf(Alignment.MAX_NAME), buf.readInt(), buf.readBoolean()));
        }
        return new RailRightOfWayPacket(new RightOfWay(spans, crossed, blocked));
    }
}
