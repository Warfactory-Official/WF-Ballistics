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
 * <p>Vanilla ships a swarm as one {@code ClientboundMoveEntityPacket} per mob per tick — around ten bytes
 * each, plus a packet's worth of framing each, plus the spawn and the synched-data table behind them. That is
 * what the sim tier is not paying for on the server, and shipping the same bytes anyway would move the cost
 * rather than remove it. This is one packet every {@link #INTERVAL} ticks holding the whole visible set, so
 * three hundred glyphids are one frame on the wire and the client interpolates between them.
 *
 * <p><b>Whole set, not a delta.</b> A delta needs a removal list, an acknowledgement, or a heartbeat to
 * notice a record that stopped being sent — three ways to leave a glyphid drawn where there is none. Sending
 * everything visible makes disappearance the default: what is not in the packet is not there.
 *
 * @param origin the block the offsets are measured from, so a position is three shorts rather than three
 *               doubles. Eighth-of-a-block precision, which is finer than the interpolation between two
 *               updates can resolve
 */
public record SimGlyphidSyncPacket(int originX, int originY, int originZ, List<Entry> glyphids)
        implements CustomPacketPayload {

    /**
     * Ticks between updates. Four is the whole trade: it is a quarter of the wire traffic of a per-tick
     * update and 200 ms of interpolation, which on something walking at a fifth of a block a tick is under a
     * block of lag on a body that is at least sixty-four blocks away.
     */
    public static final int INTERVAL = 4;

    /**
     * One glyphid. Position is in eighths of a block from the packet origin.
     *
     * @param id    negative, and stable for as long as the record lives, so a client can match this update
     *              against the last one and interpolate rather than teleport
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
