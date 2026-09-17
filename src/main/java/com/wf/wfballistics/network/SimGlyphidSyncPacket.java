package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Every glyphid near one player that has no entity, in one packet.
 *
 * @param origin the block the offsets are measured from, so a position is three shorts rather than three
 *      doubles. Eighth-of-a-block precision, which is finer than the interpolation between two
 *      updates can resolve
 */
public record SimGlyphidSyncPacket(int originX, int originY, int originZ, List<Entry> glyphids)
        implements CustomPacketPayload {

    /** Ticks between updates. */
    public static final int INTERVAL = 4;

    /**
     * One glyphid.
     *
     * @param id negative, and stable for as long as the record lives, so a client can match this update
     *      against the last one and interpolate rather than teleport
     * @param caste index into {@code GlyphidCaste.VALUES}, which is what picks the skin and the size
     */
    public record Entry(int id, short dx, short dy, short dz, byte yaw, byte caste) {
    }

    /**
     * Position quantum, in blocks.
     */
    public static final double QUANTUM = 0.125;

    public static final Type<SimGlyphidSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "sim_glyphids"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SimGlyphidSyncPacket> STREAM_CODEC =
            StreamCodec.of(SimGlyphidSyncPacket::encode, SimGlyphidSyncPacket::decode);

    @Override
    public Type<SimGlyphidSyncPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SimGlyphidSyncPacket pkt) {
        buf.writeVarInt(pkt.originX);
        buf.writeVarInt(pkt.originY);
        buf.writeVarInt(pkt.originZ);
        buf.writeVarInt(pkt.glyphids.size());
        for (Entry entry : pkt.glyphids) {
            buf.writeVarInt(zigzag(entry.id));
            buf.writeShort(entry.dx);
            buf.writeShort(entry.dy);
            buf.writeShort(entry.dz);
            buf.writeByte(entry.yaw);
            buf.writeByte(entry.caste);
        }
    }

    private static SimGlyphidSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int originX = buf.readVarInt();
        int originY = buf.readVarInt();
        int originZ = buf.readVarInt();
        int count = buf.readVarInt();
        List<Entry> glyphids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            glyphids.add(new Entry(unzigzag(buf.readVarInt()), buf.readShort(), buf.readShort(),
                    buf.readShort(), buf.readByte(), buf.readByte()));
        }
        return new SimGlyphidSyncPacket(originX, originY, originZ, glyphids);
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
