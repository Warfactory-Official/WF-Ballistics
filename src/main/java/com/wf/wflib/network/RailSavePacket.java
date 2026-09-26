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
import java.util.UUID;

/**
 * A surveyor's working copy of a route, on its way to the server that owns it.
 *
 * <p>Sent on a debounce while the route is being drawn rather than on a deliberate act of publishing:
 * a route lives on the server from its second point onwards, so a crash, a logout or a teammate taking
 * over all find it where it was left.</p>
 *
 * @param baseRevision the version this edit was made against, or 0 for a route the client believes is
 *                     new. The server refuses the write if it has moved on, which is the only thing
 *                     stopping two planners overwriting each other.
 */
public record RailSavePacket(UUID id, String name, int coreColour, DesignClass designClass,
                             List<AlignPoint> points, int baseRevision) implements CustomPacketPayload {

    public static final Type<RailSavePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailSavePacket> STREAM_CODEC =
            StreamCodec.of(RailSavePacket::encode, RailSavePacket::decode);

    @Override
    public Type<RailSavePacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RailSavePacket pkt) {
        buf.writeUUID(pkt.id);
        buf.writeUtf(pkt.name, Alignment.MAX_NAME);
        buf.writeInt(pkt.coreColour);
        buf.writeVarInt(pkt.designClass.ordinal());
        buf.writeVarInt(Math.max(0, pkt.baseRevision));
        buf.writeVarInt(Math.min(pkt.points.size(), Alignment.MAX_POINTS));
        for (int i = 0; i < pkt.points.size() && i < Alignment.MAX_POINTS; i++) {
            AlignPoint point = pkt.points.get(i);
            buf.writeDouble(point.x());
            buf.writeDouble(point.z());
            buf.writeFloat((float) point.radius());
        }
    }

    private static RailSavePacket decode(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(Alignment.MAX_NAME);
        int colour = buf.readInt();
        int ordinal = buf.readVarInt();
        int baseRevision = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), Alignment.MAX_POINTS);
        List<AlignPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new AlignPoint(buf.readDouble(), buf.readDouble(), buf.readFloat()));
        }
        DesignClass[] classes = DesignClass.values();
        DesignClass designClass = ordinal >= 0 && ordinal < classes.length
                ? classes[ordinal] : DesignClass.BRANCH;
        return new RailSavePacket(id, name, colour, designClass, points, baseRevision);
    }
}
