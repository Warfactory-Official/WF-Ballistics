package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.DesignClass;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** A surveyor asking whose land the line they are drawing crosses. */
public record RailRightOfWayRequestPacket(DesignClass designClass, List<AlignPoint> points)
        implements CustomPacketPayload {

    public static final Type<RailRightOfWayRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_row_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailRightOfWayRequestPacket> STREAM_CODEC =
            StreamCodec.of(RailRightOfWayRequestPacket::encode, RailRightOfWayRequestPacket::decode);

    @Override
    public Type<RailRightOfWayRequestPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RailRightOfWayRequestPacket pkt) {
        buf.writeVarInt(pkt.designClass.ordinal());
        buf.writeVarInt(Math.min(pkt.points.size(), Alignment.MAX_POINTS));
        for (int i = 0; i < pkt.points.size() && i < Alignment.MAX_POINTS; i++) {
            AlignPoint point = pkt.points.get(i);
            buf.writeDouble(point.x());
            buf.writeDouble(point.z());
            buf.writeFloat((float) point.radius());
        }
    }

    private static RailRightOfWayRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), Alignment.MAX_POINTS);
        List<AlignPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new AlignPoint(buf.readDouble(), buf.readDouble(), buf.readFloat()));
        }
        DesignClass[] classes = DesignClass.values();
        return new RailRightOfWayRequestPacket(
                ordinal >= 0 && ordinal < classes.length ? classes[ordinal] : DesignClass.BRANCH, points);
    }
}
